package com.fusioncareer;

import cn.dev33.satoken.stp.StpUtil;
import com.fusioncareer.dto.req.ResumeRequest;
import com.fusioncareer.dto.req.UserProfileRequest;
import com.fusioncareer.dto.res.ResumeFileResponse;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.enums.UserRole;
import com.fusioncareer.enums.UserStatus;
import com.fusioncareer.service.ResumeFileService;
import com.fusioncareer.service.ResumeService;
import com.fusioncareer.service.UserProfileService;
import com.fusioncareer.service.UserService;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminUserTest {
    @Autowired MockMvc mvc;
    @Autowired UserService users;
    @Autowired UserProfileService profiles;
    @Autowired ResumeService resumes;
    @Autowired ResumeFileService files;

    private final List<Long> actors = new ArrayList<>();
    private UserEntity target;
    private UserEntity superAdmin;
    private String token;
    private ResumeFileResponse file;

    @BeforeEach
    void setup() {
        target = user("admin-user-target", UserRole.NORMAL);
        target.setUsername("=SUM(1,1)");
        target.setPassword("must-not-export");
        users.updateById(target);
        superAdmin = user("admin-user-super", UserRole.SUPERADMIN);
        token = StpUtil.getStpLogic().createLoginSession(superAdmin.getId());
        UserProfileRequest profile = new UserProfileRequest();
        profile.setRealName("测试同学");
        profile.setPhone("0013800000000");
        profile.setEmail("test@example.test");
        profiles.saveOrUpdateProfile(target.getId(), profile);
        ResumeRequest resume = new ResumeRequest();
        resume.setEducation("新闻学本科");
        resume.setSkills("Python");
        resumes.saveOrUpdateResume(target.getId(), resume);
        file = files.upload(target.getId(), new MockMultipartFile(
                "file", "测试简历.pdf", "application/pdf", "%PDF-test".getBytes(StandardCharsets.UTF_8)));
    }

    @AfterEach
    void cleanup() {
        if (file != null) {
            files.delete(target.getId(), file.getId());
        }
        actors.forEach(StpUtil::logout);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"NORMAL", "ADMIN"})
    void rejectSystemManagementForOtherRoles(UserRole role) throws Exception {
        String otherToken = StpUtil.getStpLogic().createLoginSession(user("other-" + role, role).getId());
        for (String path : List.of("/admin/user/list", "/admin/user/export", "/admin/user/" + target.getId(),
                "/admin/user/" + target.getId() + "/profile", "/admin/user/" + target.getId() + "/resume",
                "/admin/user/" + target.getId() + "/resume/file/list", downloadPath(target.getId()))) {
            mvc.perform(get(path).header("Fusion-Token", otherToken)).andExpect(status().isForbidden());
        }
        mvc.perform(put("/admin/user/{id}/role", target.getId()).header("Fusion-Token", otherToken)
                        .param("role", "SUPERADMIN")).andExpect(status().isForbidden());
    }

    @Test
    void superAdminCanReadProfilesResumesAndDownload() throws Exception {
        mvc.perform(get("/admin/user/{id}", target.getId()).header("Fusion-Token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.password").doesNotExist());
        mvc.perform(get("/admin/user/{id}/profile", target.getId()).header("Fusion-Token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.realName").value("测试同学"));
        mvc.perform(get("/admin/user/{id}/resume", target.getId()).header("Fusion-Token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.education").value("新闻学本科"));
        mvc.perform(get("/admin/user/{id}/resume/file/list", target.getId()).header("Fusion-Token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].originalName").value("测试简历.pdf"))
                .andExpect(jsonPath("$.data[0].storagePath").doesNotExist());
        mvc.perform(get(downloadPath(target.getId())).header("Fusion-Token", token))
                .andExpect(status().isOk()).andExpect(content().bytes("%PDF-test".getBytes(StandardCharsets.UTF_8)))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("Content-Disposition"));
        mvc.perform(get(downloadPath(superAdmin.getId())).header("Fusion-Token", token))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin/user/-1/profile").header("Fusion-Token", token)).andExpect(status().isNotFound());
        mvc.perform(get("/admin/user/{id}/profile", superAdmin.getId()).header("Fusion-Token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void roleChangesApplyImmediatelyAndPreventSelfDemotion() throws Exception {
        String targetToken = StpUtil.getStpLogic().createLoginSession(target.getId());
        mvc.perform(put("/admin/user/{id}/role", target.getId()).header("Fusion-Token", token)
                        .param("role", "SUPERADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("SUPERADMIN"));
        mvc.perform(get("/admin/user/list").header("Fusion-Token", targetToken)).andExpect(status().isOk());
        mvc.perform(get("/admin/job-post/list").header("Fusion-Token", targetToken)).andExpect(status().isOk());
        mvc.perform(put("/admin/user/{id}/role", target.getId()).header("Fusion-Token", token)
                        .param("role", "ADMIN")).andExpect(status().isOk());
        mvc.perform(get("/admin/user/list").header("Fusion-Token", targetToken)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/job-post/list").header("Fusion-Token", targetToken)).andExpect(status().isOk());
        mvc.perform(put("/admin/user/{id}/role", superAdmin.getId()).header("Fusion-Token", token)
                        .param("role", "ADMIN")).andExpect(status().isBadRequest());
        assertThat(users.getById(superAdmin.getId()).getRole()).isEqualTo(UserRole.SUPERADMIN);
        mvc.perform(put("/admin/user/{id}/role", target.getId()).header("Fusion-Token", token)
                        .param("role", "INVALID")).andExpect(status().isBadRequest());
        mvc.perform(put("/admin/user/{id}/role", target.getId()).header("Fusion-Token", targetToken)
                        .param("role", "SUPERADMIN")).andExpect(status().isForbidden());
    }

    @Test
    void protectLegacyStaticUrlsWithoutBreakingOwnerDownloads() throws Exception {
        String path = "/files/" + files.getById(file.getId()).getStoragePath();
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        String adminToken = StpUtil.getStpLogic().createLoginSession(user("file-admin", UserRole.ADMIN).getId());
        mvc.perform(get(path).header("Fusion-Token", adminToken)).andExpect(status().isForbidden());
        String ownerToken = StpUtil.getStpLogic().createLoginSession(target.getId());
        mvc.perform(get(path).header("Fusion-Token", ownerToken)).andExpect(status().isOk());
        mvc.perform(get(path).header("Fusion-Token", token)).andExpect(status().isOk());
        mvc.perform(get("/user/resume/file/{id}/download", file.getId()).header("Fusion-Token", ownerToken))
                .andExpect(status().isOk());
    }

    @Test
    void exportSelectedUserWithProfilesAndResumeAsTextCells() throws Exception {
        byte[] body = mvc.perform(get("/admin/user/export").header("Fusion-Token", token)
                        .param("userIds", target.getId().toString()).param("role", "NORMAL"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(body))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(3);
            var account = workbook.getSheet("账号信息");
            assertThat(account.getLastRowNum()).isEqualTo(1);
            assertThat(account.getRow(1).getCell(0).getStringCellValue()).isEqualTo(target.getId().toString());
            assertThat(account.getRow(1).getCell(2).getCellType()).isEqualTo(CellType.STRING);
            assertThat(account.getRow(1).getCell(2).getStringCellValue()).isEqualTo("=SUM(1,1)");
            assertThat(account.getRow(1).getCell(3).getStringCellValue()).isEqualTo("普通用户");
            assertThat(workbook.getSheet("用户资料").getRow(1).getCell(5).getStringCellValue()).isEqualTo("0013800000000");
            assertThat(workbook.getSheet("简历正文").getRow(1).getCell(3).getStringCellValue()).isEqualTo("新闻学本科");
            for (var sheet : workbook) {
                for (var row : sheet) {
                    for (var cell : row) {
                        assertThat(cell.getStringCellValue()).doesNotContain("must-not-export");
                    }
                }
            }
        }
    }

    @Test
    void rejectAnonymousAndDisabledSuperAdmin() throws Exception {
        mvc.perform(get("/admin/user/list")).andExpect(status().isUnauthorized());
        superAdmin.setStatus(UserStatus.DISABLED);
        users.updateById(superAdmin);
        mvc.perform(get("/admin/user/list").header("Fusion-Token", token)).andExpect(status().isForbidden());
    }

    private UserEntity user(String studentId, UserRole role) {
        UserEntity user = new UserEntity();
        user.setStudentId(studentId);
        user.setUsername(studentId);
        user.setRole(role);
        user.setStatus(UserStatus.NORMAL);
        user.setCreatedAt(LocalDateTime.now());
        users.save(user);
        actors.add(user.getId());
        return user;
    }

    private String downloadPath(Long userId) {
        return "/admin/user/" + userId + "/resume/file/" + file.getId() + "/download";
    }
}
