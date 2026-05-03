package com.fusioncareer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户上传的简历文件元数据
 *
 * @author Xiong Heng
 */
@Data
@TableName("fc_resume_file")
public class ResumeFileEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 文件ID（雪花算法） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属用户ID */
    private Long userId;

    /** 用户上传时的原始文件名 */
    private String originalName;

    /** 服务器上存储的相对路径（相对于 upload.base-dir） */
    private String storagePath;

    /** 文件大小（字节） */
    private Long fileSize;

    /** MIME 类型：application/pdf / image/jpeg / image/png */
    private String mimeType;

    private LocalDateTime createdAt;
}
