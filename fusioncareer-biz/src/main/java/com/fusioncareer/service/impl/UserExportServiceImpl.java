package com.fusioncareer.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.res.UserResponse;
import com.fusioncareer.entity.UserProfileEntity;
import com.fusioncareer.entity.ResumeEntity;
import com.fusioncareer.enums.UserRole;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.service.UserExportService;
import com.fusioncareer.service.UserProfileService;
import com.fusioncareer.service.UserService;
import com.fusioncareer.service.ResumeService;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserExportServiceImpl implements UserExportService {

    private static final String[][] ACCOUNT_COLUMNS = {
            {"id", "用户ID"}, {"studentId", "学号/工号"}, {"username", "用户名"},
            {"role", "角色"}, {"status", "账号状态"}, {"createdAt", "注册时间"}, {"updatedAt", "更新时间"}
    };
    private static final String[][] PROFILE_COLUMNS = {
            {"userId", "用户ID"}, {"realName", "姓名"}, {"gender", "性别"}, {"birthDate", "出生日期"},
            {"politicalStatus", "政治面貌"}, {"phone", "手机"}, {"email", "邮箱"}, {"wechat", "微信"},
            {"hometown", "生源地"}, {"grade", "年级"}, {"major", "专业"}, {"eduLevel", "学历"},
            {"supervisor", "导师"}, {"intentionOrder", "去向意向排序"}, {"intentionCity", "意向城市"},
            {"intentionDream", "理想岗位"}, {"mindset", "就业心态"}, {"updatedAt", "更新时间"}
    };
    private static final String[][] RESUME_COLUMNS = {
            {"userId", "用户ID"}, {"personalIntro", "个人简况"}, {"basicInfo", "基础信息"},
            {"education", "教育背景"}, {"internship", "实习经历"}, {"campus", "在校经历"},
            {"awards", "荣誉奖励"}, {"skills", "技能"}, {"portfolio", "作品集"},
            {"remark", "备注"}, {"updatedAt", "更新时间"}
    };

    private final UserService userService;
    private final UserProfileService profileService;
    private final ResumeService resumeService;

    @Override
    public byte[] exportUsers(String username, UserRole role, List<Long> userIds) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            Sheet accounts = createSheet(workbook, "账号信息", ACCOUNT_COLUMNS);
            Sheet profiles = createSheet(workbook, "用户资料", PROFILE_COLUMNS);
            Sheet resumes = createSheet(workbook, "简历正文", RESUME_COLUMNS);
            int page = 1;
            int rowIndex = 1;
            while (true) {
                PageResult<UserResponse> users = userService.listUsers(page, 100, username, role, userIds);
                if (users.getList().isEmpty()) {
                    break;
                }
                List<Long> ids = users.getList().stream().map(UserResponse::getId).toList();
                Map<Long, UserProfileEntity> profileMap = profileService.listByIds(ids).stream()
                        .collect(Collectors.toMap(UserProfileEntity::getUserId, Function.identity()));
                Map<Long, ResumeEntity> resumeMap = resumeService.listByIds(ids).stream()
                        .collect(Collectors.toMap(ResumeEntity::getUserId, Function.identity()));
                for (UserResponse user : users.getList()) {
                    writeRow(accounts, rowIndex, user, user.getId(), ACCOUNT_COLUMNS);
                    writeRow(profiles, rowIndex, profileMap.get(user.getId()), user.getId(), PROFILE_COLUMNS);
                    writeRow(resumes, rowIndex, resumeMap.get(user.getId()), user.getId(), RESUME_COLUMNS);
                    rowIndex++;
                }
                if (page++ >= users.getTotalPages()) {
                    break;
                }
            }
            for (Sheet sheet : workbook) {
                sheet.setAutoFilter(new CellRangeAddress(0, rowIndex - 1, 0, sheet.getRow(0).getLastCellNum() - 1));
            }
            workbook.write(bytes);
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("用户信息导出失败", error);
        }
    }

    private Sheet createSheet(XSSFWorkbook workbook, String name, String[][] columns) {
        Sheet sheet = workbook.createSheet(name);
        Row header = sheet.createRow(0);
        for (int i = 0; i < columns.length; i++) {
            header.createCell(i).setCellValue(columns[i][1]);
            sheet.setColumnWidth(i, 24 * 256);
        }
        sheet.createFreezePane(0, 1);
        return sheet;
    }

    private void writeRow(Sheet sheet, int index, Object bean, Long userId, String[][] columns) {
        Map<String, Object> values = bean == null ? Map.of("userId", userId) : BeanUtil.beanToMap(bean);
        Row row = sheet.createRow(index);
        for (int i = 0; i < columns.length; i++) {
            Object value = values.get(columns[i][0]);
            if (value instanceof Enum<?>) {
                value = BeanUtil.getProperty(value, "desc");
            }
            // Explicit string cells preserve long IDs/leading zeroes and never execute spreadsheet formulas.
            String text = value == null ? "" : value.toString();
            if (text.length() > 32767) {
                throw ServiceException.of(ResultCode.VALIDATE_FAILED,
                        "用户 " + userId + " 的“" + columns[i][1] + "”超过 Excel 单元格长度限制，请通过查询接口获取完整内容");
            }
            row.createCell(i).setCellValue(text);
        }
    }
}
