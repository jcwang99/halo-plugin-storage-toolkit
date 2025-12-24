package com.timxs.storagetoolkit.config;

import com.timxs.storagetoolkit.model.WatermarkPosition;

/**
 * 文字水印配置 record
 *
 * @param text     水印文字
 * @param fontName 字体名称
 * @param fontSize 字体大小（像素）
 * @param color    颜色（十六进制）
 * @param position 位置
 * @param opacity  透明度 0-100
 * @param marginXPercent  X 边距百分比 0-50
 * @param marginYPercent  Y 边距百分比 0-50
 */
public record TextWatermarkConfig(
    String text,
    String fontName,
    int fontSize,
    String color,
    WatermarkPosition position,
    int opacity,
    double marginXPercent,
    double marginYPercent
) {
    /**
     * 从 WatermarkConfig 创建 TextWatermarkConfig
     */
    public static TextWatermarkConfig from(WatermarkConfig config) {
        return new TextWatermarkConfig(
            config.getText(),
            config.getFontName(),
            config.getFontSize(),
            config.getColor(),
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
