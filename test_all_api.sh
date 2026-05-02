#!/bin/bash
set -e
BASE_URL="http://localhost:8080"
echo "======================================"
echo "    FusionCareer CRUD API Test        "
echo "======================================"

# 1. 登录获取 Token
echo -e "\n[0] 模拟登录获取 Token..."
LOGIN_RESP=$(curl -s "$BASE_URL/auth/login")
TOKEN=$(echo "$LOGIN_RESP" | jq -r '.data.tokenValue')
LOGIN_ID=$(echo "$LOGIN_RESP" | jq -r '.data.loginId')
echo "  >> Token: $TOKEN"
echo "  >> LoginID: $LOGIN_ID"

echo -e "\n======================================"
echo "    【1】 内部用户管理 (Internal User)"
echo "======================================"

# 创建用户
echo "[1.1] POST /internal/user (创建新用户)"
CREATE_USER_RESP=$(curl -s -X POST "$BASE_URL/internal/user" \
  -H "Content-Type: application/json" \
  -d '{"username": "test_user_01", "studentId": "20260001", "role": "USER", "status": "NORMAL"}')
echo "  >> $CREATE_USER_RESP"
NEW_USER_ID=$(echo "$CREATE_USER_RESP" | jq -r '.data.id')

# 获取单条
echo -e "\n[1.2] GET /internal/user/$NEW_USER_ID (获取刚创建的用户)"
curl -s "$BASE_URL/internal/user/$NEW_USER_ID" | jq -c

# 列表查询
echo -e "\n[1.3] GET /internal/user/list?page=1&size=10 (分页列表)"
curl -s "$BASE_URL/internal/user/list?page=1&size=10" | jq -c

# 更新
echo -e "\n[1.4] PUT /internal/user/$NEW_USER_ID (更新用户)"
curl -s -X PUT "$BASE_URL/internal/user/$NEW_USER_ID" \
  -H "Content-Type: application/json" \
  -d '{"username": "test_user_updated", "status": "DISABLED"}' | jq -c

# 删除
echo -e "\n[1.5] DELETE /internal/user/$NEW_USER_ID (删除用户)"
curl -s -X DELETE "$BASE_URL/internal/user/$NEW_USER_ID" | jq -c

echo -e "\n======================================"
echo "    【2】 用户档案 (User Profile)     "
echo "======================================"

# 客户端更新/保存档案
echo "[2.1] PUT /api/user/profile (保存或更新个人档案)"
curl -s -X PUT "$BASE_URL/api/user/profile" \
  -H "Fusion-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"realName": "Tester 02", "gender": "FEMALE", "phone": "13999999999"}' | jq -c

# 客户端读取档案
echo -e "\n[2.2] GET /api/user/profile (读取个人档案)"
curl -s -H "Fusion-Token: $TOKEN" "$BASE_URL/api/user/profile" | jq -c

# 内部读取所有档案
echo -e "\n[2.3] GET /internal/user-profile/list (管理员列出所有档案)"
curl -s "$BASE_URL/internal/user-profile/list" | jq -c

# 内部更新档案 (模拟管理员覆盖)
echo -e "\n[2.4] PUT /internal/user-profile/$LOGIN_ID (管理员更新特定用户档案)"
curl -s -X PUT "$BASE_URL/internal/user-profile/$LOGIN_ID" \
  -H "Content-Type: application/json" \
  -d '{"realName": "Admin Overwrite", "gender": "MALE"}' | jq -c

# 内部删除档案
echo -e "\n[2.5] DELETE /internal/user-profile/$LOGIN_ID (管理员删除特定用户档案)"
curl -s -X DELETE "$BASE_URL/internal/user-profile/$LOGIN_ID" | jq -c

echo -e "\n======================================"
echo "    【3】 用户简历 (Resume)           "
echo "======================================"

echo "[3.1] PUT /api/user/resume (保存或更新简历)"
curl -s -X PUT "$BASE_URL/api/user/resume" \
  -H "Fusion-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"personalIntro": "I am a backend developer.", "skills": "Java, Spring Boot, Vue"}' | jq -c

echo -e "\n[3.2] GET /api/user/resume (读取自己的简历)"
curl -s -H "Fusion-Token: $TOKEN" "$BASE_URL/api/user/resume" | jq -c

echo -e "\n[3.3] GET /internal/resume/list (内部获取所有简历)"
curl -s "$BASE_URL/internal/resume/list" | jq -c

echo -e "\n[3.4] DELETE /internal/resume/$LOGIN_ID (管理员删除特定用户简历)"
curl -s -X DELETE "$BASE_URL/internal/resume/$LOGIN_ID" | jq -c

echo -e "\n======================================"
echo "    【4】 岗位帖子 (Job Post)         "
echo "======================================"

echo "[4.1] POST /internal/job-post (管理员发单条岗位)"
CREATE_JOB_RESP=$(curl -s -X POST "$BASE_URL/internal/job-post" \
  -H "Content-Type: application/json" \
  -d '{
    "companyName": "ByteDance",
    "positionName": "Data Engineer",
    "jobCategory": "TECHNOLOGY",
    "status": "PUBLISHED"
  }')
echo "  >> $CREATE_JOB_RESP"
JOB_ID=$(echo "$CREATE_JOB_RESP" | jq -r '.data.id')

echo -e "\n[4.2] POST /internal/job-post/batch (管理员批量发岗位)"
curl -s -X POST "$BASE_URL/internal/job-post/batch" \
  -H "Content-Type: application/json" \
  -d '[
    {"companyName": "Meituan", "positionName": "Frontend", "status": "DRAFT"},
    {"companyName": "Baidu", "positionName": "AI Engineer", "status": "PUBLISHED"}
  ]' | jq -c

echo -e "\n[4.3] GET /internal/job-post/$JOB_ID (内部获取单个岗位)"
curl -s "$BASE_URL/internal/job-post/$JOB_ID" | jq -c

echo -e "\n[4.4] GET /api/job/list (用户端分页查询已发布的岗位)"
curl -s -H "Fusion-Token: $TOKEN" "$BASE_URL/api/job/list?page=1&size=10&keyword=Engineer" | jq -c

echo -e "\n[4.5] PUT /internal/job-post/$JOB_ID (管理员更新岗位)"
curl -s -X PUT "$BASE_URL/internal/job-post/$JOB_ID" \
  -H "Content-Type: application/json" \
  -d '{"status": "CLOSED"}' | jq -c

echo -e "\n[4.6] DELETE /internal/job-post/$JOB_ID (管理员删除岗位)"
curl -s -X DELETE "$BASE_URL/internal/job-post/$JOB_ID" | jq -c

echo -e "\n✅ 所有接口测试完毕！"
