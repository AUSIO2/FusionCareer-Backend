-- 审阅通过/未通过（若 V20260528 已执行但无此列，单独执行本脚本）
ALTER TABLE `fc_questionnaire_answer`
    ADD COLUMN `review_passed` TINYINT(1) NULL COMMENT '审阅是否通过：1-通过 0-未通过' AFTER `reviewed_by`;
