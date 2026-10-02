CREATE TABLE IF NOT EXISTS `fc_user_memory`
(
    `user_id`     BIGINT      NOT NULL COMMENT '用户ID，关联 fc_user.id，同时为主键',
    `memory_json` JSON        NOT NULL COMMENT '白名单长期记忆，应用层限制 4KB',
    `version`     BIGINT      NOT NULL DEFAULT 0 COMMENT '资源版本号，用于并发控制与变更历史',
    `created_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '用户轻量长期记忆';
