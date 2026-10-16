ALTER TABLE `fc_job_post`
    ADD COLUMN `work_cities` JSON NULL COMMENT '工作城市列表' AFTER `work_city`,
    ADD COLUMN `req_edu_levels` JSON NULL COMMENT '要求学历层次列表' AFTER `req_edu_level`;

UPDATE `fc_job_post`
SET `work_cities` = JSON_ARRAY(`work_city`)
WHERE `work_city` IS NOT NULL AND `work_city` <> '' AND `work_cities` IS NULL;

UPDATE `fc_job_post`
SET `req_edu_levels` = JSON_ARRAY(
        CASE `req_edu_level`
            WHEN 1 THEN 'UNDERGRADUATE'
            WHEN 2 THEN 'ACADEMIC_MASTER'
            WHEN 3 THEN 'PROFESSIONAL_MASTER'
            WHEN 4 THEN 'DOCTORAL'
        END)
WHERE `req_edu_level` IS NOT NULL AND `req_edu_levels` IS NULL;
