package com.timxs.storagetoolkit.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.util.List;

/**
 * 重复组 Extension 实体
 * 存储具有相同 MD5 哈希的附件组
 * metadata.name 格式为 dup-{md5hash}
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "storage-toolkit.timxs.com",
     version = "v1alpha1",
     kind = "DuplicateGroup",
     plural = "duplicategroups",
     singular = "duplicategroup")
public class DuplicateGroup extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private DuplicateGroupSpec spec;

    private DuplicateGroupStatus status;

    @Data
    public static class DuplicateGroupSpec {
        /**
         * 文件 MD5 哈希值
         */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String md5Hash;
    }

    @Data
    public static class DuplicateGroupStatus {
        /**
         * 文件大小（字节）
         */
        private long fileSize;

        /**
         * 组内文件数量
         */
        private int fileCount;

        /**
         * 可节省空间 = fileSize × (fileCount - 1)
         */
        private long savableSize;

        /**
         * 推荐保留的附件名称
         */
        private String recommendedKeep;

        /**
         * 组内附件名称列表
         */
        private List<String> attachmentNames;

        /**
         * 待删除标识（扫描时标记旧记录）
         */
        private Boolean pendingDelete;
    }
}
