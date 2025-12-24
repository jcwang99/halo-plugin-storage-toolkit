package com.timxs.storagetoolkit.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.timxs.storagetoolkit.config.*;
import com.timxs.storagetoolkit.model.ImageFormat;
import com.timxs.storagetoolkit.model.WatermarkPosition;
import com.timxs.storagetoolkit.model.WatermarkType;
import com.timxs.storagetoolkit.service.SettingsManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 配置管理器实现
 * 从 Halo 插件设置中读取配置，转换为 ProcessingConfig 对象
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsManagerImpl implements SettingsManager {

    /**
     * Halo 响应式设置获取器，用于读取插件设置
     */
    private final ReactiveSettingFetcher settingFetcher;
    
    /**
     * Halo 响应式扩展客户端，用于读取系统配置
     */
    private final ReactiveExtensionClient extensionClient;
    
    /**
     * 系统配置 ConfigMap 名称
     */
    private static final String SYSTEM_CONFIG_NAME = "system";
    
    /**
     * JSON 解析器
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 获取当前配置
     * 合并全局设置、图片处理设置和日志设置
     *
     * @return 完整的处理配置
     */
    @Override
    public Mono<ProcessingConfig> getConfig() {
        return buildConfig();
    }

    /**
     * 构建配置对象
     * 并行读取各组设置，合并到一个 ProcessingConfig 对象中
     *
     * @return 配置对象
     */
    private Mono<ProcessingConfig> buildConfig() {
        ProcessingConfig config = new ProcessingConfig();
        
        // 并行读取三组设置
        return Mono.zip(
            getGlobalSettings(config),
            getImageProcessingSettings(config),
            getLogSettings(config)
        ).thenReturn(config)
        .onErrorResume(e -> {
            log.warn("Failed to load settings, using defaults: {}", e.getMessage());
            return Mono.just(new ProcessingConfig());
        });
    }

    /**
     * 从 global 组读取全局设置
     * 设置项嵌套在 basic 子组下
     *
     * @param config 配置对象（会被修改）
     * @return 完成信号
     */
    private Mono<Boolean> getGlobalSettings(ProcessingConfig config) {
        return settingFetcher.get("global")
            .doOnNext(setting -> {
                JsonNode basic = setting.get("basic");
                if (basic != null) {
                    // 新的嵌套结构
                    config.setEnabled(getBoolean(basic, "imageProcessingEnabled", true));
                    config.setTargetPolicy(getString(basic, "targetPolicy", ""));
                    config.setProcessEditorImages(getBoolean(basic, "processEditorImages", false));
                    List<String> groups = getStringList(basic, "targetGroups");
                    if (groups != null && !groups.isEmpty()) {
                        config.setTargetGroups(groups);
                    }
                } else {
                    // 兼容旧的扁平结构
                    config.setEnabled(getBoolean(setting, "imageProcessingEnabled", true));
                    config.setTargetPolicy(getString(setting, "targetPolicy", ""));
                    config.setProcessEditorImages(getBoolean(setting, "processEditorImages", false));
                    List<String> groups = getStringList(setting, "targetGroups");
                    if (groups != null && !groups.isEmpty()) {
                        config.setTargetGroups(groups);
                    }
                }
            })
            .thenReturn(true)
            .onErrorReturn(true);
    }

    /**
     * 从 imageProcessing 组读取图片处理设置
     * 包含文件过滤、格式转换、水印三个子组
     *
     * @param config 配置对象（会被修改）
     * @return 完成信号
     */
    private Mono<Boolean> getImageProcessingSettings(ProcessingConfig config) {
        return settingFetcher.get("imageProcessing")
            .doOnNext(setting -> {
                // 文件过滤（嵌套在 fileFilter 下）
                JsonNode fileFilter = setting.get("fileFilter");
                if (fileFilter != null) {
                    List<String> formats = getStringList(fileFilter, "allowedFormats");
                    // 无论是否为空都设置，空列表表示不处理任何图片
                    config.setAllowedFormats(formats != null ? formats : List.of());
                    // 文件大小单位是 KB，需要转换为字节
                    long minSize = getLong(fileFilter, "minFileSize", 0) * 1024;
                    long maxSize = getLong(fileFilter, "maxFileSize", 0) * 1024;
                    config.setMinFileSize(minSize);
                    config.setMaxFileSize(maxSize);
                    log.debug("文件大小过滤配置 - minFileSize: {} KB, maxFileSize: {} KB", minSize / 1024, maxSize / 1024);
                }
                
                // 格式转换（嵌套在 formatConversion 下）
                JsonNode formatNode = setting.get("formatConversion");
                FormatConversionConfig format = config.getFormatConversion();
                if (formatNode != null) {
                    format.setEnabled(getBoolean(formatNode, "enabled", false));
                    String formatStr = getString(formatNode, "targetFormat", "WEBP");
                    try {
                        format.setTargetFormat(ImageFormat.valueOf(formatStr));
                    } catch (IllegalArgumentException e) {
                        format.setTargetFormat(ImageFormat.WEBP);
                    }
                    format.setOutputQuality(getInt(formatNode, "outputQuality", 85));
                }
                
                // 水印设置（嵌套在 watermark 下）
                JsonNode watermarkNode = setting.get("watermark");
                WatermarkConfig watermark = config.getWatermark();
                if (watermarkNode != null) {
                    watermark.setEnabled(getBoolean(watermarkNode, "enabled", false));
                    
                    String typeStr = getString(watermarkNode, "type", "TEXT");
                    log.debug("读取水印配置 - enabled: {}, type: {}", watermark.isEnabled(), typeStr);
                    try {
                        watermark.setType(WatermarkType.valueOf(typeStr));
                    } catch (IllegalArgumentException e) {
                        watermark.setType(WatermarkType.TEXT);
                    }
                    
                    String watermarkText = getString(watermarkNode, "text", "");
                    String watermarkImageUrl = getString(watermarkNode, "imageUrl", "");
                    log.debug("读取水印配置 - text: '{}', imageUrl: '{}'", watermarkText, watermarkImageUrl);
                    
                    watermark.setText(watermarkText);
                    watermark.setImageUrl(watermarkImageUrl);
                    // imageScale 是百分比（1-100），需要转换为小数（0.01-1.0）
                    watermark.setImageScale(getDouble(watermarkNode, "imageScale", 20) / 100.0);
                    
                    String posStr = getString(watermarkNode, "position", "BOTTOM_RIGHT");
                    try {
                        watermark.setPosition(WatermarkPosition.valueOf(posStr));
                    } catch (IllegalArgumentException e) {
                        watermark.setPosition(WatermarkPosition.BOTTOM_RIGHT);
                    }
                    
                    watermark.setOpacity(getInt(watermarkNode, "opacity", 50));
                    watermark.setFontSize(getInt(watermarkNode, "fontSize", 48));
                    watermark.setColor(getString(watermarkNode, "color", "#FFFFFF"));
                    // 边距是百分比（0-50）
                    watermark.setMarginX(getDouble(watermarkNode, "marginX", 5));
                    watermark.setMarginY(getDouble(watermarkNode, "marginY", 5));
                }
            })
            .thenReturn(true)
            .onErrorReturn(true);
    }

    /**
     * 从 log 组读取日志设置
     *
     * @param config 配置对象（会被修改）
     * @return 完成信号
     */
    private Mono<Boolean> getLogSettings(ProcessingConfig config) {
        return settingFetcher.get("log")
            .doOnNext(setting -> {
                config.setLogRetentionDays(getInt(setting, "logRetentionDays", 30));
            })
            .thenReturn(true)
            .onErrorReturn(true);
    }

    /**
     * 获取文章附件存储策略
     * 从系统配置 ConfigMap 的 post 组读取 attachmentPolicyName
     * 用于判断编辑器上传的图片是否需要处理
     *
     * @return 存储策略名称
     */
    @Override
    public Mono<String> getPostAttachmentPolicy() {
        return extensionClient.fetch(ConfigMap.class, SYSTEM_CONFIG_NAME)
            .map(configMap -> {
                Map<String, String> data = configMap.getData();
                if (data == null) {
                    return "";
                }
                // 读取 post 组的配置
                String postConfig = data.get("post");
                if (postConfig == null || postConfig.isBlank()) {
                    return "";
                }
                // 解析 JSON 获取 attachmentPolicyName
                try {
                    JsonNode postNode = OBJECT_MAPPER.readTree(postConfig);
                    JsonNode policyNode = postNode.get("attachmentPolicyName");
                    if (policyNode != null && policyNode.isTextual()) {
                        return policyNode.asText();
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse post config: {}", e.getMessage());
                }
                return "";
            })
            .defaultIfEmpty("");
    }

    // ========== JsonNode 辅助方法 ==========

    /**
     * 从 JsonNode 获取布尔值
     */
    private boolean getBoolean(JsonNode node, String key, boolean defaultValue) {
        JsonNode value = node.get(key);
        if (value != null && value.isBoolean()) {
            return value.asBoolean();
        }
        return defaultValue;
    }

    /**
     * 从 JsonNode 获取整数值
     * 支持数字类型和字符串类型
     */
    private int getInt(JsonNode node, String key, int defaultValue) {
        JsonNode value = node.get(key);
        if (value != null) {
            if (value.isNumber()) {
                return value.asInt();
            }
            if (value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText());
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    /**
     * 从 JsonNode 获取长整数值
     * 支持数字类型和字符串类型
     */
    private long getLong(JsonNode node, String key, long defaultValue) {
        JsonNode value = node.get(key);
        if (value != null) {
            if (value.isNumber()) {
                return value.asLong();
            }
            if (value.isTextual()) {
                try {
                    return Long.parseLong(value.asText());
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    /**
     * 从 JsonNode 获取双精度浮点值
     * 支持数字类型和字符串类型
     */
    private double getDouble(JsonNode node, String key, double defaultValue) {
        JsonNode value = node.get(key);
        if (value != null) {
            if (value.isNumber()) {
                return value.asDouble();
            }
            if (value.isTextual()) {
                try {
                    return Double.parseDouble(value.asText());
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    /**
     * 从 JsonNode 获取字符串值
     */
    private String getString(JsonNode node, String key, String defaultValue) {
        JsonNode value = node.get(key);
        if (value != null && value.isTextual()) {
            return value.asText();
        }
        return defaultValue;
    }

    /**
     * 从 JsonNode 获取字符串列表
     * 支持数组类型和逗号分隔的字符串类型
     *
     * @return 字符串列表，如果为空则返回 null
     */
    private List<String> getStringList(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value != null) {
            List<String> list = new ArrayList<>();
            if (value.isArray()) {
                // 数组类型
                value.forEach(item -> {
                    if (item.isTextual()) {
                        list.add(item.asText().trim());
                    }
                });
            } else if (value.isTextual()) {
                // 逗号分隔的字符串类型
                String text = value.asText();
                if (text != null && !text.isEmpty()) {
                    for (String item : text.split(",")) {
                        String trimmed = item.trim();
                        if (!trimmed.isEmpty()) {
                            list.add(trimmed);
                        }
                    }
                }
            }
            return list.isEmpty() ? null : list;
        }
        return null;
    }
}
