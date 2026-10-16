ALTER TABLE `fc_user_profile`
    ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0 COMMENT '资源版本号，用于并发控制与变更历史' AFTER `mindset`;

ALTER TABLE `fc_resume`
    ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0 COMMENT '资源版本号，用于并发控制与变更历史' AFTER `remark`;

ALTER TABLE `fc_resume_file`
    ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0 COMMENT '资源版本号，用于并发控制与变更历史' AFTER `mime_type`,
    ADD COLUMN `deleted_at` DATETIME NULL COMMENT '软删除时间，NULL 表示活动文件' AFTER `version`,
    ADD COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间' AFTER `created_at`,
    ADD KEY `idx_user_deleted` (`user_id`, `deleted_at`);

ALTER TABLE `fc_questionnaire_answer`
    ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0 COMMENT '资源版本号，用于并发控制与变更历史' AFTER `review_comments`,
    ADD COLUMN `deleted_at` DATETIME NULL COMMENT '软删除时间，NULL 表示活动记录' AFTER `version`,
    ADD KEY `idx_user_deleted` (`user_id`, `deleted_at`);
