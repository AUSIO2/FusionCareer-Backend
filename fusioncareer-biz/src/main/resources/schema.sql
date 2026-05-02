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
    `role`        TINYINT      NOT NULL DEFAULT 0 COMMENT '角色：0-普通用户 1-管理员',
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
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '用户简历+作品集表';


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
    `recruit_type`      TINYINT       NOT NULL COMMENT '招聘类型：1-大实习 2-小实习 3-日常实习 4-应届生招聘 5-应届生摸排 6-其他',
    `headcount`         INT                    DEFAULT NULL COMMENT '需求人数',
    -- 工作要求
    `work_start_date`   DATE                   DEFAULT NULL COMMENT '工作开始时间',
    `work_end_date`     DATE                   DEFAULT NULL COMMENT '工作结束时间',
    `work_days_per_week` TINYINT               DEFAULT NULL COMMENT '每周工作天数，如：3（即一周3天及以上）',
    `work_duration_type` TINYINT               DEFAULT NULL COMMENT '工作时长类型：1-一周1-2天 2-一周3-4天 3-一周5天',
    `work_period_type`  TINYINT                DEFAULT NULL COMMENT '实习时长：1-3个月以内 2-3到6个月 3-6个月以上',
    `work_mode`         TINYINT                DEFAULT NULL COMMENT '工作形式：1-线上 2-线下 3-线上线下均可',
    `work_city`         VARCHAR(32)            DEFAULT NULL COMMENT '工作城市',
    `work_province`     VARCHAR(32)            DEFAULT NULL COMMENT '工作省份',
    `work_location`     VARCHAR(128)           DEFAULT NULL COMMENT '详细工作地点',
    `salary_min`        INT                    DEFAULT NULL COMMENT '薪资下限（元/月）',
    `salary_max`        INT                    DEFAULT NULL COMMENT '薪资上限（元/月）',
    `salary_display`    VARCHAR(64)            DEFAULT NULL COMMENT '薪资展示文本，如：面议、150/天',
    -- 岗位描述
    `job_desc`          TEXT                   DEFAULT NULL COMMENT '岗位职责描述',
    -- 招聘要求
    `req_edu_level`     TINYINT                DEFAULT NULL COMMENT '要求学历层次（同 fc_user_profile.edu_level）',
    `req_major`         VARCHAR(256)           DEFAULT NULL COMMENT '要求专业方向（可多个，逗号分隔）',
    `req_grad_year`     VARCHAR(16)            DEFAULT NULL COMMENT '要求毕业时间，如：2026届',
    `req_skills`        VARCHAR(512)           DEFAULT NULL COMMENT '技能经验要求',
    `req_other`         VARCHAR(512)           DEFAULT NULL COMMENT '其他招聘要求',
    -- 状态与审计
    `status`            TINYINT       NOT NULL DEFAULT 1 COMMENT '岗位状态：1-发布中 0-已下线 2-已截止',
    `created_by`        BIGINT                 DEFAULT NULL COMMENT '发布人user_id',
    `created_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_job_category`  (`job_category`),
    KEY `idx_recruit_type`  (`recruit_type`),
    KEY `idx_work_city`     (`work_city`),
    KEY `idx_work_mode`     (`work_mode`),
    KEY `idx_status`        (`status`),
    KEY `idx_created_at`    (`created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '岗位信息表';



