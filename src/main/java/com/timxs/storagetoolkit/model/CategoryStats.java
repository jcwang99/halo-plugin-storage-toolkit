package com.timxs.storagetoolkit.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分类统计项
 * 用于按类型/策略/分组统计的单项数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryStats {
    
    /**
     * 唯一标识，用于前端 v-for key
     */
    private String key;
    
    /**
     * 显示名称
     */
    private String name;
    
    /**
     * 图标标识符（如 "image", "video", "folder"）
     */
    private String icon;
    
    /**
     * 文件数量
     */
    private long count;
    
    /**
     * 存储大小（字节）
     */
    private long size;
    
    /**
     * 百分比（0-100，保留两位小数）
     */
    private double percent;
}
