package com.fusioncareer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 文件上传统一配置
 * <p>
 * 对应 application.yml 中的 {@code upload.*} 配置块。
 * 所有与文件上传相关的限制、路径、白名单均从此处读取，不允许在业务代码中出现硬编码。
 *
 * @author Xiong Heng
 */
@Data
@Component
@ConfigurationProperties(prefix = "upload")
public class UploadProperties {

    /**
     * 文件存储根目录（绝对路径）
     * 示例：/data/fusioncareer/uploads
     */
    private String baseDir;
    private long MB = 1024 * 1024;
    /**
     * 静态资源访问 URL 前缀
     * 需与 WebConfig 中 addResourceHandlers 映射的路径一致
     * 示例：http://localhost:8080/files
     */
    private String urlPrefix;

    /**
     * 每用户简历文件存储配额（字节）
     * 默认 30MB = 31457280
     */
    private long quotaPerUser = 30*MB;

    /**
     * 单个文件大小上限（字节）
     * 默认 20MB = 20971520
     */
    private long maxSingleFileBytes = 20*MB;

    /**
     * 允许上传的 MIME 类型白名单
     */
    private List<String> allowedMimeTypes = List.of(
            "application/pdf",
            "image/jpeg",
            "image/png"
    );

    /**
     * 允许上传的文件扩展名白名单（小写）
     */
    private List<String> allowedExtensions = List.of("pdf", "jpg", "jpeg", "png");

    // ── 派生方法（避免调用方重复 new HashSet） ─────────────────────────────────

    public Set<String> allowedMimeSet() {
        return Set.copyOf(allowedMimeTypes);
    }

    public Set<String> allowedExtSet() {
        return Set.copyOf(allowedExtensions);
    }
}
