package com.timxs.storagetoolkit.filter;

import com.timxs.storagetoolkit.config.ProcessingConfig;
import com.timxs.storagetoolkit.extension.ProcessingLog;
import com.timxs.storagetoolkit.model.ProcessingResult;
import com.timxs.storagetoolkit.model.ProcessingStatus;
import com.timxs.storagetoolkit.service.ImageProcessor;
import com.timxs.storagetoolkit.service.ProcessingLogService;
import com.timxs.storagetoolkit.service.SettingsManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.lang.NonNull;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.security.AdditionalWebFilter;

import java.time.Instant;
import java.util.List;

/**
 * 图片处理 WebFilter
 * 拦截附件上传请求，在图片传递给存储策略之前进行处理
 * 支持控制台上传和编辑器上传两种来源
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageProcessingWebFilter implements AdditionalWebFilter {

    /**
     * 图片处理器
     */
    private final ImageProcessor imageProcessor;
    
    /**
     * 配置管理器
     */
    private final SettingsManager settingsManager;
    
    /**
     * 处理日志服务
     */
    private final ProcessingLogService processingLogService;

    /**
     * 数据缓冲区工厂
     */
    private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    /**
     * 控制台上传路径匹配器
     */
    private final ServerWebExchangeMatcher uploadMatcher = ServerWebExchangeMatchers.pathMatchers(
        "/apis/api.console.halo.run/v1alpha1/attachments/upload"
    );

    /**
     * UC API 上传路径匹配器（编辑器上传）
     */
    private final ServerWebExchangeMatcher ucUploadMatcher = ServerWebExchangeMatchers.pathMatchers(
        "/apis/uc.api.storage.halo.run/v1alpha1/attachments"
    );

    /**
     * 上传来源常量 - 控制台
     */
    private static final String SOURCE_CONSOLE = "console";
    
    /**
     * 上传来源常量 - 编辑器
     */
    private static final String SOURCE_EDITOR = "editor";

    /**
     * 过滤器主入口
     * 匹配上传请求并进行处理
     *
     * @param exchange 请求交换对象
     * @param chain    过滤器链
     * @return 完成信号
     */
    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        // 先检查 Console API
        return uploadMatcher.matches(exchange)
            .filter(ServerWebExchangeMatcher.MatchResult::isMatch)
            .switchIfEmpty(
                // 不匹配 Console API，检查 UC API
                ucUploadMatcher.matches(exchange)
                    .filter(ServerWebExchangeMatcher.MatchResult::isMatch)
                    .switchIfEmpty(chain.filter(exchange).then(Mono.empty()))
                    .flatMap(matchResult -> processUcRequest(exchange, chain).then(Mono.empty()))
            )
            .flatMap(matchResult -> processRequest(exchange, chain, SOURCE_CONSOLE));
    }

    /**
     * 处理 UC API 请求（编辑器上传）
     * 需要额外检查是否启用了编辑器图片处理
     *
     * @param exchange 请求交换对象
     * @param chain    过滤器链
     * @return 完成信号
     */
    private Mono<Void> processUcRequest(ServerWebExchange exchange, WebFilterChain chain) {
        return settingsManager.getConfig()
            .flatMap(config -> {
                // 检查全局开关
                if (!config.isEnabled()) {
                    log.debug("Image processing disabled globally, skipping UC API request");
                    return chain.filter(exchange);
                }
                // 检查编辑器图片处理开关
                if (!config.isProcessEditorImages()) {
                    log.debug("Editor image processing disabled, skipping UC API request");
                    return chain.filter(exchange);
                }
                return processRequest(exchange, chain, SOURCE_EDITOR);
            });
    }

    /**
     * 处理上传请求
     * 检查是否为 multipart 请求，并提取文件进行处理
     *
     * @param exchange 请求交换对象
     * @param chain    过滤器链
     * @param source   上传来源
     * @return 完成信号
     */
    private Mono<Void> processRequest(ServerWebExchange exchange, WebFilterChain chain, String source) {
        // 检查是否为 multipart 请求
        if (!isMultipartRequest(exchange.getRequest())) {
            return chain.filter(exchange);
        }

        // 检查 boundary 参数
        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        if (contentType == null || contentType.getParameter("boundary") == null) {
            return chain.filter(exchange);
        }

        // 解析 multipart 数据并处理
        return exchange.getMultipartData()
            .flatMap(parts -> processFilePart(exchange, chain, parts, source));
    }

    /**
     * 处理文件部分
     * 判断是否为图片文件，分别处理
     *
     * @param exchange 请求交换对象
     * @param chain    过滤器链
     * @param parts    multipart 数据
     * @param source   上传来源
     * @return 完成信号
     */
    private Mono<Void> processFilePart(ServerWebExchange exchange, WebFilterChain chain,
                                        MultiValueMap<String, Part> parts, String source) {
        // 获取文件部分
        FilePart filePart = (FilePart) parts.getFirst("file");
        if (filePart == null) {
            return chain.filter(exchange);
        }

        // 判断是否为图片文件
        if (!isImageFile(filePart)) {
            return processNonImageFile(exchange, chain, parts, filePart);
        }

        return processImageFile(exchange, chain, parts, filePart, source);
    }

    /**
     * 处理非图片文件
     * 直接传递给下游，不做任何处理
     *
     * @param exchange 请求交换对象
     * @param chain    过滤器链
     * @param parts    multipart 数据
     * @param filePart 文件部分
     * @return 完成信号
     */
    private Mono<Void> processNonImageFile(ServerWebExchange exchange, WebFilterChain chain,
                                            MultiValueMap<String, Part> parts, FilePart filePart) {
        return filePart.content().collectList()
            .flatMap(fileBuffers -> decorateExchange(exchange, parts, filePart, Flux.fromIterable(fileBuffers)))
            .flatMap(chain::filter);
    }

    /**
     * 处理图片文件
     * 根据配置决定是否进行处理
     *
     * @param exchange 请求交换对象
     * @param chain    过滤器链
     * @param parts    multipart 数据
     * @param filePart 文件部分
     * @param source   上传来源
     * @return 完成信号
     */
    private Mono<Void> processImageFile(ServerWebExchange exchange, WebFilterChain chain,
                                         MultiValueMap<String, Part> parts, FilePart filePart, String source) {
        return settingsManager.getConfig()
            .flatMap(config -> {
                // 检查全局开关
                if (!config.isEnabled()) {
                    // 禁用时，直接传递原始文件
                    return filePart.content().collectList()
                        .flatMap(originalBuffers -> decorateExchange(exchange, parts, filePart, 
                            Flux.fromIterable(originalBuffers)))
                        .flatMap(chain::filter);
                }

                // UC API 需要检查存储策略是否匹配
                if (SOURCE_EDITOR.equals(source)) {
                    return shouldProcessForUcApi(config)
                        .flatMap(shouldProcess -> {
                            if (!shouldProcess) {
                                log.debug("Skipping UC API processing: policy not matched");
                                return filePart.content().collectList()
                                    .flatMap(originalBuffers -> decorateExchange(exchange, parts, filePart, 
                                        Flux.fromIterable(originalBuffers)))
                                    .flatMap(chain::filter);
                            }
                            return doProcessImage(exchange, chain, parts, filePart, config, source);
                        });
                }

                // Console API 检查存储策略和分组是否匹配
                if (!shouldProcessForPolicyAndGroup(parts, config)) {
                    log.debug("Skipping processing: policy or group not in target list");
                    return filePart.content().collectList()
                        .flatMap(originalBuffers -> decorateExchange(exchange, parts, filePart, 
                            Flux.fromIterable(originalBuffers)))
                        .flatMap(chain::filter);
                }

                return doProcessImage(exchange, chain, parts, filePart, config, source);
            });
    }

    /**
     * 检查 UC API 是否应该处理
     * 根据文章附件存储策略判断
     *
     * @param config 处理配置
     * @return 是否应该处理
     */
    private Mono<Boolean> shouldProcessForUcApi(ProcessingConfig config) {
        String targetPolicy = config.getTargetPolicy();
        // 未配置目标策略时，处理所有
        if (targetPolicy == null || targetPolicy.isBlank()) {
            return Mono.just(true);
        }
        
        // 获取文章附件存储策略并比较
        return settingsManager.getPostAttachmentPolicy()
            .map(postPolicy -> {
                boolean matches = targetPolicy.equals(postPolicy);
                if (!matches) {
                    log.debug("UC API policy mismatch: target={}, postPolicy={}", targetPolicy, postPolicy);
                }
                return matches;
            })
            .defaultIfEmpty(false);
    }

    /**
     * 检查当前上传的存储策略和分组是否在目标列表中
     *
     * @param parts  multipart 数据
     * @param config 处理配置
     * @return 是否应该处理
     */
    private boolean shouldProcessForPolicyAndGroup(MultiValueMap<String, Part> parts, ProcessingConfig config) {
        // 检查存储策略
        String targetPolicy = config.getTargetPolicy();
        if (targetPolicy != null && !targetPolicy.isBlank()) {
            FormFieldPart policyPart = (FormFieldPart) parts.getFirst("policyName");
            if (policyPart == null) {
                return false;
            }
            String currentPolicy = policyPart.value();
            if (!targetPolicy.equals(currentPolicy)) {
                log.debug("Policy mismatch: target={}, current={}", targetPolicy, currentPolicy);
                return false;
            }
        }
        
        // 检查分组
        List<String> targetGroups = config.getTargetGroups();
        if (targetGroups != null && !targetGroups.isEmpty()) {
            Part groupPart = parts.getFirst("groupName");
            String currentGroup = "";
            if (groupPart instanceof FormFieldPart formField) {
                currentGroup = formField.value();
            }
            // 如果当前分组不在目标分组列表中，跳过处理
            if (!targetGroups.contains(currentGroup)) {
                log.debug("Group mismatch: targetGroups={}, currentGroup={}", targetGroups, currentGroup);
                return false;
            }
        }
        
        return true;
    }

    /**
     * 执行图片处理
     * 读取文件内容，调用处理器处理，然后重建请求
     *
     * @param exchange 请求交换对象
     * @param chain    过滤器链
     * @param parts    multipart 数据
     * @param filePart 文件部分
     * @param config   处理配置
     * @param source   上传来源
     * @return 完成信号
     */
    private Mono<Void> doProcessImage(ServerWebExchange exchange, WebFilterChain chain,
                                       MultiValueMap<String, Part> parts, FilePart filePart,
                                       ProcessingConfig config, String source) {
        String filename = filePart.filename();
        String contentType = getContentType(filePart);
        Instant startTime = Instant.now();

        // 合并所有数据缓冲区为一个
        return DataBufferUtils.join(filePart.content())
            .flatMap(dataBuffer -> {
                // 读取图片数据
                byte[] imageData = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(imageData);
                DataBufferUtils.release(dataBuffer);

                long originalSize = imageData.length;

                // 检查是否需要处理
                String skipReason = imageProcessor.getSkipReason(contentType, originalSize, config);
                if (skipReason != null) {
                    log.debug("File skipped: {} - {}", filename, skipReason);
                    // 记录跳过日志
                    saveSkippedLog(filename, contentType, originalSize, startTime, skipReason, source);
                    DataBuffer buffer = bufferFactory.wrap(imageData);
                    return decorateExchange(exchange, parts, filePart, Flux.just(buffer))
                        .flatMap(chain::filter);
                }

                // 处理图片
                return imageProcessor.process(imageData, filename, contentType, config)
                    .map(result -> {
                        // 保存处理日志
                        saveProcessingLog(result, filename, originalSize, startTime, source);
                        return result;
                    })
                    .flatMap(result -> {
                        // 处理失败或跳过时，使用原始文件
                        if (result.status() == ProcessingStatus.SKIPPED ||
                            result.status() == ProcessingStatus.FAILED) {
                            log.debug("Processing skipped or failed, using original");
                            DataBuffer buffer = bufferFactory.wrap(imageData);
                            return decorateExchange(exchange, parts, filePart, Flux.just(buffer))
                                .flatMap(chain::filter);
                        }

                        // 记录处理结果
                        log.info("Image processed: {} -> {} ({} bytes -> {} bytes, {}% reduction)",
                            filename, result.filename(),
                            originalSize, result.data().length,
                            originalSize > 0 ? (100 - (result.data().length * 100 / originalSize)) : 0);

                        // 使用处理后的数据重建请求
                        DataBuffer processedBuffer = bufferFactory.wrap(result.data());
                        String newFilename = result.filename();
                        MediaType newContentType = MediaType.parseMediaType(result.contentType());
                        
                        return decorateExchange(exchange, parts, filePart, 
                            Flux.just(processedBuffer), newFilename, newContentType)
                            .flatMap(chain::filter);
                    })
                    .onErrorResume(e -> {
                        // 处理异常时，使用原始文件继续上传
                        log.error("Image processing error, using original file: {}", e.getMessage());
                        DataBuffer buffer = bufferFactory.wrap(imageData);
                        return decorateExchange(exchange, parts, filePart, Flux.just(buffer))
                            .flatMap(chain::filter);
                    });
            });
    }

    /**
     * 装饰 Exchange，重建 multipart 请求体
     * 使用原始文件名和内容类型
     *
     * @param exchange       请求交换对象
     * @param parts          multipart 数据
     * @param filePart       原始文件部分
     * @param processedImage 处理后的图片数据流
     * @return 装饰后的 Exchange
     */
    private Mono<ServerWebExchange> decorateExchange(ServerWebExchange exchange,
                                                      MultiValueMap<String, Part> parts,
                                                      FilePart filePart,
                                                      Flux<DataBuffer> processedImage) {
        return decorateExchange(exchange, parts, filePart, processedImage, 
            filePart.filename(), filePart.headers().getContentType());
    }

    /**
     * 装饰 Exchange，重建 multipart 请求体
     * 支持新文件名和内容类型
     *
     * @param exchange       请求交换对象
     * @param parts          multipart 数据
     * @param filePart       原始文件部分
     * @param processedImage 处理后的图片数据流
     * @param newFilename    新文件名
     * @param newContentType 新内容类型
     * @return 装饰后的 Exchange
     */
    private Mono<ServerWebExchange> decorateExchange(ServerWebExchange exchange,
                                                      MultiValueMap<String, Part> parts,
                                                      FilePart filePart,
                                                      Flux<DataBuffer> processedImage,
                                                      String newFilename,
                                                      MediaType newContentType) {
        String boundary = getBoundary(exchange);
        
        if (boundary == null) {
            log.warn("Missing boundary in request");
            return Mono.just(exchange);
        }

        // 收集处理后的数据并创建装饰后的 Exchange
        return processedImage.collectList()
            .flatMap(buffers -> createDecoratedExchange(exchange, parts, 
                boundary, buffers, newFilename, newContentType));
    }

    /**
     * 创建装饰后的 Exchange
     * 重建 multipart 请求体，替换文件内容
     *
     * @param exchange       请求交换对象
     * @param parts          multipart 数据
     * @param boundary       multipart 边界
     * @param buffers        文件数据缓冲区列表
     * @param filename       文件名
     * @param contentType    内容类型
     * @return 装饰后的 Exchange
     */
    private Mono<ServerWebExchange> createDecoratedExchange(final ServerWebExchange exchange,
                                                             MultiValueMap<String, Part> parts,
                                                             String boundary,
                                                             List<DataBuffer> buffers,
                                                             String filename,
                                                             MediaType contentType) {
        // 构建 multipart 头部
        String multipartContent = buildMultipartContent(boundary, parts, filename, contentType);
        // 构建 multipart 尾部
        String footer = "\r\n--" + boundary + "--\r\n";

        final DataBuffer headerBuffer = bufferFactory.wrap(multipartContent.getBytes());
        final DataBuffer footerBuffer = bufferFactory.wrap(footer.getBytes());

        // 确保有数据
        final List<DataBuffer> finalBuffers = buffers.isEmpty()
            ? List.of(bufferFactory.wrap(new byte[0]))
            : buffers;

        // 创建装饰后的请求
        final ServerHttpRequest decoratedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            @NonNull
            public Flux<DataBuffer> getBody() {
                // 组合：头部 + 文件内容 + 尾部
                return Flux.just(headerBuffer)
                    .concatWith(Flux.fromIterable(finalBuffers))
                    .concatWith(Flux.just(footerBuffer));
            }

            @Override
            @NonNull
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(exchange.getRequest().getHeaders());
                return headers;
            }
        };

        // 返回装饰后的 Exchange
        return Mono.just(new ServerWebExchangeDecorator(exchange) {
            @Override
            @NonNull
            public ServerHttpRequest getRequest() {
                return decoratedRequest;
            }
        });
    }

    /**
     * 构建 multipart 内容头部
     * 遍历所有字段，只替换 file 内容
     * 注意：file 字段必须放在最后，因为文件内容在 header 之后单独添加
     *
     * @param boundary    multipart 边界
     * @param parts       multipart 数据
     * @param filename    文件名
     * @param contentType 内容类型
     * @return multipart 头部字符串
     */
    private String buildMultipartContent(String boundary,
                                          MultiValueMap<String, Part> parts,
                                          String filename,
                                          MediaType contentType) {
        StringBuilder content = new StringBuilder();

        // 先遍历所有非 file 字段
        for (var entry : parts.entrySet()) {
            String partName = entry.getKey();
            List<Part> partList = entry.getValue();
            
            if ("file".equals(partName)) {
                continue; // file 字段最后处理
            }
            
            for (Part part : partList) {
                if (part instanceof FormFieldPart formField) {
                    // 表单字段原样保留
                    content.append("--").append(boundary).append("\r\n");
                    content.append("Content-Disposition: form-data; name=\"").append(partName).append("\"\r\n");
                    content.append("\r\n");
                    content.append(formField.value()).append("\r\n");
                }
            }
        }

        // 最后添加 file 字段（文件内容在 header 之后单独添加）
        content.append("--").append(boundary).append("\r\n");
        content.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
            .append(filename).append("\"\r\n");
        String contentTypeStr = contentType != null ? contentType.toString() : "application/octet-stream";
        content.append("Content-Type: ").append(contentTypeStr).append("\r\n");
        content.append("\r\n");

        return content.toString();
    }

    /**
     * 获取 multipart 边界
     *
     * @param exchange 请求交换对象
     * @return 边界字符串，如果不是 multipart 请求则返回 null
     */
    private String getBoundary(ServerWebExchange exchange) {
        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        if (contentType == null || !contentType.isCompatibleWith(MediaType.MULTIPART_FORM_DATA)) {
            return null;
        }
        return contentType.getParameter("boundary");
    }

    /**
     * 检查是否为 multipart 请求
     *
     * @param request HTTP 请求
     * @return 是否为 multipart 请求
     */
    private boolean isMultipartRequest(ServerHttpRequest request) {
        MediaType contentType = request.getHeaders().getContentType();
        return contentType != null && contentType.isCompatibleWith(MediaType.MULTIPART_FORM_DATA);
    }

    /**
     * 检查是否为图片文件
     *
     * @param filePart 文件部分
     * @return 是否为图片
     */
    private boolean isImageFile(FilePart filePart) {
        MediaType contentType = filePart.headers().getContentType();
        return contentType != null && contentType.getType().equals("image");
    }

    /**
     * 获取文件的内容类型
     *
     * @param filePart 文件部分
     * @return 内容类型字符串
     */
    private String getContentType(FilePart filePart) {
        MediaType contentType = filePart.headers().getContentType();
        return contentType != null ? contentType.toString() : null;
    }

    /**
     * 保存处理日志
     *
     * @param result           处理结果
     * @param originalFilename 原始文件名
     * @param originalSize     原始大小
     * @param startTime        开始时间
     * @param source           上传来源
     */
    private void saveProcessingLog(ProcessingResult result, String originalFilename,
                                    long originalSize, Instant startTime, String source) {
        try {
            ProcessingLog logEntry = new ProcessingLog();
            ProcessingLog.ProcessingLogSpec spec = new ProcessingLog.ProcessingLogSpec();

            spec.setOriginalFilename(originalFilename);
            spec.setResultFilename(result.filename());
            spec.setOriginalSize(originalSize);
            spec.setResultSize(result.data().length);
            spec.setStatus(result.status());
            spec.setProcessedAt(startTime);
            spec.setProcessingDuration(Instant.now().toEpochMilli() - startTime.toEpochMilli());
            spec.setSource(source);

            if (result.message() != null) {
                spec.setErrorMessage(result.message());
            }

            logEntry.setSpec(spec);

            // 异步保存日志
            processingLogService.save(logEntry)
                .subscribe(
                    saved -> log.debug("Processing log saved: {}", saved.getMetadata().getName()),
                    error -> log.error("Failed to save processing log", error)
                );
        } catch (Exception e) {
            log.error("Failed to create processing log", e);
        }
    }

    /**
     * 保存跳过处理的日志
     *
     * @param filename    文件名
     * @param contentType 内容类型
     * @param fileSize    文件大小
     * @param startTime   开始时间
     * @param reason      跳过原因
     * @param source      上传来源
     */
    private void saveSkippedLog(String filename, String contentType, long fileSize, 
                                 Instant startTime, String reason, String source) {
        try {
            ProcessingLog logEntry = new ProcessingLog();
            ProcessingLog.ProcessingLogSpec spec = new ProcessingLog.ProcessingLogSpec();

            spec.setOriginalFilename(filename);
            spec.setResultFilename(filename);
            spec.setOriginalSize(fileSize);
            spec.setResultSize(fileSize);
            spec.setStatus(ProcessingStatus.SKIPPED);
            spec.setProcessedAt(startTime);
            spec.setProcessingDuration(Instant.now().toEpochMilli() - startTime.toEpochMilli());
            spec.setErrorMessage(reason);
            spec.setSource(source);

            logEntry.setSpec(spec);

            // 异步保存日志
            processingLogService.save(logEntry)
                .subscribe(
                    saved -> log.debug("Skipped log saved: {}", saved.getMetadata().getName()),
                    error -> log.error("Failed to save skipped log", error)
                );
        } catch (Exception e) {
            log.error("Failed to create skipped log", e);
        }
    }

    /**
     * 获取过滤器顺序
     * 返回 0 表示默认优先级
     *
     * @return 顺序值
     */
    @Override
    public int getOrder() {
        return 0;
    }
}
