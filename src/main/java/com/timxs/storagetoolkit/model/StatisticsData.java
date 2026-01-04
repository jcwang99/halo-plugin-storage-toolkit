package com.timxs.storagetoolkit.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 存储统计数据响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatisticsData {
    
    /**
     * 总体统计
     */
    private TotalStats total;
    
    /**
     * 按文件类型统计
     */
    private List<CategoryStats> byType;
    
    /**
     * 按存储策略统计
     */
    private List<CategoryStats> byPolicy;
    
    /**
     * 按分组统计
     */
    private List<CategoryStats> byGroup;
}
