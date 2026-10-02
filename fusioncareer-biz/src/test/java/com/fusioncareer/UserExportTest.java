package com.fusioncareer;

import com.fusioncareer.dto.req.ResumeRequest;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.enums.UserRole;
import com.fusioncareer.enums.UserStatus;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.service.UserExportService;
import com.fusioncareer.service.UserService;
import com.fusioncareer.service.ResumeService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserExportTest {
    @Autowired UserService users;
    @Autowired ResumeService resumes;
    @Autowired UserExportService exporter;

    @Test
    void exportEveryPageWithStableOrderingAndEmptyProfiles() throws Exception {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 103; i++) {
            ids.add(user("export-page-" + i).getId().toString());
        }
        try (XSSFWorkbook workbook = read(exporter.exportUsers("export-page-", UserRole.NORMAL, null))) {
            var sheet = workbook.getSheet("账号信息");
            Set<String> exported = new HashSet<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                exported.add(sheet.getRow(i).getCell(0).getStringCellValue());
            }
            assertThat(exported).isEqualTo(ids);
            assertThat(sheet.getLastRowNum()).isEqualTo(103);
            assertThat(workbook.getSheet("用户资料").getLastRowNum()).isEqualTo(103);
            assertThat(workbook.getSheet("用户资料").getRow(1).getCell(1).getStringCellValue()).isEmpty();
        }
        try (XSSFWorkbook workbook = read(exporter.exportUsers("export-page-", UserRole.SUPERADMIN, null))) {
            assertThat(workbook.getSheet("账号信息").getLastRowNum()).isZero();
        }
    }

    @Test
    void rejectOversizedCellsRatherThanSilentlyLosingResumeContent() {
        UserEntity user = user("export-large");
        ResumeRequest resume = new ResumeRequest();
        resume.setEducation("x".repeat(32768));
        resumes.saveOrUpdateResume(user.getId(), resume);
        assertThatThrownBy(() -> exporter.exportUsers(null, null, List.of(user.getId())))
                .isInstanceOf(ServiceException.class).hasMessageContaining("Excel 单元格长度限制");
    }

    private UserEntity user(String name) {
        UserEntity user = new UserEntity();
        user.setUsername(name);
        user.setStudentId(name);
        user.setRole(UserRole.NORMAL);
        user.setStatus(UserStatus.NORMAL);
        user.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        users.save(user);
        return user;
    }

    private XSSFWorkbook read(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }
}
