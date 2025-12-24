package com.timxs.storagetoolkit.service;

import com.timxs.storagetoolkit.config.ImageWatermarkConfig;
import com.timxs.storagetoolkit.config.TextWatermarkConfig;

import java.awt.image.BufferedImage;

/**
 * 水印服务接口
 * 定义添加文字水印和图片水印的方法
 */
public interface WatermarkService {

    /**
     * 添加文字水印
     *
     * @param image  原始图片
     * @param config 文字水印配置
     * @return 添加水印后的图片
     */
    BufferedImage addTextWatermark(BufferedImage image, TextWatermarkConfig config);

    /**
     * 添加图片水印
     *
     * @param image          原始图片
     * @param config         图片水印配置
     * @param watermarkImage 水印图片
     * @return 添加水印后的图片
     */
    BufferedImage addImageWatermark(BufferedImage image, ImageWatermarkConfig config, BufferedImage watermarkImage);
}
