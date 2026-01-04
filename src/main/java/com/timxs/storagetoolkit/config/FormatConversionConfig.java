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
    
    /**
     * 智能跳过：当转换后体积大于原始体积时，是否跳过格式转换
     * 默认启用，避免格式转换反而增大文件体积
     */
    private boolean skipIfLarger = true;
    
    /**
     * 跳过阈值（0-50%）：转换后体积增加超过此比例才触发跳过
     * 例如：设为 5 表示增加超过 5% 才跳过
     * 默认为 0，即任何体积增加都跳过
     */
    private int skipThreshold = 0;
}
