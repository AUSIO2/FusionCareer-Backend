CREATE TABLE IF NOT EXISTS `fc_ai_session`
(
    `user_id`                    BIGINT       NOT NULL COMMENT '当前用户，同时为主键',
    `epoch`                      BIGINT       NOT NULL DEFAULT 1 COMMENT '清空对话时递增的会话世代',
    `summary`                    TEXT                  DEFAULT NULL COMMENT '旧对话摘要',
    `summary_through_message_id` BIGINT                DEFAULT NULL COMMENT '摘要覆盖到的最后消息ID',
    `active_run_id`              CHAR(36)              DEFAULT NULL COMMENT '当前运行UUID',
    `lease_until`                DATETIME(3)           DEFAULT NULL COMMENT '当前运行租约截止时间',
    `last_message_at`            DATETIME(3)           DEFAULT NULL COMMENT '最近消息时间',
    `created_at`                 DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`                 DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`user_id`),
    KEY `idx_ai_session_lease` (`lease_until`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '用户唯一AI对话Session';

CREATE TABLE IF NOT EXISTS `fc_ai_message`
(
    `id`                BIGINT       NOT NULL COMMENT '消息ID',
    `user_id`           BIGINT       NOT NULL COMMENT '消息所属用户',
    `epoch`             BIGINT       NOT NULL COMMENT '消息所属Session世代',
    `run_id`            CHAR(36)     NOT NULL COMMENT '本轮运行UUID',
    `request_id`        VARCHAR(64)  NOT NULL COMMENT '前端单轮幂等键',
    `role`              TINYINT      NOT NULL COMMENT '1-USER 2-ASSISTANT',
    `status`            TINYINT      NOT NULL COMMENT '0-PENDING 1-STREAMING 2-COMPLETED 3-FAILED 4-CANCELLED',
    `content`           MEDIUMTEXT   NOT NULL COMMENT '用户输入或最终助手文本',
    `attachment_ids`    JSON         NOT NULL COMMENT '用户显式附带的自有文件ID',
    `model`             VARCHAR(128)          DEFAULT NULL COMMENT '实际使用模型',
    `finish_reason`     VARCHAR(32)           DEFAULT NULL COMMENT '完成原因',
    `prompt_tokens`     INT                   DEFAULT NULL,
    `completion_tokens` INT                   DEFAULT NULL,
    `error_code`        VARCHAR(64)           DEFAULT NULL COMMENT '稳定错误码',
    `created_at`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_message_request` (`user_id`, `epoch`, `request_id`, `role`),
    UNIQUE KEY `uk_ai_message_run` (`user_id`, `epoch`, `run_id`, `role`),
    KEY `idx_ai_message_page` (`user_id`, `epoch`, `id`),
    KEY `idx_ai_message_state` (`status`, `updated_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'AI对话消息';
