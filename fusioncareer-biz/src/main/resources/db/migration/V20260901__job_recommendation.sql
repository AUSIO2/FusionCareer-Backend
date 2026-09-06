ALTER TABLE `fc_job_post`
    ADD COLUMN `recommended` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否推荐：1-推荐 0-普通' AFTER `req_other`,
    ADD KEY `idx_recommended` (`recommended`);
