package com.timxs.storagetoolkit.model;

/**
 * 处理状态枚举
 */
public enum ProcessingStatus {
    /**
     * 处理成功
     */
    SUCCESS,
    
    /**
     * 部分成功（某些步骤失败）
     */
    PARTIAL,
    
    /**
     * 处理失败
     */
    FAILED,
    
    /**
     * 跳过处理（不符合条件）
     */
    SKIPPED
}
