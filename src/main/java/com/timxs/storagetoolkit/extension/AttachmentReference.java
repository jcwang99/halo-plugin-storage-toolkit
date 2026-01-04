package com.timxs.storagetoolkit.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;
import java.util.List;

/**
 * 附件引用关系 Extension 实体
 * 存储每个附件被哪些内容（文章/页面/评论）引用的信息
 * metadata.name 格式为 ref-{attachmentName}
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "storage-toolkit.timxs.com",
     version = "v1alpha1",
     kind = "AttachmentReference",
     plural = "attachmentreferences",
     singular = "attachmentreference")
public class AttachmentReference extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private AttachmentReferenceSpec spec;

    private AttachmentReferenceStatus status;

    /**
     * 附件引用规格
     * 只包含用户声明的关联关系
     */
    @Data
    public static class AttachmentReferenceSpec {
        /**
         * 关联的附件名称（Attachment 的 metadata.name）
         */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String attachmentName;
    }

    /**
     * 附件引用状态
     * 包含扫描计算出的引用信息
     */
    @Data
    public static class AttachmentReferenceStatus {
        /**
         * 引用次数
         */
        private int referenceCount;

        /**
         * 最后扫描时间
         */
        private Instant lastScannedAt;

        /**
         * 引用列表
         */
        private List<ReferenceSource> references;

        /**
         * 待删除标识（扫描时标记旧记录）
         */
        private Boolean pendingDelete;
    }

    /**
     * 引用源信息
     */
    @Data
    public static class ReferenceSource {
        /**
         * 引用源类型：Post、SinglePage、Comment、Reply、ConfigMap、Moment、Photo
         */
        private String sourceType;

        /**
         * 引用源名称（metadata.name）
         */
        private String sourceName;

        /**
         * 引用源标题（用于显示）
         */
        private String sourceTitle;

        /**
         * 引用源链接（用于跳转）
         */
        private String sourceUrl;

        /**
         * 是否已删除（在回收站中）
         */
        private Boolean deleted;

        /**
         * 引用类型：cover（封面图）、content（内容）、media（媒体）
         * 对于 ConfigMap 类型，存储 groupKey（如 basic、image）
         */
        private String referenceType;

        /**
         * Setting 名称（仅 ConfigMap 类型使用）
         * 用于异步查询 group label
         */
        private String settingName;
    }
}
