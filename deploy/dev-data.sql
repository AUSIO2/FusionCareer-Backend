-- FusionCareer dev-only seed. Idempotent and never loaded by production or tests.
SET NAMES utf8mb4;

SET @studentId = (SELECT id FROM fc_user WHERE student_id = 'dev-student' LIMIT 1);
INSERT INTO fc_user (id, username, student_id, role, status)
SELECT 700000000000000001, '本地学生', 'dev-student', 0, 1
WHERE @studentId IS NULL;
SET @studentId = COALESCE(@studentId, 700000000000000001);

SET @adminId = (SELECT id FROM fc_user WHERE student_id = 'dev-admin' LIMIT 1);
INSERT INTO fc_user (id, username, student_id, role, status)
SELECT 700000000000000002, '本地管理员', 'dev-admin', 1, 1
WHERE @adminId IS NULL;
SET @adminId = COALESCE(@adminId, 700000000000000002);

INSERT INTO fc_user_profile
    (user_id, real_name, gender, political_status, phone, email, hometown,
     grade, major, edu_level, supervisor, intention_order, intention_city,
     intention_dream, mindset)
VALUES
    (@studentId, '本地学生', 2, 2, '13800000000', 'student@example.test',
     '江苏南京', '2023级', '新闻传播学', 2, '测试导师',
     '新闻媒体,企业公司', JSON_ARRAY('上海', '北京'),
     '希望从事深度报道或融媒体内容策划', 2)
ON DUPLICATE KEY UPDATE
    real_name = VALUES(real_name), grade = VALUES(grade), major = VALUES(major),
    edu_level = VALUES(edu_level), intention_city = VALUES(intention_city);

INSERT INTO fc_resume
    (user_id, personal_intro, basic_info, education, internship, campus,
     awards, skills, portfolio, remark)
VALUES
    (@studentId, '新闻传播学研究生，关注数据新闻与融媒体叙事。',
     '本地测试学生 / 2023级', '复旦大学新闻学院',
     '校媒记者；某都市报新媒体实习生', '学院研究生会宣传部',
     '校级优秀学生干部', '采访写作、Premiere、Python 数据分析',
     'https://example.test/portfolio', '本地开发测试数据')
ON DUPLICATE KEY UPDATE
    personal_intro = VALUES(personal_intro), education = VALUES(education),
    internship = VALUES(internship), skills = VALUES(skills);

INSERT INTO fc_job_post
    (id, source_type, company_name, department, position_name,
     job_category, job_sub_category, recruit_type, headcount,
     work_start_date, work_end_date, work_days_per_week,
     work_duration_type, work_period_type, work_mode,
     work_province, work_city, work_location,
     salary_min, salary_max, salary_display,
     job_desc, req_edu_level, req_major, req_grad_year,
     req_skills, req_other, recommended, status, created_by, created_at)
VALUES
    (700000000000001001, 1, '新华社上海分社', '新媒体中心', '新媒体编辑记者',
     3, 21, 4, 3, CURDATE(), DATE_ADD(CURDATE(), INTERVAL 90 DAY), 5, 3, 3, 2,
     '上海', '上海', '静安区', 8000, 12000, '8k-12k',
     '负责重点新闻选题策划、采访和多媒体内容生产。', 2,
     '新闻传播学、中文', '2027届', '采访写作，短视频制作', '有校媒经历优先', 1, 1, @adminId, NOW()),
    (700000000000001002, 1, '腾讯新闻', '内容运营部', '内容运营实习生',
     4, 32, 1, 5, CURDATE(), DATE_ADD(CURDATE(), INTERVAL 75 DAY), 4, 2, 2, 3,
     '广东', '深圳', '南山区', 180, 220, '180-220元/天',
     '参与新闻客户端选题运营、数据复盘和用户研究。', 1,
     '新闻传播、市场营销', NULL, '内容策划，数据分析', '每周至少到岗4天', 1, 1, @adminId, DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (700000000000001003, 1, '澎湃新闻', '数据新闻组', '数据新闻记者',
     3, 22, 4, 2, CURDATE(), DATE_ADD(CURDATE(), INTERVAL 120 DAY), 5, 3, 3, 2,
     '上海', '上海', '徐汇区', 10000, 15000, '10k-15k',
     '从公开数据中挖掘选题，完成分析、采访和可视化叙事。', 2,
     '新闻传播、统计学、计算机', '2027届', 'Python/R，数据可视化', '有数据作品优先', 1, 1, @adminId, DATE_SUB(NOW(), INTERVAL 2 DAY)),
    (700000000000001004, 1, '字节跳动', '企业传播部', '公关传播实习生',
     4, 32, 3, 2, CURDATE(), DATE_ADD(CURDATE(), INTERVAL 60 DAY), 4, 2, 2, 3,
     '上海', '上海', '杨浦区', 200, 250, '200-250元/天',
     '支持媒体关系、品牌故事写作和传播项目执行。', 1,
     '新闻传播、公共关系', NULL, '文案写作，英语读写', '实习3个月以上', 0, 1, @adminId, DATE_SUB(NOW(), INTERVAL 3 DAY)),
    (700000000000001005, 1, '复旦大学', '宣传部', '高校新媒体行政',
     2, 13, 4, 1, CURDATE(), DATE_ADD(CURDATE(), INTERVAL 100 DAY), 5, 3, 3, 2,
     '上海', '上海', '杨浦区邯郸路', 9000, 13000, '9k-13k',
     '负责校级新媒体平台运营和重大活动宣传。', 2,
     '新闻传播、中文、公共管理', '2027届', '文字功底，组织协调', '中共党员优先', 0, 1, @adminId, DATE_SUB(NOW(), INTERVAL 4 DAY)),
    (700000000000001006, 1, '光明日报', '国际部', '国际传播编辑',
     3, 21, 4, 2, CURDATE(), DATE_ADD(CURDATE(), INTERVAL 110 DAY), 5, 3, 3, 2,
     '北京', '北京', '东城区', 9000, 14000, '9k-14k',
     '参与国际版和海外社交媒体内容策划、编辑与翻译。', 2,
     '新闻传播、英语', '2027届', '英文写作，国际新闻敏感度', '有海外经历优先', 0, 1, @adminId, DATE_SUB(NOW(), INTERVAL 5 DAY))
ON DUPLICATE KEY UPDATE
    company_name = VALUES(company_name), position_name = VALUES(position_name),
    work_end_date = VALUES(work_end_date), job_desc = VALUES(job_desc),
    recommended = VALUES(recommended), status = VALUES(status), updated_at = NOW();

INSERT INTO fc_job_post_question
    (id, job_post_id, sort_order, title, question_type, options, required, placeholder)
VALUES
    (700000000000101001, 700000000000001001, 1, '请简要介绍自己', 2, NULL, 1, '包括学习方向和兴趣'),
    (700000000000101002, 700000000000001001, 2, '你最关注的新闻领域是？', 3, JSON_ARRAY('时政', '社会', '财经', '科技'), 1, NULL),
    (700000000000101003, 700000000000001001, 3, '你具备哪些内容能力？', 4, JSON_ARRAY('采访', '写作', '摄影', '视频剪辑'), 1, NULL),
    (700000000000101004, 700000000000001001, 4, '上传个人简历', 5, NULL, 0, NULL),

    (700000000000102001, 700000000000001002, 1, '你可以每周实习几天？', 3, JSON_ARRAY('3天', '4天', '5天'), 1, NULL),
    (700000000000102002, 700000000000001002, 2, '介绍一次内容运营经历', 2, NULL, 1, '可以是校媒、社团或个人账号'),
    (700000000000102003, 700000000000001002, 3, '你使用过哪些数据工具？', 4, JSON_ARRAY('Excel', 'Python', 'SQL', '数据平台'), 0, NULL),

    (700000000000103001, 700000000000001003, 1, '最熟悉的数据分析工具', 3, JSON_ARRAY('Python', 'R', 'Excel', '其他'), 1, NULL),
    (700000000000103002, 700000000000001003, 2, '请描述一个数据新闻选题', 2, NULL, 1, '说明数据来源和故事角度'),
    (700000000000103003, 700000000000001003, 3, '作品集链接', 1, NULL, 0, 'https://'),

    (700000000000104001, 700000000000001004, 1, '为什么想做企业传播？', 2, NULL, 1, NULL),
    (700000000000104002, 700000000000001004, 2, '英语读写水平', 3, JSON_ARRAY('一般', '良好', '熟练', '母语水平'), 1, NULL),
    (700000000000104003, 700000000000001004, 3, '可实习的起止日期', 1, NULL, 1, '例：2026-09 至 2026-12'),

    (700000000000105001, 700000000000001005, 1, '请介绍你的校园组织经历', 2, NULL, 1, NULL),
    (700000000000105002, 700000000000001005, 2, '你擅长哪些工作？', 4, JSON_ARRAY('文案', '摄影', '排版', '活动组织'), 1, NULL),
    (700000000000105003, 700000000000001005, 3, '是否为中共党员？', 3, JSON_ARRAY('是', '否'), 1, NULL),

    (700000000000106001, 700000000000001006, 1, '请用英文做简短自我介绍', 2, NULL, 1, NULL),
    (700000000000106002, 700000000000001006, 2, '关注的国际议题', 4, JSON_ARRAY('国际政治', '全球经济', '科技与社会', '文化传播'), 1, NULL),
    (700000000000106003, 700000000000001006, 3, '第二外语及水平', 1, NULL, 0, '没有可留空')
ON DUPLICATE KEY UPDATE
    title = VALUES(title), question_type = VALUES(question_type),
    options = VALUES(options), required = VALUES(required),
    placeholder = VALUES(placeholder), updated_at = NOW();

-- Preserve later user edits: initial application samples are inserted once only.
INSERT IGNORE INTO fc_questionnaire_answer
    (id, job_post_id, user_id, answers, submission_status, created_at, updated_at)
VALUES
    (700000000000201001, 700000000000001002, @studentId,
     JSON_ARRAY(
       JSON_OBJECT('questionId', 700000000000102001, 'value', '4天'),
       JSON_OBJECT('questionId', 700000000000102002, 'value', '参与过学院公众号运营')), 0, NOW(), NOW());

INSERT IGNORE INTO fc_questionnaire_answer
    (id, job_post_id, user_id, answers, submission_status, reviewed_at,
     reviewed_by, review_passed, review_comments, created_at, updated_at)
VALUES
    (700000000000201002, 700000000000001003, @studentId,
     JSON_ARRAY(
       JSON_OBJECT('questionId', 700000000000103001, 'value', 'Python'),
       JSON_OBJECT('questionId', 700000000000103002, 'value', '城市公共交通数据可视化'),
       JSON_OBJECT('questionId', 700000000000103003, 'value', 'https://example.test/portfolio')), 2,
     NOW(), @adminId, 1, '经历与岗位匹配，通过。',
     DATE_SUB(NOW(), INTERVAL 2 DAY), NOW());

SELECT
    (SELECT COUNT(*) FROM fc_job_post WHERE id BETWEEN 700000000000001001 AND 700000000000001006) AS jobs,
    (SELECT COUNT(*) FROM fc_job_post_question WHERE id BETWEEN 700000000000101001 AND 700000000000106003) AS questions,
    (SELECT COUNT(*) FROM fc_questionnaire_answer WHERE user_id = @studentId) AS applications;
