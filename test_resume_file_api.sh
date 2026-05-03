#!/bin/bash
BASE_URL="http://localhost:8080"
PASS=0
FAIL=0
TOTAL=0

result() {
  TOTAL=$((TOTAL + 1))
  local name="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    PASS=$((PASS + 1))
    echo "  ✅ PASS"
  else
    FAIL=$((FAIL + 1))
    echo "  ❌ FAIL (期望: $expected, 实际: $actual)"
  fi
}

echo "============================================"
echo "  FusionCareer 简历文件 API 全接口测试"
echo "============================================"

# ─── 0. 准备：创建测试用户 ───────────────────────────────
echo -e "\n[0] 创建测试用户..."
CREATE_RESP=$(curl -s -X POST "$BASE_URL/internal/user" \
  -H "Content-Type: application/json" \
  -d '{"username": "file_test_user", "studentId": "20269999"}')
USER_ID=$(echo "$CREATE_RESP" | jq -r '.data.id')
echo "  >> 用户 ID: $USER_ID"

if [ "$USER_ID" = "null" ] || [ -z "$USER_ID" ]; then
  echo "❌ 创建用户失败: $CREATE_RESP"
  exit 1
fi

# ═══════════════════════════════════════════════
echo -e "\n============================================"
echo "  【1】Internal 简历文件接口"
echo "============================================"

# 1.1 空列表
echo -e "\n[1.1] GET /internal/resume-file/{userId}/list （空列表）"
RESP=$(curl -s "$BASE_URL/internal/resume-file/$USER_ID/list")
CODE=$(echo "$RESP" | jq -r '.code')
COUNT=$(echo "$RESP" | jq -r '.data | length')
echo "  >> code=$CODE, count=$COUNT"
result "空列表" "200" "$CODE"

# 1.2 上传 PDF
echo -e "\n[1.2] POST /internal/resume-file/{userId}/upload （上传 PDF）"
UPLOAD_RESP=$(curl -s -X POST "$BASE_URL/internal/resume-file/$USER_ID/upload" \
  -F "file=@test_resume.pdf;type=application/pdf")
UPLOAD_CODE=$(echo "$UPLOAD_RESP" | jq -r '.code')
FILE_ID=$(echo "$UPLOAD_RESP" | jq -r '.data.id')
ORIG_NAME=$(echo "$UPLOAD_RESP" | jq -r '.data.originalName')
echo "  >> code=$UPLOAD_CODE, fileId=$FILE_ID, name=$ORIG_NAME"
result "上传PDF" "200" "$UPLOAD_CODE"

# 1.3 上传后列表
echo -e "\n[1.3] GET /internal/resume-file/{userId}/list （上传后应有1条）"
RESP=$(curl -s "$BASE_URL/internal/resume-file/$USER_ID/list")
COUNT=$(echo "$RESP" | jq -r '.data | length')
echo "  >> count=$COUNT"
result "上传后列表" "1" "$COUNT"

# 1.4 下载文件
echo -e "\n[1.4] GET /internal/resume-file/{fileId}/download （下载文件）"
DL_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/internal/resume-file/$FILE_ID/download")
echo "  >> HTTP $DL_STATUS"
result "下载文件" "200" "$DL_STATUS"

# 1.5 下载内容验证
echo -e "\n[1.5] 下载内容验证 （应包含 %PDF 头）"
DL_HEAD=$(curl -s "$BASE_URL/internal/resume-file/$FILE_ID/download" | head -c 5)
echo "  >> 文件头: $DL_HEAD"
if [[ "$DL_HEAD" == *"%PDF"* ]]; then
  PASS=$((PASS + 1)); TOTAL=$((TOTAL + 1)); echo "  ✅ PASS"
else
  FAIL=$((FAIL + 1)); TOTAL=$((TOTAL + 1)); echo "  ❌ FAIL"
fi

# 1.6 下载不存在的文件
echo -e "\n[1.6] GET /internal/resume-file/999/download （不存在的文件）"
DL_404=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/internal/resume-file/999/download")
echo "  >> HTTP $DL_404"
result "404文件" "404" "$DL_404"

# ═══════════════════════════════════════════════
echo -e "\n============================================"
echo "  【2】上传校验测试"
echo "============================================"

# 2.1 上传空文件
echo -e "\n[2.1] 上传空文件 （应拒绝）"
echo -n "" > /tmp/empty.pdf
EMPTY_RESP=$(curl -s -X POST "$BASE_URL/internal/resume-file/$USER_ID/upload" \
  -F "file=@/tmp/empty.pdf;type=application/pdf")
EMPTY_CODE=$(echo "$EMPTY_RESP" | jq -r '.code')
echo "  >> code=$EMPTY_CODE, msg=$(echo $EMPTY_RESP | jq -r '.message')"
if [ "$EMPTY_CODE" != "200" ]; then
  PASS=$((PASS + 1)); TOTAL=$((TOTAL + 1)); echo "  ✅ PASS (正确拒绝)"
else
  FAIL=$((FAIL + 1)); TOTAL=$((TOTAL + 1)); echo "  ❌ FAIL (不应该成功)"
fi

# 2.2 上传非法格式
echo -e "\n[2.2] 上传 .txt 文件 （应拒绝）"
echo "hello" > /tmp/test.txt
BAD_RESP=$(curl -s -X POST "$BASE_URL/internal/resume-file/$USER_ID/upload" \
  -F "file=@/tmp/test.txt;type=text/plain")
BAD_CODE=$(echo "$BAD_RESP" | jq -r '.code')
echo "  >> code=$BAD_CODE, msg=$(echo $BAD_RESP | jq -r '.message')"
if [ "$BAD_CODE" != "200" ]; then
  PASS=$((PASS + 1)); TOTAL=$((TOTAL + 1)); echo "  ✅ PASS (正确拒绝)"
else
  FAIL=$((FAIL + 1)); TOTAL=$((TOTAL + 1)); echo "  ❌ FAIL (不应该成功)"
fi

# ═══════════════════════════════════════════════
echo -e "\n============================================"
echo "  【3】删除与清理"
echo "============================================"

# 3.1 用 internal user-profile 间接测试
# （删除只在用户端 Controller，这里用数据库验证）
echo -e "\n[3.1] 确认上传文件仍存在"
RESP=$(curl -s "$BASE_URL/internal/resume-file/$USER_ID/list")
COUNT=$(echo "$RESP" | jq -r '.data | length')
echo "  >> 文件数: $COUNT"
result "文件存在" "1" "$COUNT"

# ─── 清理 ──────────────────────────────────────
echo -e "\n[清理] 删除测试用户..."
curl -s -X DELETE "$BASE_URL/internal/user/$USER_ID" | jq -c

# ═══════════════════════════════════════════════
echo -e "\n============================================"
echo "  测试结果: $PASS/$TOTAL 通过, $FAIL 失败"
echo "============================================"

if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
