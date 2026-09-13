ALTER TABLE `fc_job_post`
    ADD COLUMN `recycle_reason` VARCHAR(512) NULL COMMENT '移入回收站原因' AFTER `status`,
    ADD COLUMN `recycled_at` DATETIME NULL COMMENT '移入回收站时间' AFTER `recycle_reason`,
    ADD INDEX `idx_recycled_at` (`recycled_at`);
