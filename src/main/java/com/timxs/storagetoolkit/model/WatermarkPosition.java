package com.timxs.storagetoolkit.model;

/**
 * 水印位置枚举
 * 定义九宫格位置，用于指定水印在图片上的位置
 */
public enum WatermarkPosition {
    
    /**
     * 左上角
     */
    TOP_LEFT,
    
    /**
     * 顶部居中
     */
    TOP_CENTER,
    
    /**
     * 右上角
     */
    TOP_RIGHT,
    
    /**
     * 左侧居中
     */
    MIDDLE_LEFT,
    
    /**
     * 正中央
     */
    MIDDLE_CENTER,
    
    /**
     * 右侧居中
     */
    MIDDLE_RIGHT,
    
    /**
     * 左下角
     */
    BOTTOM_LEFT,
    
    /**
     * 底部居中
     */
    BOTTOM_CENTER,
    
    /**
     * 右下角
     */
    BOTTOM_RIGHT;

    /**
     * 计算水印在图片上的 X 坐标
     *
     * @param imageWidth     图片宽度
     * @param watermarkWidth 水印宽度
     * @param marginX        X 方向边距
     * @return X 坐标
     */
    public int calculateX(int imageWidth, int watermarkWidth, int marginX) {
        return switch (this) {
            case TOP_LEFT, MIDDLE_LEFT, BOTTOM_LEFT -> marginX;
            case TOP_CENTER, MIDDLE_CENTER, BOTTOM_CENTER -> (imageWidth - watermarkWidth) / 2;
            case TOP_RIGHT, MIDDLE_RIGHT, BOTTOM_RIGHT -> imageWidth - watermarkWidth - marginX;
        };
    }

    /**
     * 计算水印在图片上的 Y 坐标
     *
     * @param imageHeight     图片高度
     * @param watermarkHeight 水印高度
     * @param marginY         Y 方向边距
     * @return Y 坐标
     */
    public int calculateY(int imageHeight, int watermarkHeight, int marginY) {
        return switch (this) {
            case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> marginY;
            case MIDDLE_LEFT, MIDDLE_CENTER, MIDDLE_RIGHT -> (imageHeight - watermarkHeight) / 2;
            case BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> imageHeight - watermarkHeight - marginY;
        };
    }
}
