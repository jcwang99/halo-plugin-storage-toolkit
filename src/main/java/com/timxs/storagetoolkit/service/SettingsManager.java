package com.timxs.storagetoolkit.service;

import com.timxs.storagetoolkit.config.ProcessingConfig;
import reactor.core.publisher.Mono;

/**
 * 配置管理器接口
 * 从 Halo 插件设置中读取配置
 */
public interface SettingsManager {

    /**
     * 获取当前配置
     *
     * @return 处理配置对象
     */
    Mono<ProcessingConfig> getConfig();

    /**
     * 获取文章附件存储策略
     * 从 SystemSetting.Post.attachmentPolicyName 读取
     *
     * @return 存储策略名称
     */
    Mono<String> getPostAttachmentPolicy();
}
