package com.timxs.storagetoolkit.service;

import com.timxs.storagetoolkit.model.ImageFormat;

import java.awt.image.BufferedImage;

/**
 * 格式转换器接口
 * 定义图片格式转换的核心方法
 */
public interface FormatConverter {

    /**
     * 转换图片格式
     *
     * @param image        BufferedImage 对象
     * @param targetFormat 目标格式
     * @param quality      输出质量（0-100，对有损格式有效）
     * @return 转换后的字节数组
     */
    byte[] convert(BufferedImage image, ImageFormat targetFormat, int quality);

    /**
     * 检查是否支持指定格式
     *
     * @param format 图片格式
     * @return 是否支持
     */
    boolean supportsFormat(ImageFormat format);

    /**
     * 更新文件名扩展名
     *
     * @param originalFilename 原始文件名
     * @param targetFormat     目标格式
     * @return 更新后的文件名
     */
    String updateFilenameExtension(String originalFilename, ImageFormat targetFormat);

    /**
     * 获取格式对应的 MIME 类型
     *
     * @param format 图片格式
     * @return MIME 类型，如果是 ORIGINAL 则返回 null
     */
    String getMimeType(ImageFormat format);
}
