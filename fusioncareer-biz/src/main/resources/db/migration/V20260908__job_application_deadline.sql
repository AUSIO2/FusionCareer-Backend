ALTER TABLE `fc_job_post`
    ADD COLUMN `application_deadline` DATE NULL COMMENT '岗位投递截止日期' AFTER `work_end_date`,
    ADD INDEX `idx_application_deadline` (`application_deadline`);
