package com.fusioncareer.dto.res;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 简历文件响应 DTO
 *
 * @author Xiong Heng
 */
@Data
public class ResumeFileResponse {

    /** 文件ID */
    private Long id;

    /** 原始文件名 */
    private String originalName;

    /** 文件访问 URL（由后端拼接） */
    private String url;

    /** 文件大小（字节） */
    private Long fileSize;

    /** MIME 类型 */
    private String mimeType;

    /** 上传时间 */
    private LocalDateTime createdAt;
}
