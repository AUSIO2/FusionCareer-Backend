UPDATE `fc_user_profile` AS `profile`
JOIN `fc_user` AS `user` ON `user`.`id` = `profile`.`user_id`
SET `profile`.`real_name` = CASE
        WHEN `profile`.`real_name` = `user`.`student_id` THEN NULL
        ELSE `profile`.`real_name`
    END,
    `profile`.`email` = CASE
        WHEN LOWER(TRIM(`profile`.`email`)) = 'null' THEN NULL
        ELSE `profile`.`email`
    END,
    `profile`.`major` = CASE
        WHEN `profile`.`major` = '100286' THEN NULL
        ELSE `profile`.`major`
    END
WHERE `profile`.`real_name` = `user`.`student_id`
   OR LOWER(TRIM(`profile`.`email`)) = 'null'
   OR `profile`.`major` = '100286';
