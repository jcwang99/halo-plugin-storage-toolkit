package com.timxs.storagetoolkit.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.timxs.storagetoolkit.extension.AttachmentReference;
import com.timxs.storagetoolkit.extension.DuplicateGroup;
import com.timxs.storagetoolkit.extension.DuplicateScanStatus;
import com.timxs.storagetoolkit.extension.ReferenceScanStatus;
import com.timxs.storagetoolkit.model.DuplicateGroupVo;
import com.timxs.storagetoolkit.service.DuplicateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 重复检测服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DuplicateServiceImpl implements DuplicateService {

    private final ReactiveExtensionClient client;
    private final ExternalLinkProcessor externalLinkProcessor;
    private final ReactiveSettingFetcher settingFetcher;

    private static final int DEFAULT_SCAN_TIMEOUT_MINUTES = 5;
    private static final int DEFAULT_DUPLICATE_SCAN_CONCURRENCY = 4;

    // 内存中的扫描进度（不持久化，重启后清零）
    private final AtomicInteger scanProgress = new AtomicInteger(0);
    private final AtomicInteger scanTotal = new AtomicInteger(0);

    @Override
    public Mono<DuplicateScanStatus> startScan() {
        return getScanStatus()
            .flatMap(status -> {
                // 检查是否正在扫描
                if (status.getStatus() != null
                    && DuplicateScanStatus.Phase.SCANNING.equals(status.getStatus().getPhase())) {
                    // 检查是否超时
                    return getScanTimeoutMinutes()
                        .flatMap(timeout -> {
                            if (!isStuck(status.getStatus().getStartTime(), timeout)) {
                                return Mono.error(new IllegalStateException("扫描正在进行中"));
                            }
                            log.warn("上次扫描超时，允许重新触发");
                            return doStartScan(status);
                        });
                }
                return doStartScan(status);
            });
    }

    private boolean isStuck(Instant startTime, int timeoutMinutes) {
        if (startTime == null) return true;
        return java.time.Duration.between(startTime, Instant.now()).toMinutes() > timeoutMinutes;
    }

    /**
     * 获取扫描超时时间配置（从 global.analysis.scanTimeoutMinutes 读取）
     */
    private Mono<Integer> getScanTimeoutMinutes() {
        return settingFetcher.get("global")
            .map(setting -> {
                JsonNode analysis = setting.get("analysis");
                if (analysis != null) {
                    JsonNode timeout = analysis.get("scanTimeoutMinutes");
                    if (timeout != null) {
                        if (timeout.isNumber()) {
                            return timeout.asInt();
                        } else if (timeout.isTextual()) {
                            try {
                                return Integer.parseInt(timeout.asText());
                            } catch (NumberFormatException e) {
                                // ignore
                            }
                        }
                    }
                }
                return DEFAULT_SCAN_TIMEOUT_MINUTES;
            })
            .defaultIfEmpty(DEFAULT_SCAN_TIMEOUT_MINUTES);
    }

    /**
     * 获取重复检测并发数配置
     */
    private Mono<Integer> getDuplicateScanConcurrency() {
        return settingFetcher.get("global")
            .map(setting -> {
                JsonNode analysis = setting.get("analysis");
                if (analysis != null) {
                    JsonNode concurrency = analysis.get("duplicateScanConcurrency");
                    if (concurrency != null) {
                        if (concurrency.isNumber()) {
                            return Math.max(1, Math.min(10, concurrency.asInt()));
                        } else if (concurrency.isTextual()) {
                            try {
                                int value = Integer.parseInt(concurrency.asText());
                                return Math.max(1, Math.min(10, value));
                            } catch (NumberFormatException e) {
                                // ignore
                            }
                        }
                    }
                }
                return DEFAULT_DUPLICATE_SCAN_CONCURRENCY;
            })
            .defaultIfEmpty(DEFAULT_DUPLICATE_SCAN_CONCURRENCY);
    }

    private Mono<DuplicateScanStatus> doStartScan(DuplicateScanStatus status) {
        // 重置内存进度
        scanProgress.set(0);
        scanTotal.set(0);

        if (status.getStatus() == null) {
            status.setStatus(new DuplicateScanStatus.DuplicateScanStatusStatus());
        }
        status.getStatus().setPhase(DuplicateScanStatus.Phase.SCANNING);
        status.getStatus().setStartTime(Instant.now());
        status.getStatus().setErrorMessage(null);

        return client.update(status)
            .flatMap(updated -> {
                // 异步执行扫描
                performScan()
                    .subscribe(
                        result -> {
                            log.info("重复检测扫描完成");
                            // 扫描完成后清零内存进度
                            scanProgress.set(0);
                            scanTotal.set(0);
                        },
                        error -> {
                            log.error("重复检测扫描失败", error);
                            // 扫描失败后清零内存进度
                            scanProgress.set(0);
                            scanTotal.set(0);
                            updateScanError(error.getMessage()).subscribe();
                        }
                    );
                return Mono.just(updated);
            });
    }

    private Mono<DuplicateScanStatus> performScan() {
        log.info("开始重复检测扫描...");

        // 用于存储 MD5 -> 附件列表的映射
        Map<String, List<AttachmentInfo>> hashToAttachments = new ConcurrentHashMap<>();

        // 1. 先标记旧数据为待删除
        return markAllAsPendingDelete()
            // 2. 获取本地存储策略和并发数配置
            .then(Mono.zip(getLocalPolicyNames(), getDuplicateScanConcurrency()))
            .flatMap(tuple -> {
                Set<String> localPolicyNames = tuple.getT1();
                int concurrency = tuple.getT2();
                
                if (localPolicyNames.isEmpty()) {
                    log.info("没有本地存储策略，跳过扫描");
                    return updateScanCompleted(0, 0, 0, 0);
                }
                log.info("找到本地存储策略: {}, 并发数: {}", localPolicyNames, concurrency);

                // 3. 获取本地附件
                return client.listAll(Attachment.class, ListOptions.builder().build(), Sort.unsorted())
                    .filter(attachment -> localPolicyNames.contains(attachment.getSpec().getPolicyName()))
                    .collectList()
                    .flatMap(attachments -> {
                        // 设置内存进度总数
                        scanTotal.set(attachments.size());
                        scanProgress.set(0);
                        log.info("找到 {} 个本地附件，开始计算 MD5...", scanTotal.get());

                        if (attachments.isEmpty()) {
                            log.info("没有本地附件，完成扫描");
                            return updateScanCompleted(0, 0, 0, 0);
                        }

                        // 4. 计算 MD5（使用配置的并发数）
                        return Flux.fromIterable(attachments)
                            .flatMap(attachment -> processAttachment(attachment, hashToAttachments), concurrency)
                            .then(Mono.defer(() -> {
                                log.info("MD5 计算完成，已处理: {}/{}", scanProgress.get(), scanTotal.get());
                                
                                // 5. 创建重复组
                                return createDuplicateGroups(hashToAttachments)
                                    .then(Mono.defer(() -> {
                                        // 6. 计算统计数据并更新状态
                                        int groupCount = (int) hashToAttachments.values().stream()
                                            .filter(list -> list.size() > 1)
                                            .count();
                                        int fileCount = hashToAttachments.values().stream()
                                            .filter(list -> list.size() > 1)
                                            .mapToInt(list -> list.size() - 1)
                                            .sum();
                                        long savableSize = hashToAttachments.values().stream()
                                            .filter(list -> list.size() > 1)
                                            .mapToLong(list -> list.get(0).size * (list.size() - 1))
                                            .sum();

                                        log.info("扫描统计 - 重复组: {}, 重复文件: {}, 可节省: {} bytes", 
                                            groupCount, fileCount, savableSize);
                                        return updateScanCompleted(scanTotal.get(), groupCount, fileCount, savableSize);
                                    }));
                            }));
                    });
            })
            .doOnSuccess(s -> {
                log.info("扫描流程完成，开始清理旧数据");
                // 异步删除旧数据
                asyncDeletePendingRecords();
            })
            .onErrorResume(error -> {
                log.error("扫描过程出错: {}", error.getMessage(), error);
                return updateScanError(error.getMessage());
            });
    }

    /**
     * 处理单个附件：计算 MD5 并添加到映射
     */
    private Mono<Void> processAttachment(Attachment attachment,
                                          Map<String, List<AttachmentInfo>> hashToAttachments) {
        String attachmentName = attachment.getMetadata().getName();
        String displayName = attachment.getSpec().getDisplayName();
        Long fileSize = attachment.getSpec().getSize();
        Instant uploadTime = attachment.getMetadata().getCreationTimestamp();

        // 获取文件路径
        String permalink = attachment.getStatus() != null ? attachment.getStatus().getPermalink() : null;
        if (permalink == null) {
            log.warn("附件 {} 没有 permalink，跳过", attachmentName);
            scanProgress.incrementAndGet();
            return Mono.empty();
        }

        return calculateMd5(permalink)
            .timeout(java.time.Duration.ofSeconds(90))
            .doOnNext(md5 -> {
                AttachmentInfo info = new AttachmentInfo(attachmentName, displayName, fileSize, uploadTime, 0);
                hashToAttachments.computeIfAbsent(md5, k -> Collections.synchronizedList(new ArrayList<>())).add(info);
            })
            .doFinally(signal -> {
                // 无论成功失败都更新进度
                int count = scanProgress.incrementAndGet();
                if (count % 50 == 0) {
                    log.info("已处理 {}/{} 个附件...", count, scanTotal.get());
                }
            })
            .doOnError(e -> log.warn("计算附件 {} MD5 失败: {}", displayName, e.getMessage()))
            .onErrorResume(e -> Mono.empty())
            .then();
    }

    /**
     * 流式计算文件 MD5（通过 HTTP 获取文件内容）
     */
    private Mono<String> calculateMd5(String permalink) {
        return Mono.fromCallable(() -> {
            // 使用 ExternalLinkProcessor 将相对路径转为完整 URL
            String fullUrl = externalLinkProcessor.processLink(permalink);
            
            HttpURLConnection conn = null;
            try {
                URL url = new URL(fullUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);  // 连接超时 10 秒
                conn.setReadTimeout(30000);     // 读取超时 30 秒
                conn.setRequestMethod("GET");
                
                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    throw new RuntimeException("HTTP " + responseCode);
                }

                MessageDigest md5Digest = MessageDigest.getInstance("MD5");
                byte[] buffer = new byte[8192]; // 8KB buffer

                try (InputStream is = conn.getInputStream();
                     DigestInputStream dis = new DigestInputStream(is, md5Digest)) {
                    while (dis.read(buffer) != -1) {
                        // 流式读取，自动更新 digest
                    }
                }

                byte[] digest = md5Digest.digest();
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 获取本地存储策略名称列表
     */
    private Mono<Set<String>> getLocalPolicyNames() {
        return client.listAll(Policy.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(policy -> "local".equals(policy.getSpec().getTemplateName()))
            .map(policy -> policy.getMetadata().getName())
            .collect(Collectors.toSet());
    }

    /**
     * 标记所有旧的 DuplicateGroup 为待删除
     */
    private Mono<Void> markAllAsPendingDelete() {
        return client.listAll(DuplicateGroup.class, ListOptions.builder().build(), Sort.unsorted())
            .flatMap(group -> {
                if (group.getStatus() == null) {
                    group.setStatus(new DuplicateGroup.DuplicateGroupStatus());
                }
                group.getStatus().setPendingDelete(true);
                return client.update(group);
            })
            .then()
            .doOnSuccess(v -> log.info("已标记所有旧 DuplicateGroup 为待删除"));
    }

    /**
     * 异步删除待删除的记录
     */
    private void asyncDeletePendingRecords() {
        client.listAll(DuplicateGroup.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(group -> group.getStatus() != null && Boolean.TRUE.equals(group.getStatus().getPendingDelete()))
            .flatMap(group -> client.delete(group))
            .subscribe(
                deleted -> {},
                error -> log.error("删除旧 DuplicateGroup 失败", error),
                () -> log.info("旧 DuplicateGroup 清理完成")
            );
    }

    /**
     * 创建重复组记录
     */
    private Mono<Void> createDuplicateGroups(Map<String, List<AttachmentInfo>> hashToAttachments) {
        long timestamp = System.currentTimeMillis();

        return Flux.fromIterable(hashToAttachments.entrySet())
            .filter(entry -> entry.getValue().size() > 1) // 只保留有重复的组
            .flatMap(entry -> {
                String md5Hash = entry.getKey();
                List<AttachmentInfo> attachments = entry.getValue();

                DuplicateGroup group = new DuplicateGroup();
                group.setMetadata(new Metadata());
                group.getMetadata().setName("dup-" + md5Hash.substring(0, 8) + "-" + timestamp);

                DuplicateGroup.DuplicateGroupSpec spec = new DuplicateGroup.DuplicateGroupSpec();
                spec.setMd5Hash(md5Hash);
                group.setSpec(spec);

                DuplicateGroup.DuplicateGroupStatus status = new DuplicateGroup.DuplicateGroupStatus();
                status.setFileSize(attachments.get(0).size);
                status.setFileCount(attachments.size());
                status.setSavableSize(attachments.get(0).size * (attachments.size() - 1));
                status.setAttachmentNames(attachments.stream()
                    .map(AttachmentInfo::name)
                    .collect(Collectors.toList()));
                status.setRecommendedKeep(selectRecommendedKeep(attachments));
                status.setPendingDelete(false);
                group.setStatus(status);

                return client.create(group);
            })
            .then();
    }

    /**
     * 选择推荐保留的文件（引用次数最多，相同则选最早上传的）
     */
    private String selectRecommendedKeep(List<AttachmentInfo> attachments) {
        return attachments.stream()
            .max(Comparator
                .comparingInt(AttachmentInfo::refCount)
                .thenComparing(AttachmentInfo::uploadTime, Comparator.nullsLast(Comparator.naturalOrder())))
            .map(AttachmentInfo::name)
            .orElse(attachments.get(0).name());
    }

    /**
     * 更新扫描完成状态
     */
    private Mono<DuplicateScanStatus> updateScanCompleted(int totalCount,
                                                           int groupCount,
                                                           int fileCount,
                                                           long savableSize) {
        log.info("扫描完成 - 总附件: {}, 重复组: {}, 重复文件: {}, 可节省: {} bytes",
            totalCount, groupCount, fileCount, savableSize);

        return client.fetch(DuplicateScanStatus.class, DuplicateScanStatus.SINGLETON_NAME)
            .flatMap(status -> {
                if (status.getStatus() == null) {
                    status.setStatus(new DuplicateScanStatus.DuplicateScanStatusStatus());
                }
                status.getStatus().setPhase(DuplicateScanStatus.Phase.COMPLETED);
                status.getStatus().setLastScanTime(Instant.now());
                status.getStatus().setTotalCount(totalCount);
                status.getStatus().setDuplicateGroupCount(groupCount);
                status.getStatus().setDuplicateFileCount(fileCount);
                status.getStatus().setSavableSize(savableSize);
                status.getStatus().setErrorMessage(null);
                return client.update(status);
            });
    }

    /**
     * 更新扫描错误状态
     */
    private Mono<DuplicateScanStatus> updateScanError(String errorMessage) {
        return client.fetch(DuplicateScanStatus.class, DuplicateScanStatus.SINGLETON_NAME)
            .flatMap(status -> {
                if (status.getStatus() == null) {
                    status.setStatus(new DuplicateScanStatus.DuplicateScanStatusStatus());
                }
                status.getStatus().setPhase(DuplicateScanStatus.Phase.ERROR);
                status.getStatus().setErrorMessage(errorMessage);
                return client.update(status);
            });
    }

    @Override
    public Mono<DuplicateScanStatus> getScanStatus() {
        return client.fetch(DuplicateScanStatus.class, DuplicateScanStatus.SINGLETON_NAME)
            .switchIfEmpty(Mono.defer(() -> {
                DuplicateScanStatus status = new DuplicateScanStatus();
                status.setMetadata(new Metadata());
                status.getMetadata().setName(DuplicateScanStatus.SINGLETON_NAME);
                status.setStatus(new DuplicateScanStatus.DuplicateScanStatusStatus());
                return client.create(status);
            }))
            .map(status -> {
                // 注入内存中的扫描进度
                if (status.getStatus() != null) {
                    status.getStatus().setScannedCount(scanProgress.get());
                    status.getStatus().setTotalCount(scanTotal.get());
                }
                return status;
            });
    }

    @Override
    public Mono<ListResult<DuplicateGroupVo>> listDuplicateGroups(int page, int size) {
        return client.listAll(DuplicateGroup.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(group -> group.getStatus() == null ||
                             group.getStatus().getPendingDelete() == null ||
                             !group.getStatus().getPendingDelete())
            .collectList()
            .flatMap(groups -> {
                // 按 savableSize 降序排序
                groups.sort((a, b) -> {
                    long sizeA = a.getStatus() != null ? a.getStatus().getSavableSize() : 0;
                    long sizeB = b.getStatus() != null ? b.getStatus().getSavableSize() : 0;
                    return Long.compare(sizeB, sizeA);
                });

                int total = groups.size();
                int start = (page - 1) * size;
                int end = Math.min(start + size, total);

                List<DuplicateGroup> pageItems = start < total
                    ? groups.subList(start, end)
                    : Collections.emptyList();

                // 批量获取附件信息
                Set<String> allAttachmentNames = pageItems.stream()
                    .filter(g -> g.getStatus() != null && g.getStatus().getAttachmentNames() != null)
                    .flatMap(g -> g.getStatus().getAttachmentNames().stream())
                    .collect(Collectors.toSet());

                // 批量获取附件和引用信息
                Mono<Map<String, Attachment>> attachmentsMono = client.listAll(Attachment.class, ListOptions.builder().build(), Sort.unsorted())
                    .filter(att -> allAttachmentNames.contains(att.getMetadata().getName()))
                    .collectMap(att -> att.getMetadata().getName(), att -> att);

                // 通过 spec.attachmentName 关联附件
                Mono<Map<String, Integer>> referenceCountsMono = client.listAll(AttachmentReference.class, ListOptions.builder().build(), Sort.unsorted())
                    .filter(ref -> ref.getSpec() != null 
                        && ref.getSpec().getAttachmentName() != null
                        && allAttachmentNames.contains(ref.getSpec().getAttachmentName())
                        && (ref.getStatus() == null || !Boolean.TRUE.equals(ref.getStatus().getPendingDelete())))
                    .collectMap(
                        ref -> ref.getSpec().getAttachmentName(),
                        ref -> ref.getStatus() != null ? ref.getStatus().getReferenceCount() : 0
                    );

                // 检查是否执行过引用扫描（通过 ReferenceScanStatus.lastScanTime 判断）
                Mono<Boolean> hasReferenceScanMono = client.fetch(ReferenceScanStatus.class, ReferenceScanStatus.SINGLETON_NAME)
                    .map(scanStatus -> scanStatus.getStatus() != null 
                        && scanStatus.getStatus().getLastScanTime() != null)
                    .defaultIfEmpty(false);

                return Mono.zip(attachmentsMono, referenceCountsMono, hasReferenceScanMono)
                    .map(tuple -> {
                        Map<String, Attachment> attachmentMap = tuple.getT1();
                        Map<String, Integer> referenceCountMap = tuple.getT2();
                        boolean hasReferenceScan = tuple.getT3();
                        List<DuplicateGroupVo> voList = pageItems.stream()
                            .map(group -> convertToVo(group, attachmentMap, referenceCountMap, hasReferenceScan))
                            .collect(Collectors.toList());
                        return new ListResult<>(page, size, total, voList);
                    });
            });
    }

    /**
     * 转换为 VO
     */
    private DuplicateGroupVo convertToVo(DuplicateGroup group, 
                                          Map<String, Attachment> attachmentMap,
                                          Map<String, Integer> referenceCountMap,
                                          boolean hasReferenceScan) {
        String recommendedKeep = group.getStatus() != null ? group.getStatus().getRecommendedKeep() : null;
        String previewUrl = null;
        String mediaType = null;

        List<DuplicateGroupVo.DuplicateFileVo> files = new ArrayList<>();
        if (group.getStatus() != null && group.getStatus().getAttachmentNames() != null) {
            for (String attachmentName : group.getStatus().getAttachmentNames()) {
                Attachment attachment = attachmentMap.get(attachmentName);
                DuplicateGroupVo.DuplicateFileVo fileVo = new DuplicateGroupVo.DuplicateFileVo();
                fileVo.setAttachmentName(attachmentName);
                fileVo.setRecommended(attachmentName.equals(recommendedKeep));

                if (attachment != null) {
                    fileVo.setDisplayName(attachment.getSpec().getDisplayName());
                    fileVo.setMediaType(attachment.getSpec().getMediaType());
                    fileVo.setPermalink(attachment.getStatus() != null ? attachment.getStatus().getPermalink() : null);
                    fileVo.setUploadTime(attachment.getMetadata().getCreationTimestamp());
                    fileVo.setGroupName(attachment.getSpec().getGroupName());
                    // 从引用扫描结果获取引用次数，没有扫描数据时设为 -1 表示未扫描
                    fileVo.setReferenceCount(hasReferenceScan 
                        ? referenceCountMap.getOrDefault(attachmentName, 0) 
                        : -1);

                    // 设置预览 URL 和媒体类型（使用第一个文件的）
                    if (previewUrl == null && attachment.getStatus() != null) {
                        previewUrl = attachment.getStatus().getPermalink();
                        mediaType = attachment.getSpec().getMediaType();
                    }
                } else {
                    fileVo.setDisplayName(attachmentName);
                    fileVo.setReferenceCount(hasReferenceScan ? 0 : -1);
                }

                files.add(fileVo);
            }
        }

        DuplicateGroupVo vo = new DuplicateGroupVo();
        vo.setMd5Hash(group.getSpec().getMd5Hash());
        vo.setFileSize(group.getStatus() != null ? group.getStatus().getFileSize() : 0);
        vo.setFileCount(group.getStatus() != null ? group.getStatus().getFileCount() : 0);
        vo.setSavableSize(group.getStatus() != null ? group.getStatus().getSavableSize() : 0);
        vo.setRecommendedKeep(recommendedKeep);
        vo.setPreviewUrl(previewUrl);
        vo.setMediaType(mediaType);
        vo.setFiles(files);

        return vo;
    }

    /**
     * 附件信息内部类
     */
    private record AttachmentInfo(String name, String displayName, Long size, Instant uploadTime, int refCount) {}

    @Override
    public Mono<Void> clearAll() {
        log.info("开始清空重复检测记录...");
        
        // 删除所有 DuplicateGroup 记录
        return client.listAll(DuplicateGroup.class, ListOptions.builder().build(), Sort.unsorted())
            .flatMap(group -> client.delete(group))
            .then(Mono.defer(() -> {
                // 重置扫描状态
                return getScanStatus()
                    .flatMap(status -> {
                        if (status.getStatus() != null) {
                            status.getStatus().setPhase(null);
                            status.getStatus().setLastScanTime(null);
                            status.getStatus().setStartTime(null);
                            status.getStatus().setTotalCount(0);
                            status.getStatus().setScannedCount(0);
                            status.getStatus().setDuplicateGroupCount(0);
                            status.getStatus().setDuplicateFileCount(0);
                            status.getStatus().setSavableSize(0);
                            status.getStatus().setErrorMessage(null);
                        }
                        return client.update(status);
                    });
            }))
            .then()
            .doOnSuccess(v -> log.info("重复检测记录已清空"));
    }
}
