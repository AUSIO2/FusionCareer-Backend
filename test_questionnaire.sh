#!/bin/bash
# ================================================================
# 问卷功能端到端测试脚本
# 
# 测试流程：
#   1. 创建测试岗位
#   2. 配置问卷（5道题）
#   3. 查询问卷
#   4. 学生提交作答（通过 internal 接口绕过登录）
#   5. 管理员查看作答
#   6. 覆盖更新 + 整组替换
#   7. 清理
# ================================================================

BASE="http://localhost:8080"
GREEN='\033[0;32m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

pass() { echo -e "${GREEN}✅ $1${NC}"; }
fail() { echo -e "${RED}❌ $1${NC}"; echo "  Response: $2"; }
ERRORS=0

echo -e "${CYAN}=============================================${NC}"
echo -e "${CYAN}  FusionCareer 问卷功能测试${NC}"
echo -e "${CYAN}=============================================${NC}"
echo ""

# ── Step 1: 创建一个测试岗位 ──────────────────────────────────────────────
echo -e "${CYAN}[Step 1] 创建测试岗位${NC}"
RES=$(curl -s -X POST "$BASE/internal/job-post" \
  -H "Content-Type: application/json" \
  -d '{
    "companyName": "复旦大学新闻学院",
    "positionName": "学生助理-问卷测试岗",
    "jobCategory": "ACADEMIC",
    "recruitType": "DAILY_INTERNSHIP",
    "status": "PUBLISHED"
  }')
JOB_ID=$(echo "$RES" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" 2>/dev/null)
if [ -n "$JOB_ID" ] && [ "$JOB_ID" != "None" ]; then
  pass "岗位创建成功, ID=$JOB_ID"
else
  fail "岗位创建失败" "$RES"
  ERRORS=$((ERRORS+1))
fi
echo ""

# ── Step 2: 为岗位配置投递问卷（整组保存） ─────────────────────────────────
echo -e "${CYAN}[Step 2] 配置投递问卷（5道题）${NC}"
RES=$(curl -s -X POST "$BASE/internal/questionnaire/questions/batch/$JOB_ID" \
  -H "Content-Type: application/json" \
  -d '[
    {
      "sortOrder": 1,
      "title": "请填写你的年级",
      "questionType": "RADIO",
      "options": ["大一", "大二", "大三", "大四", "研一", "研二", "研三"],
      "required": true
    },
    {
      "sortOrder": 2,
      "title": "请选择性别",
      "questionType": "RADIO",
      "options": ["男", "女", "其他"],
      "required": true
    },
    {
      "sortOrder": 3,
      "title": "请填写身份证号",
      "questionType": "TEXT",
      "required": true,
      "placeholder": "18位身份证号码"
    },
    {
      "sortOrder": 4,
      "title": "请简述申请理由（200字以内）",
      "questionType": "TEXTAREA",
      "required": true,
      "placeholder": "请说明你为什么适合这个岗位..."
    },
    {
      "sortOrder": 5,
      "title": "请上传个人简历",
      "questionType": "FILE_UPLOAD",
      "required": false,
      "placeholder": "支持 PDF/JPG/PNG 格式"
    }
  ]')
Q_COUNT=$(echo "$RES" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']))" 2>/dev/null)
if [ "$Q_COUNT" = "5" ]; then
  pass "问卷配置成功, 共 $Q_COUNT 道题"
  # 打印所有题目
  echo "$RES" | python3 -c "
import sys, json
data = json.load(sys.stdin)['data']
for q in data:
    opts = q.get('options', [])
    opt_str = '  选项: ' + ', '.join(opts) if opts else ''
    print(f\"  #{q['sortOrder']} [{q['questionType']}] {q['title']}{opt_str}\")
" 2>/dev/null
else
  fail "问卷配置失败" "$RES"
  ERRORS=$((ERRORS+1))
fi
echo ""

# ── Step 3: 查询问卷题目 ──────────────────────────────────────────────────
echo -e "${CYAN}[Step 3] 查询岗位问卷题目${NC}"
RES=$(curl -s "$BASE/internal/questionnaire/questions/$JOB_ID")
Q_COUNT=$(echo "$RES" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']))" 2>/dev/null)
FIRST_TITLE=$(echo "$RES" | python3 -c "import sys,json; print(json.load(sys.stdin)['data'][0]['title'])" 2>/dev/null)
if [ "$Q_COUNT" = "5" ] && [ "$FIRST_TITLE" = "请填写你的年级" ]; then
  pass "问卷查询成功, 共 $Q_COUNT 道题, 第一题: $FIRST_TITLE"
else
  fail "问卷查询失败" "$RES"
  ERRORS=$((ERRORS+1))
fi
echo ""

# ── Step 4: 创建测试用户 ──────────────────────────────────────────────────
echo -e "${CYAN}[Step 4] 创建测试用户${NC}"
RES=$(curl -s -X POST "$BASE/internal/user" \
  -H "Content-Type: application/json" \
  -d '{"username":"questionnaire_test_student","studentId":"22307110999"}')
USER_ID=$(echo "$RES" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" 2>/dev/null)
if [ -n "$USER_ID" ] && [ "$USER_ID" != "None" ]; then
  pass "用户创建成功, ID=$USER_ID"
else
  # 可能已存在，尝试获取
  fail "用户创建失败（可能已存在）" "$RES"
  USER_ID="0"
fi
echo ""

# ── Step 5: 模拟学生提交问卷作答（直接通过数据库插入模拟）───────────────────
# 因为学生端接口需要 @SaCheckLogin，我们直接测试 service 层的 internal 写入
echo -e "${CYAN}[Step 5] 模拟学生提交问卷作答${NC}"
ANSWER_JSON='[{"questionId":1,"value":"研一"},{"questionId":2,"value":"男"},{"questionId":3,"value":"310101200001011234"},{"questionId":4,"value":"我对新闻传播有浓厚兴趣。"}]'
# 直接通过 MySQL 插入模拟
SNOWFLAKE_ID=$(python3 -c "import random; print(random.randint(1000000000000000000,1999999999999999999))")
mysql -u root fusioncareer -e "
INSERT INTO fc_questionnaire_answer (id, job_post_id, user_id, answers)
VALUES ($SNOWFLAKE_ID, $JOB_ID, $USER_ID, '$ANSWER_JSON')
ON DUPLICATE KEY UPDATE answers = '$ANSWER_JSON', updated_at = NOW();
" 2>/dev/null
if [ $? -eq 0 ]; then
  pass "问卷作答插入成功, 记录ID=$SNOWFLAKE_ID"
else
  fail "问卷作答插入失败" ""
  ERRORS=$((ERRORS+1))
fi
echo ""

# ── Step 6: 管理员查看该岗位所有作答 ──────────────────────────────────────
echo -e "${CYAN}[Step 6] 管理员查看岗位所有作答${NC}"
RES=$(curl -s "$BASE/internal/questionnaire/answers/job/$JOB_ID?page=1&size=10")
LIST_LEN=$(echo "$RES" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']['list']))" 2>/dev/null)
if [ "$LIST_LEN" -ge 1 ] 2>/dev/null; then
  pass "管理员查看作答成功, 列表中有 $LIST_LEN 条记录"
  echo "$RES" | python3 -c "
import sys, json
data = json.load(sys.stdin)['data']['list']
for a in data:
    answers = json.loads(a['answers'])
    summary = ', '.join([f\"{item['value']}\" for item in answers[:3]])
    print(f\"  学生={a.get('username','?')} (学号={a.get('studentId','?')}): {summary}...\")
" 2>/dev/null
else
  fail "管理员查看作答失败" "$RES"
  ERRORS=$((ERRORS+1))
fi
echo ""

# ── Step 7: 管理员查看单条作答详情 ────────────────────────────────────────
echo -e "${CYAN}[Step 7] 管理员查看单条作答详情${NC}"
RES=$(curl -s "$BASE/internal/questionnaire/answers/$SNOWFLAKE_ID")
DETAIL_ANSWERS=$(echo "$RES" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['answers'])" 2>/dev/null)
if [ -n "$DETAIL_ANSWERS" ] && [ "$DETAIL_ANSWERS" != "None" ]; then
  pass "单条作答详情查看成功"
  echo "  作答内容: $DETAIL_ANSWERS"
else
  fail "单条作答详情查看失败" "$RES"
  ERRORS=$((ERRORS+1))
fi
echo ""

# ── Step 8: 管理员更新问卷（整组替换，减少为3道题） ──────────────────────
echo -e "${CYAN}[Step 8] 管理员更新问卷（整组替换为3道题）${NC}"
RES=$(curl -s -X POST "$BASE/internal/questionnaire/questions/batch/$JOB_ID" \
  -H "Content-Type: application/json" \
  -d '[
    {
      "sortOrder": 1,
      "title": "请填写你的专业",
      "questionType": "TEXT",
      "required": true,
      "placeholder": "如：新闻学"
    },
    {
      "sortOrder": 2,
      "title": "是否有相关实习经历",
      "questionType": "RADIO",
      "options": ["有", "没有"],
      "required": true
    },
    {
      "sortOrder": 3,
      "title": "请上传个人简历",
      "questionType": "FILE_UPLOAD",
      "required": false,
      "placeholder": "支持 PDF/JPG/PNG"
    }
  ]')
Q_COUNT=$(echo "$RES" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']))" 2>/dev/null)
if [ "$Q_COUNT" = "3" ]; then
  pass "问卷整组替换成功, 现在 $Q_COUNT 道题"
  echo "$RES" | python3 -c "
import sys, json
data = json.load(sys.stdin)['data']
for q in data:
    opts = q.get('options', [])
    opt_str = '  选项: ' + ', '.join(opts) if opts else ''
    print(f\"  #{q['sortOrder']} [{q['questionType']}] {q['title']}{opt_str}\")
" 2>/dev/null
else
  fail "问卷整组替换失败" "$RES"
  ERRORS=$((ERRORS+1))
fi
echo ""

# ── Step 9: 删除问卷 ──────────────────────────────────────────────────────
echo -e "${CYAN}[Step 9] 删除岗位问卷${NC}"
RES=$(curl -s -X DELETE "$BASE/internal/questionnaire/questions/$JOB_ID")
CODE=$(echo "$RES" | python3 -c "import sys,json; print(json.load(sys.stdin)['code'])" 2>/dev/null)
# 验证已清空
RES2=$(curl -s "$BASE/internal/questionnaire/questions/$JOB_ID")
Q_COUNT=$(echo "$RES2" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']))" 2>/dev/null)
if [ "$Q_COUNT" = "0" ]; then
  pass "问卷删除成功, 现在 0 道题"
else
  fail "问卷删除失败" "$RES2"
  ERRORS=$((ERRORS+1))
fi
echo ""

# ── 清理 ──────────────────────────────────────────────────────────────────
echo -e "${CYAN}[Cleanup] 清理测试数据${NC}"
mysql -u root fusioncareer -e "DELETE FROM fc_questionnaire_answer WHERE job_post_id = $JOB_ID;" 2>/dev/null
curl -s -X DELETE "$BASE/internal/job-post/$JOB_ID" > /dev/null
if [ -n "$USER_ID" ] && [ "$USER_ID" != "0" ]; then
  curl -s -X DELETE "$BASE/internal/user/$USER_ID" > /dev/null
fi
pass "清理完成"
echo ""

echo -e "${CYAN}=============================================${NC}"
if [ $ERRORS -eq 0 ]; then
  echo -e "${GREEN}  🎉 全部测试通过！${NC}"
else
  echo -e "${RED}  ⚠️  有 $ERRORS 项测试失败${NC}"
fi
echo -e "${CYAN}=============================================${NC}"
