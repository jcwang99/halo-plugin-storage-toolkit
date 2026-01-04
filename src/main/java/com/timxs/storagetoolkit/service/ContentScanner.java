package com.timxs.storagetoolkit.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容扫描器
 * 从 HTML/Markdown/JSON 内容中提取 URL
 */
@Slf4j
@Component
public class ContentScanner {

    /**
     * HTML img 标签 src 属性
     */
    private static final Pattern HTML_IMG_PATTERN = 
        Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /**
     * HTML a 标签 href 属性
     */
    private static final Pattern HTML_A_PATTERN = 
        Pattern.compile("<a[^>]+href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /**
     * HTML video/audio source 标签 src 属性
     */
    private static final Pattern HTML_MEDIA_PATTERN = 
        Pattern.compile("<(?:source|video|audio)[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /**
     * Markdown 图片语法
     */
    private static final Pattern MD_IMAGE_PATTERN = 
        Pattern.compile("!\\[[^\\]]*\\]\\(([^)\\s]+)(?:\\s+[\"'][^\"']*[\"'])?\\)");

    /**
     * Markdown 链接语法
     */
    private static final Pattern MD_LINK_PATTERN = 
        Pattern.compile("(?<!!)\\[[^\\]]*\\]\\(([^)\\s]+)(?:\\s+[\"'][^\"']*[\"'])?\\)");

    /**
     * 通用相对路径匹配（适用于 JSON、纯文本等）
     * 匹配 /upload/ 开头的路径，但排除完整 URL 中的路径部分
     * 负向后行断言：前面不能是字母、数字、点、横线（域名字符）
     */
    private static final Pattern UPLOAD_PATH_PATTERN = 
        Pattern.compile("(?<![a-zA-Z0-9.\\-])(/upload/[^\"'\\s<>\\]\\)]+)");

    /**
     * HTTP/HTTPS URL 匹配（适用于 JSON、纯文本等）
     */
    private static final Pattern HTTP_URL_PATTERN = 
        Pattern.compile("([\"']?)(https?://[^\"'\\s<>\\]\\)]+)\\1");

    /**
     * 提取结果，区分完整 URL 和相对路径
     */
    public record ExtractResult(Set<String> fullUrls, Set<String> relativePaths) {
        public ExtractResult() {
            this(new HashSet<>(), new HashSet<>());
        }
    }

    /**
     * 从内容中提取所有 URL，区分完整 URL 和相对路径
     */
    public ExtractResult extractUrlsWithType(String content) {
        ExtractResult result = new ExtractResult();
        
        if (!StringUtils.hasText(content)) {
            return result;
        }

        // HTML 标签
        extractByPatternWithType(content, HTML_IMG_PATTERN, result, 1);
        extractByPatternWithType(content, HTML_A_PATTERN, result, 1);
        extractByPatternWithType(content, HTML_MEDIA_PATTERN, result, 1);
        
        // Markdown 语法
        extractByPatternWithType(content, MD_IMAGE_PATTERN, result, 1);
        extractByPatternWithType(content, MD_LINK_PATTERN, result, 1);
        
        // JSON、纯文本中的 URL
        extractByPatternWithType(content, HTTP_URL_PATTERN, result, 2);
        // 相对路径使用 group 1（正则已调整）
        extractByPatternWithType(content, UPLOAD_PATH_PATTERN, result, 1);

        return result;
    }

    /**
     * 从内容中提取所有 URL（兼容旧接口，返回合并结果）
     */
    public Set<String> extractUrls(String content) {
        ExtractResult result = extractUrlsWithType(content);
        Set<String> urls = new HashSet<>();
        urls.addAll(result.fullUrls());
        urls.addAll(result.relativePaths());
        return urls;
    }

    private void extractByPatternWithType(String content, Pattern pattern, ExtractResult result, int group) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String url = matcher.group(group);
            if (StringUtils.hasText(url)) {
                String decodedUrl = decodeUrl(url.trim());
                if (isValidUrl(decodedUrl)) {
                    if (isFullUrl(decodedUrl)) {
                        result.fullUrls().add(decodedUrl);
                    } else if (decodedUrl.startsWith("/")) {
                        result.relativePaths().add(decodedUrl);
                    }
                }
            }
        }
    }

    /**
     * 判断是否为完整 URL
     */
    public boolean isFullUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /**
     * 从完整 URL 中提取路径部分
     */
    public String extractPath(String fullUrl) {
        if (!isFullUrl(fullUrl)) {
            return fullUrl;
        }
        try {
            return URI.create(fullUrl).getPath();
        } catch (Exception e) {
            // 解析失败，尝试简单截取
            int idx = fullUrl.indexOf("://");
            if (idx > 0) {
                int pathStart = fullUrl.indexOf('/', idx + 3);
                if (pathStart > 0) {
                    return fullUrl.substring(pathStart);
                }
            }
            return fullUrl;
        }
    }

    private boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        if (url.startsWith("data:")) return false;
        if (url.startsWith("javascript:")) return false;
        if (url.startsWith("mailto:")) return false;
        if (url.startsWith("#")) return false;
        return true;
    }

    private String decodeUrl(String url) {
        try {
            return URLDecoder.decode(url, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
}
