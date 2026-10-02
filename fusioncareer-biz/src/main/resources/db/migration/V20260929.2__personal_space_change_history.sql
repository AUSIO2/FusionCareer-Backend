CREATE TABLE IF NOT EXISTS `fc_user_change_action`
(
    `id`                BIGINT       NOT NULL COMMENT '操作ID',
    `user_id`           BIGINT       NOT NULL COMMENT '操作所属用户',
    `actor_type`        VARCHAR(16)  NOT NULL COMMENT 'USER、AGENT、SYSTEM',
    `origin`            VARCHAR(32)  NOT NULL COMMENT '固定操作来源',
    `epoch`             BIGINT                DEFAULT NULL COMMENT 'Agent Session 世代',
    `run_id`            CHAR(36)              DEFAULT NULL COMMENT 'Agent 运行UUID',
    `request_id`        VARCHAR(64)           DEFAULT NULL COMMENT '请求幂等标识',
    `tool_name`         VARCHAR(64)           DEFAULT NULL COMMENT '固定Tool名称',
    `action_type`       TINYINT      NOT NULL COMMENT '1-APPLY 2-REVERT',
    `reverts_action_id` BIGINT                DEFAULT NULL COMMENT '被回退的操作ID',
    `status`            TINYINT      NOT NULL COMMENT '1-PENDING 2-APPLIED 3-REJECTED 4-SUPERSEDED 5-CONFLICT 6-FAILED',
    `idempotency_key`   VARCHAR(96)           DEFAULT NULL COMMENT 'Agent Tool幂等键',
    `args_hash`         CHAR(64)              DEFAULT NULL COMMENT '规范化参数SHA-256',
    `schema_version`    SMALLINT     NOT NULL DEFAULT 1 COMMENT '快照结构版本',
    `reason`            VARCHAR(256)          DEFAULT NULL COMMENT '用户可理解的修改原因',
    `error_code`        VARCHAR(64)           DEFAULT NULL COMMENT '稳定失败原因',
    `confirmed_at`      DATETIME(3)           DEFAULT NULL,
    `applied_at`        DATETIME(3)           DEFAULT NULL,
    `created_at`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_change_action_idempotency` (`user_id`, `idempotency_key`),
    KEY `idx_change_action_user` (`user_id`, `status`, `created_at`),
    KEY `idx_change_action_revert` (`reverts_action_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '用户个人空间不可变变更操作';

CREATE TABLE IF NOT EXISTS `fc_user_change_item`
(
    `id`                BIGINT        NOT NULL COMMENT '变更项ID',
    `action_id`         BIGINT        NOT NULL COMMENT '所属操作ID',
    `item_order`        SMALLINT      NOT NULL COMMENT '稳定应用顺序',
    `resource_type`     VARCHAR(32)   NOT NULL COMMENT '资源类型',
    `resource_key`      VARCHAR(128)  NOT NULL COMMENT '资源领域键',
    `operation`         VARCHAR(24)   NOT NULL COMMENT 'PATCH、CREATE等领域操作',
    `changed_fields`    JSON          NOT NULL COMMENT '实际修改字段名，不含字段值',
    `expected_version`  BIGINT                 DEFAULT NULL COMMENT '应用前资源版本',
    `applied_version`   BIGINT                 DEFAULT NULL COMMENT '应用后资源版本',
    `before_ciphertext` MEDIUMBLOB    NOT NULL COMMENT 'AES-GCM加密的修改前快照',
    `after_ciphertext`  MEDIUMBLOB    NOT NULL COMMENT 'AES-GCM加密的修改后快照',
    `key_version`       SMALLINT      NOT NULL COMMENT '加密密钥版本',
    `created_at`        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_change_item_order` (`action_id`, `item_order`),
    UNIQUE KEY `uk_change_item_resource` (`action_id`, `resource_type`, `resource_key`),
    KEY `idx_change_item_resource` (`resource_type`, `resource_key`, `action_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '用户个人空间加密资源变更';
