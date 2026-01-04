package com.timxs.storagetoolkit.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 总体统计数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TotalStats {
    
    /**
     * 附件总数
     */
    private long attachmentCount;
    
    /**
     * 存储总大小（字节）
     */
    private long totalSize;
    
    /**
     * 存储策略数量
     */
    private int policyCount;
    
    /**
     * 分组数量
     */
    private int groupCount;
}
