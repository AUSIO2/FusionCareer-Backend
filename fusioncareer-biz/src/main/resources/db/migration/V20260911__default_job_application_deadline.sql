UPDATE `fc_job_post`
SET `application_deadline` = DATE_ADD(DATE(`created_at`), INTERVAL 1 MONTH)
WHERE `application_deadline` IS NULL;
