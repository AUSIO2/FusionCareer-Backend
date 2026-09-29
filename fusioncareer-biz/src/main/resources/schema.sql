-- =====================================================
-- FusionCareer 就业资讯平台 - 数据库建表脚本
-- 数据库：fusioncareer
-- 字符集：utf8mb4
-- =====================================================

-- ----------------------------
-- 1. 用户基础账号表 fc_user
--    由 CAS/OAuth2 对接后写入，Sa-Token 以此为主体
-- ----------------------------
CREATE TABLE IF NOT EXISTS `fc_user`
(
    `id`          BIGINT       NOT NULL COMMENT '用户ID（雪花算法）',
    `username`    VARCHAR(64)  NOT NULL COMMENT '登录名',
    `student_id`  VARCHAR(32)           DEFAULT NULL COMMENT '学工号',
    `password`    VARCHAR(128)          DEFAULT NULL COMMENT '密码（CAS对接时为空）',
    `role`        TINYINT      NOT NULL DEFAULT 0 COMMENT '角色：0-普通用户 1-管理员 2-超级管理员',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-正常 0-禁用',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '用户账号表';


-- ----------------------------
-- 2. 用户资料表 fc_user_profile
--    对应「用户资料编辑 - 基础信息」
-- ----------------------------
CREATE TABLE IF NOT EXISTS `fc_user_profile`
(
    `user_id`         BIGINT       NOT NULL COMMENT '用户ID，关联 fc_user.id，同时为主键',
    -- 基础信息
    `real_name`       VARCHAR(32)           DEFAULT NULL COMMENT '姓名',
    `gender`          TINYINT               DEFAULT NULL COMMENT '性别：1-男 2-女 3-其他',
    `birth_date`      DATE                  DEFAULT NULL COMMENT '出生年月',
    `political_status` TINYINT              DEFAULT NULL COMMENT '政治面貌：1-群众 2-共青团员 3-中共党员 4-其他',
    `phone`           VARCHAR(20)           DEFAULT NULL COMMENT '联系电话',
    `email`           VARCHAR(64)           DEFAULT NULL COMMENT '联系邮箱',
    `wechat`          VARCHAR(64)           DEFAULT NULL COMMENT '微信号',
    `hometown`        VARCHAR(64)           DEFAULT NULL COMMENT '生源地（省市）',
    `grade`           VARCHAR(16)           DEFAULT NULL COMMENT '年级，如：2022级',
    `major`           VARCHAR(64)           DEFAULT NULL COMMENT '专业方向',
    `edu_level`       TINYINT               DEFAULT NULL COMMENT '学历层次：1-本科生 2-学术硕士 3-专业硕士 4-博士研究生',
    `supervisor`      VARCHAR(64)           DEFAULT NULL COMMENT '导师姓名',
    -- 个人意向
    `intention_order` VARCHAR(64)           DEFAULT NULL COMMENT '毕业去向总体意向排序，逗号分隔，如：学术教职,企业公司',
    `intention_city`  JSON                  DEFAULT NULL COMMENT '意向地区排序，JSON数组，如：["上海","北京"]',
    `intention_dream` VARCHAR(256)          DEFAULT NULL COMMENT '筹备方向/"梦中情岗"描述',
    `mindset`         TINYINT               DEFAULT NULL COMMENT '目前心态：1-比较有把握 2-谨慎乐观 3-信心不足 4-非常焦虑 5-佛系等待',
    `version`         BIGINT       NOT NULL DEFAULT 0 COMMENT '资源版本号，用于并发控制与变更历史',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '用户资料表（基础信息+意向）';


-- ----------------------------
-- 3. 用户简历表 fc_resume
--    对应「个人简历+作品集」，所有内容以字符串存储
-- ----------------------------
CREATE TABLE IF NOT EXISTS `fc_resume`
(
    `user_id`            BIGINT       NOT NULL COMMENT '用户ID，关联 fc_user.id，同时为主键',
    `personal_intro`     TEXT                  DEFAULT NULL COMMENT '个人简况（300字以内）',
    `basic_info`         TEXT                  DEFAULT NULL COMMENT '基础信息',
    `education`          TEXT                  DEFAULT NULL COMMENT '教育背景',
    `internship`         TEXT                  DEFAULT NULL COMMENT '实习经历',
    `campus`             TEXT                  DEFAULT NULL COMMENT '在校经历',
    `awards`             TEXT                  DEFAULT NULL COMMENT '荣誉奖励',
    `skills`             TEXT                  DEFAULT NULL COMMENT '掌握技能',
    `portfolio`          TEXT                  DEFAULT NULL COMMENT '作品集',
    `remark`             TEXT                  DEFAULT NULL COMMENT '备注',
    `version`            BIGINT       NOT NULL DEFAULT 0 COMMENT '资源版本号，用于并发控制与变更历史',
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '用户简历+作品集表';


-- ----------------------------
-- 3.1 用户长期记忆表 fc_user_memory
--    个人空间持有的轻量白名单偏好，不随 Agent Session 清除
-- ----------------------------
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


-- ----------------------------
-- 3.2 用户变更操作表 fc_user_change_action
--    相当于个人空间中的不可变 commit
-- ----------------------------
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


-- ----------------------------
-- 3.3 用户变更资源表 fc_user_change_item
--    保存一个 commit 内单个资源的加密字段快照
-- ----------------------------
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


-- ----------------------------
-- 3.4 AI Session 表 fc_ai_session
--    user_id 主键保证同一用户最多一个 Session
-- ----------------------------
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


-- ----------------------------
-- 3.5 AI 消息表 fc_ai_message
-- ----------------------------
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


-- ----------------------------
-- 4. 用户简历文件表 fc_resume_file
--    上传文件元数据（PDF/DOCX/JPG/PNG），配额由业务层校验
-- ----------------------------
CREATE TABLE IF NOT EXISTS `fc_resume_file`
(
    `id`            BIGINT        NOT NULL COMMENT '文件ID（雪花算法）',
    `user_id`       BIGINT        NOT NULL COMMENT '所属用户ID，关联 fc_user.id',
    `original_name` VARCHAR(255)  NOT NULL COMMENT '用户上传时的原始文件名',
    `storage_path`  VARCHAR(512)  NOT NULL COMMENT '服务器相对存储路径（相对于 upload.base-dir）',
    `file_size`     BIGINT        NOT NULL COMMENT '文件大小（字节）',
    `mime_type`     VARCHAR(128)  NOT NULL COMMENT 'MIME类型：PDF / DOCX / JPEG / PNG',
    `version`       BIGINT        NOT NULL DEFAULT 0 COMMENT '资源版本号，用于并发控制与变更历史',
    `deleted_at`    DATETIME               DEFAULT NULL COMMENT '软删除时间，NULL 表示活动文件',
    `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    `updated_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_user_deleted` (`user_id`, `deleted_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '用户简历文件表（30MB/人配额）';


-- ----------------------------
-- 9. 岗位信息主表 fc_job_post
--    对应「岗位详情页」
-- ----------------------------
CREATE TABLE IF NOT EXISTS `fc_job_post`
(
    `id`                BIGINT        NOT NULL COMMENT '岗位ID（雪花算法）',
    -- 发布来源
    `source_type`       TINYINT       NOT NULL DEFAULT 1 COMMENT '来源：1-平台发布 2-就业资讯源爬取',
    `source_url`        VARCHAR(512)           DEFAULT NULL COMMENT '信息源链接',
    -- 招聘单位
    `company_name`      VARCHAR(128)  NOT NULL COMMENT '单位名称',
    `department`        VARCHAR(128)           DEFAULT NULL COMMENT '工作部门',
    `position_name`     VARCHAR(128)  NOT NULL COMMENT '工作岗位名称',
    -- 岗位类型（一级分类）
    `job_category`      TINYINT       NOT NULL COMMENT '岗位大类：1-学术教职 2-党政机关 3-新闻媒体 4-企业公司',
    -- 岗位类型（二级分类）
    -- 学术教职：1-升学深造 2-考取教职 3-中学教师
    -- 党政机关：11-选调生 12-公务员 13-高校行政 14-医院 15-银行 16-其他事业单位
    -- 新闻媒体：21-党报央媒 22-地区主流媒体 23-其他媒体机构 24-自媒体
    -- 企业公司：31-国央企 32-民企 33-外企
    `job_sub_category`  TINYINT                DEFAULT NULL COMMENT '岗位二级分类',
    -- 招聘信息
    `recruit_type`      TINYINT       NOT NULL COMMENT '招聘类型：1-大实习 2-小实习 3-日常实习 4-应届生招聘 5-应届生摸排 6-其他 7-大/小实习均可',
    `headcount`         INT                    DEFAULT NULL COMMENT '需求人数',
    `headcount_display` VARCHAR(64)            DEFAULT NULL COMMENT '需求人数原始文本，如1-2人、若干',
    -- 工作要求
    `work_start_date`   DATE                   DEFAULT NULL COMMENT '工作开始时间',
    `work_end_date`     DATE                   DEFAULT NULL COMMENT '工作结束时间',
    `application_deadline` DATE                DEFAULT NULL COMMENT '岗位投递截止日期',
    `work_days_per_week` TINYINT               DEFAULT NULL COMMENT '每周工作天数，如：3（即一周3天及以上）',
    `work_time_requirement` TEXT               DEFAULT NULL COMMENT '实习时间及频次原始要求',
    `work_duration_type` TINYINT               DEFAULT NULL COMMENT '工作时长类型：1-一周1-2天 2-一周3-4天 3-一周5天',
    `work_period_type`  TINYINT                DEFAULT NULL COMMENT '实习时长：1-3个月以内 2-3到6个月 3-6个月以上',
    `work_mode`         TINYINT                DEFAULT NULL COMMENT '工作形式：1-线上 2-线下 3-线上线下均可',
    `work_city`         VARCHAR(32)            DEFAULT NULL COMMENT '工作城市',
    `work_cities`       JSON                   DEFAULT NULL COMMENT '工作城市列表',
    `work_province`     VARCHAR(32)            DEFAULT NULL COMMENT '工作省份',
    `work_location`     VARCHAR(128)           DEFAULT NULL COMMENT '详细工作地点',
    `salary_min`        INT                    DEFAULT NULL COMMENT '薪资下限（元/月）',
    `salary_max`        INT                    DEFAULT NULL COMMENT '薪资上限（元/月）',
    `salary_display`    VARCHAR(64)            DEFAULT NULL COMMENT '薪资展示文本，如：面议、150/天',
    `career_direction`  TEXT                   DEFAULT NULL COMMENT '职场发展方向',
    -- 岗位描述
    `job_desc`          TEXT                   DEFAULT NULL COMMENT '岗位职责描述',
    -- 招聘要求
    `req_edu_level`     TINYINT                DEFAULT NULL COMMENT '要求学历层次（同 fc_user_profile.edu_level）',
    `req_edu_levels`    JSON                   DEFAULT NULL COMMENT '要求学历层次列表',
    `req_major`         VARCHAR(256)           DEFAULT NULL COMMENT '要求专业方向（可多个，逗号分隔）',
    `req_grad_year`     VARCHAR(16)            DEFAULT NULL COMMENT '要求毕业时间，如：2026届',
    `req_skills`        VARCHAR(512)           DEFAULT NULL COMMENT '技能经验要求',
    `req_other`         TEXT                   DEFAULT NULL COMMENT '其他招聘要求',
    `internal_compensation` TEXT               DEFAULT NULL COMMENT '不对外薪资及补贴保障',
    `contact_name`      VARCHAR(128)           DEFAULT NULL COMMENT '不对外岗位联系人',
    `contact_info`      VARCHAR(256)           DEFAULT NULL COMMENT '不对外岗位联系方式',
    `internal_remark`   TEXT                   DEFAULT NULL COMMENT '不对外备注',
    -- 状态与审计
    `recommended`       TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '是否推荐：1-推荐 0-普通',
    `status`            TINYINT       NOT NULL DEFAULT 1 COMMENT '岗位状态：1-发布中 0-已下线 2-已截止 3-回收站',
    `recycle_reason`    VARCHAR(512)           DEFAULT NULL COMMENT '移入回收站原因',
    `recycled_at`       DATETIME               DEFAULT NULL COMMENT '移入回收站时间',
    `created_by`        BIGINT                 DEFAULT NULL COMMENT '发布人user_id',
    `created_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_job_category`  (`job_category`),
    KEY `idx_recruit_type`  (`recruit_type`),
    KEY `idx_work_city`     (`work_city`),
    KEY `idx_application_deadline` (`application_deadline`),
    KEY `idx_work_mode`     (`work_mode`),
    KEY `idx_recommended`   (`recommended`),
    KEY `idx_status`        (`status`),
    KEY `idx_recycled_at`   (`recycled_at`),
    KEY `idx_created_at`    (`created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '岗位信息表';


-- ----------------------------
-- 10. 岗位投递问卷题目表 fc_job_post_question
--     管理员为内部岗位自定义投递问卷
-- ----------------------------
CREATE TABLE IF NOT EXISTS `fc_job_post_question`
(
    `id`            BIGINT        NOT NULL COMMENT '问题ID（雪花算法）',
    `job_post_id`   BIGINT        NOT NULL COMMENT '所属岗位ID',
    `sort_order`    INT           NOT NULL DEFAULT 0 COMMENT '排序序号（升序）',
    `title`         VARCHAR(256)  NOT NULL COMMENT '问题标题',
    `question_type` TINYINT       NOT NULL COMMENT '题目类型：1-单行文本 2-多行文本 3-单选 4-多选 5-文件上传',
    `options`       JSON                   DEFAULT NULL COMMENT '选项列表（单选/多选时使用）',
    `required`      TINYINT       NOT NULL DEFAULT 1 COMMENT '是否必填：1-必填 0-选填',
    `placeholder`   VARCHAR(256)           DEFAULT NULL COMMENT '输入提示文字',
    `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_job_post_id` (`job_post_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '岗位投递问卷题目表';


-- ----------------------------
-- 11. 学生问卷作答表 fc_questionnaire_answer
--     学生对某岗位问卷的完整作答记录
-- ----------------------------
CREATE TABLE IF NOT EXISTS `fc_questionnaire_answer`
(
    `id`            BIGINT        NOT NULL COMMENT '作答记录ID（雪花算法）',
    `job_post_id`   BIGINT        NOT NULL COMMENT '所属岗位ID',
    `user_id`       BIGINT        NOT NULL COMMENT '投递学生用户ID',
    `answers`            JSON          NOT NULL COMMENT '作答内容JSON',
    `submission_status`  TINYINT       NOT NULL DEFAULT 1 COMMENT '0-草稿 1-已提交待审核 2-已审阅 3-已撤回',
    `reviewed_at`        DATETIME               DEFAULT NULL COMMENT '管理员审阅时间',
    `reviewed_by`        BIGINT                 DEFAULT NULL COMMENT '审阅管理员 user_id',
    `review_passed`      TINYINT(1)             DEFAULT NULL COMMENT '审阅是否通过：1-通过 0-未通过',
    `review_comments`    TEXT                   DEFAULT NULL COMMENT '管理员审阅意见',
    `version`            BIGINT        NOT NULL DEFAULT 0 COMMENT '资源版本号，用于并发控制与变更历史',
    `deleted_at`         DATETIME               DEFAULT NULL COMMENT '软删除时间，NULL 表示活动记录',
    `created_at`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_job_user` (`job_post_id`, `user_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_user_status` (`user_id`, `submission_status`),
    KEY `idx_user_deleted` (`user_id`, `deleted_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '学生问卷作答表';
