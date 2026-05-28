#!/bin/bash
# FusionCareer Agent 测试脚本
# 前置条件：Java 后端运行在 localhost:9100，Agent 运行在 localhost:8900

BASE="http://localhost:8900"
ADMIN_TOKEN="${AGENT_ADMIN_TOKEN:-change-me-in-production}"
ADMIN_HDR=(-H "X-Agent-Admin-Token: $ADMIN_TOKEN")
echo "=== FusionCareer Agent API 测试 ==="

# 1. 健康检查
echo -e "\n--- 1. 健康检查 ---"
curl -s "$BASE/api/health" | python3 -m json.tool

# 2. 列出所有 Skill
echo -e "\n--- 2. 列出 Skill ---"
curl -s "$BASE/api/skills" | python3 -m json.tool

# 3. 列出预设工作流
echo -e "\n--- 3. 预设工作流 ---"
curl -s "$BASE/api/workflows" | python3 -m json.tool

# 4. 执行预设工作流（需要先在 Java 后端创建一个用户）
# 先通过 Internal API 创建测试用户
echo -e "\n--- 4. 创建测试用户 (Java 后端) ---"
USER_RESP=$(curl -s -X POST "http://localhost:9100/internal/user" \
  -H "Content-Type: application/json" \
  -d '{"username":"agent_test_user","role":0,"status":1}')
echo "$USER_RESP" | python3 -m json.tool
USER_ID=$(echo "$USER_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('id',''))" 2>/dev/null)
echo "用户ID: $USER_ID"

if [ -z "$USER_ID" ]; then
  echo "⚠️  未能获取用户ID，使用默认值 1"
  USER_ID=1
fi

# 5. 执行预设工作流
echo -e "\n--- 5. 执行预设工作流 (write_resume_profile) ---"
curl -s -X POST "$BASE/api/workflows/write_resume_profile/run" \
  "${ADMIN_HDR[@]}" \
  -H "Content-Type: application/json" \
  -d "{\"overrides\": {\"input_uid.int\": $USER_ID, \"input_rd.data\": {\"personalIntro\": \"Agent 测试\", \"education\": \"复旦大学\", \"skills\": \"Python\"}, \"input_pd.data\": {\"realName\": \"测试用户\", \"gender\": 1, \"major\": \"计算机\", \"eduLevel\": 2, \"grade\": \"2024级\", \"mindset\": 2}}}" \
  | python3 -m json.tool

# 6. 直接提交 inline 工作流
echo -e "\n--- 6. Inline 工作流测试 ---"
curl -s -X POST "$BASE/api/run" \
  "${ADMIN_HDR[@]}" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"inline_test\",
    \"nodes\": {
      \"w1\": {
        \"skill\": \"insert_resume\",
        \"inputs\": {
          \"user_id\": {\"value\": $USER_ID},
          \"resume_data\": {\"value\": {
            \"personalIntro\": \"Agent inline 测试\",
            \"education\": \"复旦大学 计算机科学 本科\",
            \"skills\": \"Java, Python, AI\"
          }}
        }
      }
    }
  }" | python3 -m json.tool

# 7. 验证数据已落库
echo -e "\n--- 7. 验证简历落库 (Java 后端) ---"
curl -s "http://localhost:9100/internal/resume/$USER_ID" | python3 -m json.tool

echo -e "\n--- 8. 验证资料落库 (Java 后端) ---"
curl -s "http://localhost:9100/internal/user-profile/$USER_ID" | python3 -m json.tool

echo -e "\n=== 测试完成 ==="
