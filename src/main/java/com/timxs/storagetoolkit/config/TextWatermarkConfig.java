package com.timxs.storagetoolkit.config;

import com.timxs.storagetoolkit.model.FontSizeMode;
import com.timxs.storagetoolkit.model.WatermarkPosition;

/**
 * 文字水印配置 record
 *
 * @param text     水印文字
 * @param fontName 字体名称
 * @param fontSizeMode 字体大小模式（FIXED 或 ADAPTIVE）
 * @param fontScale 字体缩放比例（自适应模式，1-10%）
 * @param fontSize 字体大小（像素，固定模式）
 * @param color    颜色（十六进制）
 * @param position 位置
 * @param opacity  透明度 0-100
 * @param marginXPercent  X 边距百分比 0-50
 * @param marginYPercent  Y 边距百分比 0-50
 */
public record TextWatermarkConfig(
    String text,
    String fontName,
    FontSizeMode fontSizeMode,
    int fontScale,
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
            config.getFontSizeMode(),
            config.getFontScale(),
            config.getFontSize(),
            config.getColor(),
            config.getPosition(),
            config.getOpacity(),
            config.getMarginX(),
            config.getMarginY()
        );
    }
    
    /**
     * 根据图片尺寸计算最终字体大小
     * 
     * @param imageWidth 图片宽度
     * @param imageHeight 图片高度
     * @return 计算后的字体大小（像素），最小 12px
     */
    public int calculateFontSize(int imageWidth, int imageHeight) {
        if (fontSizeMode == null || fontSizeMode == FontSizeMode.FIXED) {
            return fontSize;
        }
        
        // 自适应模式：基于宽度百分比计算
        int calculated = (int) (imageWidth * fontScale / 100.0);
        
        // 最小 12px（优先保证可读性）
        calculated = Math.max(12, calculated);
        
        // 最大：短边的 12%（防止水印过大）
        int shorterSide = Math.min(imageWidth, imageHeight);
        int maxSize = (int) (shorterSide * 0.12);
        calculated = Math.min(calculated, maxSize);
        
        return calculated;
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
