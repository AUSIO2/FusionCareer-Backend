ALTER TABLE `fc_job_post`
    ADD COLUMN `headcount_display` VARCHAR(64) DEFAULT NULL COMMENT '需求人数原始文本，如1-2人、若干' AFTER `headcount`,
    ADD COLUMN `work_time_requirement` TEXT DEFAULT NULL COMMENT '实习时间及频次原始要求' AFTER `work_days_per_week`,
    ADD COLUMN `career_direction` TEXT DEFAULT NULL COMMENT '职场发展方向' AFTER `salary_display`,
    MODIFY COLUMN `req_other` TEXT DEFAULT NULL COMMENT '其他招聘要求',
    ADD COLUMN `internal_compensation` TEXT DEFAULT NULL COMMENT '不对外薪资及补贴保障' AFTER `req_other`,
    ADD COLUMN `contact_name` VARCHAR(128) DEFAULT NULL COMMENT '不对外岗位联系人' AFTER `internal_compensation`,
    ADD COLUMN `contact_info` VARCHAR(256) DEFAULT NULL COMMENT '不对外岗位联系方式' AFTER `contact_name`,
    ADD COLUMN `internal_remark` TEXT DEFAULT NULL COMMENT '不对外备注' AFTER `contact_info`;
