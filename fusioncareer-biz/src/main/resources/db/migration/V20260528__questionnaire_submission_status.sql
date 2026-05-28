-- 问卷投递状态与审阅意见（存量库增量，若列已存在请跳过对应语句）
ALTER TABLE `fc_questionnaire_answer`
    ADD COLUMN `submission_status` TINYINT NOT NULL DEFAULT 1 COMMENT '0-草稿 1-已提交待审核 2-已审阅' AFTER `answers`;

ALTER TABLE `fc_questionnaire_answer`
    ADD COLUMN `reviewed_at` DATETIME NULL COMMENT '管理员审阅时间' AFTER `submission_status`;

ALTER TABLE `fc_questionnaire_answer`
    ADD COLUMN `reviewed_by` BIGINT NULL COMMENT '审阅管理员 user_id' AFTER `reviewed_at`;

ALTER TABLE `fc_questionnaire_answer`
    ADD COLUMN `review_passed` TINYINT(1) NULL COMMENT '审阅是否通过：1-通过 0-未通过' AFTER `reviewed_by`;

ALTER TABLE `fc_questionnaire_answer`
    ADD COLUMN `review_comments` TEXT NULL COMMENT '管理员审阅意见' AFTER `review_passed`;

ALTER TABLE `fc_questionnaire_answer`
    ADD KEY `idx_user_status` (`user_id`, `submission_status`);
