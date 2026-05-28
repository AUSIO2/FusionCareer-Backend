-- 简历文件元数据表（存量库若缺表则执行）
CREATE TABLE IF NOT EXISTS `fc_resume_file`
(
    `id`            BIGINT        NOT NULL COMMENT '文件ID（雪花算法）',
    `user_id`       BIGINT        NOT NULL COMMENT '所属用户ID，关联 fc_user.id',
    `original_name` VARCHAR(255)  NOT NULL COMMENT '用户上传时的原始文件名',
    `storage_path`  VARCHAR(512)  NOT NULL COMMENT '服务器相对存储路径（相对于 upload.base-dir）',
    `file_size`     BIGINT        NOT NULL COMMENT '文件大小（字节）',
    `mime_type`     VARCHAR(64)   NOT NULL COMMENT 'MIME类型：application/pdf / image/jpeg / image/png',
    `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '用户简历文件表（30MB/人配额）';
