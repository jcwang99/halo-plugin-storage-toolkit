package com.timxs.storagetoolkit.config;

import com.timxs.storagetoolkit.model.WatermarkPosition;

/**
 * 图片水印配置 record
 *
 * @param imageUrl  水印图片URL
 * @param scale     缩放比例
 * @param position  位置
 * @param opacity   透明度 0-100
 * @param marginXPercent   X 边距百分比 0-50
 * @param marginYPercent   Y 边距百分比 0-50
 */
public record ImageWatermarkConfig(
    String imageUrl,
    double scale,
    WatermarkPosition position,
    int opacity,
    double marginXPercent,
    double marginYPercent
) {
    /**
     * 从 WatermarkConfig 创建 ImageWatermarkConfig
     */
    public static ImageWatermarkConfig from(WatermarkConfig config) {
        return new ImageWatermarkConfig(
            config.getImageUrl(),
            config.getImageScale(),
            config.getPosition(),
            config.getOpacity(),
            config.getMarginX(),
            config.getMarginY()
        );
    }
    
    /**
     * 根据图片尺寸计算实际X边距（像素）
     */
    public int calculateMarginX(int imageWidth) {
        return (int) (imageWidth * marginXPercent / 100.0);
    }
    
    /**
     * 根据图片尺寸计算实际Y边距（像素）
     */
    public int calculateMarginY(int imageHeight) {
        return (int) (imageHeight * marginYPercent / 100.0);
    }
}
