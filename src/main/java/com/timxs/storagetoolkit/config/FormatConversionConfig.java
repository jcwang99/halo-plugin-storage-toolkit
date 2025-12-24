package com.timxs.storagetoolkit.config;

import com.timxs.storagetoolkit.model.ImageFormat;
import lombok.Data;

/**
 * 格式转换配置
 * 控制图片格式转换的行为
 */
@Data
public class FormatConversionConfig {
    
    /**
     * 是否启用格式转换
     */
    private boolean enabled = false;
    
    /**
     * 目标格式（默认转换为 WebP）
     */
    private ImageFormat targetFormat = ImageFormat.WEBP;
    
    /**
     * 输出质量（0-100，对有损格式有效）
     */
    private int outputQuality = 85;
}
