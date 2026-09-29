package com.fusioncareer;

import cn.dev33.satoken.stp.StpUtil;
import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.dto.req.QuestionnaireReviewRequest;
import com.fusioncareer.dto.req.QuestionnaireSubmitRequest;
import com.fusioncareer.dto.req.ResumeRequest;
import com.fusioncareer.dto.req.UserProfileRequest;
import com.fusioncareer.dto.req.UserMemoryUpdateRequest;
import com.fusioncareer.dto.res.ResumeFileResponse;
import com.fusioncareer.entity.QuestionnaireAnswerEntity;
import com.fusioncareer.entity.UserChangeActionEntity;
import com.fusioncareer.entity.UserChangeItemEntity;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.enums.ChangeResourceType;
import com.fusioncareer.enums.ChangeActionStatus;
import com.fusioncareer.enums.ChangeOperation;
import com.fusioncareer.enums.JobCategory;
import com.fusioncareer.enums.JobPostStatus;
import com.fusioncareer.enums.QuestionnaireSubmissionStatus;
import com.fusioncareer.enums.RecruitType;
import com.fusioncareer.enums.UserRole;
import com.fusioncareer.enums.UserStatus;
import com.fusioncareer.service.JobPostService;
import com.fusioncareer.service.ChangeSnapshotCipher;
import com.fusioncareer.service.QuestionnaireAnswerService;
import com.fusioncareer.service.ResumeFileService;
import com.fusioncareer.service.ResumeService;
import com.fusioncareer.service.UserProfileService;
import com.fusioncareer.service.UserMemoryService;
import com.fusioncareer.service.UserChangeService;
import com.fusioncareer.service.UserService;
import com.fusioncareer.mapper.UserChangeActionMapper;
import com.fusioncareer.mapper.UserChangeItemMapper;
import com.fusioncareer.mapper.UserMemoryMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PersonalSpaceTest {

    @Autowired MockMvc readMvc;
    @Autowired UserService readUsers;
    @Autowired UserProfileService readProfiles;
    @Autowired ResumeService readResumes;
    @Autowired ResumeFileService readFiles;
    @Autowired QuestionnaireAnswerService readAnswers;
    @Autowired JobPostService readJobs;
    @Autowired UserMemoryService manageMemory;
    @Autowired UserChangeActionMapper readActions;
    @Autowired UserChangeItemMapper readChangeItems;
    @Autowired ChangeSnapshotCipher changeCipher;
    @Autowired UserChangeService readChanges;
    @Autowired UserMemoryMapper readMemoryRows;

    private UserEntity createUser;
    private ResumeFileResponse createFile;
    private String createToken;

    @BeforeEach
    void createSpace() {
        createUser = new UserEntity();
        createUser.setUsername("space-user");
        createUser.setStudentId("space-student");
        createUser.setRole(UserRole.NORMAL);
        createUser.setStatus(UserStatus.NORMAL);
        createUser.setCreatedAt(LocalDateTime.now());
        readUsers.save(createUser);

        UserProfileRequest createProfile = new UserProfileRequest();
        createProfile.setRealName("空间用户");
        createProfile.setPhone("13800000000");
        readProfiles.saveOrUpdateProfile(createUser.getId(), createProfile);

        ResumeRequest createResume = new ResumeRequest();
        createResume.setEducation("不应出现在总览中的简历正文");
        readResumes.saveOrUpdateResume(createUser.getId(), createResume);

        createFile = readFiles.upload(createUser.getId(), new MockMultipartFile(
                "file",
                "space.pdf",
                "application/pdf",
                "%PDF-space".getBytes(StandardCharsets.UTF_8)));

        saveAnswer(910001L, QuestionnaireSubmissionStatus.DRAFT);
        saveAnswer(910002L, QuestionnaireSubmissionStatus.SUBMITTED);
        createToken = StpUtil.getStpLogic().createLoginSession(createUser.getId());
    }

    @AfterEach
    void clearSpace() {
        if (createFile != null) {
            readFiles.purge(createUser.getId(), createFile.getId());
        }
        if (createUser != null) {
            StpUtil.logout(createUser.getId());
        }
    }

    @Test
    void rejectGuest() throws Exception {
        readMvc.perform(get("/personal-space"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readEmptySpace() throws Exception {
        UserEntity createOther = new UserEntity();
        createOther.setUsername("empty-space-student");
        createOther.setStudentId("empty-space-student");
        createOther.setRole(UserRole.NORMAL);
        createOther.setStatus(UserStatus.NORMAL);
        createOther.setCreatedAt(LocalDateTime.now());
        readUsers.save(createOther);
        String createOtherToken = StpUtil.getStpLogic().createLoginSession(createOther.getId());
        try {
            readMvc.perform(get("/personal-space").header("Fusion-Token", createOtherToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.account.id").value(createOther.getId().toString()))
                    .andExpect(jsonPath("$.data.account.displayName").value("未填写姓名"))
                    .andExpect(jsonPath("$.data.sections.profile.exists").value(false))
                    .andExpect(jsonPath("$.data.sections.resume.exists").value(false))
                    .andExpect(jsonPath("$.data.sections.documents.count").value("0"))
                    .andExpect(jsonPath("$.data.sections.applications.total").value("0"));
        } finally {
            StpUtil.logout(createOther.getId());
        }
    }

    @Test
    void readOverview() throws Exception {
        readMvc.perform(get("/personal-space").header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account.id").value(createUser.getId().toString()))
                .andExpect(jsonPath("$.data.account.displayName").value("空间用户"))
                .andExpect(jsonPath("$.data.sections.profile.exists").value(true))
                .andExpect(jsonPath("$.data.sections.profile.version").value("0"))
                .andExpect(jsonPath("$.data.sections.resume.exists").value(true))
                .andExpect(jsonPath("$.data.sections.resume.version").value("0"))
                .andExpect(jsonPath("$.data.sections.resume.education").doesNotExist())
                .andExpect(jsonPath("$.data.sections.documents.count").value("1"))
                .andExpect(jsonPath("$.data.sections.documents.usedBytes").value("10"))
                .andExpect(jsonPath("$.data.sections.documents.files").doesNotExist())
                .andExpect(jsonPath("$.data.sections.applications.total").value("2"))
                .andExpect(jsonPath("$.data.sections.applications.draft").value("1"))
                .andExpect(jsonPath("$.data.sections.applications.submitted").value("1"))
                .andExpect(jsonPath("$.data.sections.applications.reviewed").value("0"))
                .andExpect(jsonPath("$.data.sections.memory.count").value(0))
                .andExpect(jsonPath("$.data.sections.memory.version").value("0"))
                .andExpect(jsonPath("$.data.sections.assistant.sessionExists").value(false))
                .andExpect(jsonPath("$.data.sections.assistant.activeRun").value(false))
                .andExpect(jsonPath("$.data.phone").doesNotExist())
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    void readSections() throws Exception {
        readMvc.perform(get("/personal-space/profile").header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.realName").value("空间用户"));
        readMvc.perform(get("/personal-space/resume").header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.education").value("不应出现在总览中的简历正文"));
        readMvc.perform(get("/personal-space/documents").header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[0].originalName").value("space.pdf"))
                .andExpect(jsonPath("$.data.files[0].storagePath").doesNotExist())
                .andExpect(jsonPath("$.data.usedBytes").value("10"));
        readMvc.perform(get("/personal-space/applications")
                        .header("Fusion-Token", createToken)
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tabCounts.all").value("2"))
                .andExpect(jsonPath("$.data.tabCounts.draft").value("1"))
                .andExpect(jsonPath("$.data.tabCounts.pending").value("1"));
    }

    @Test
    void patchProfile() throws Exception {
        readMvc.perform(patch("/personal-space/profile")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 0,
                                  "set": {
                                    "major": "新闻传播学",
                                    "gender": "FEMALE",
                                    "birthDate": "2000-01-15",
                                    "intentionCity": ["上海", "杭州"]
                                  },
                                  "clear": ["phone"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("1"))
                .andExpect(jsonPath("$.data.realName").value("空间用户"))
                .andExpect(jsonPath("$.data.major").value("新闻传播学"))
                .andExpect(jsonPath("$.data.gender").value("FEMALE"))
                .andExpect(jsonPath("$.data.birthDate").value("2000-01-15"))
                .andExpect(jsonPath("$.data.intentionCity").value("[\"上海\", \"杭州\"]"))
                .andExpect(jsonPath("$.data.phone").doesNotExist());

        readMvc.perform(patch("/personal-space/profile")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 1, "set": {"major": "新闻传播学"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("1"));
    }

    @Test
    void patchResume() throws Exception {
        readMvc.perform(patch("/personal-space/resume")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 0,
                                  "set": {"skills": "Java, Python"},
                                  "clear": ["education"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("1"))
                .andExpect(jsonPath("$.data.skills").value("Java, Python"))
                .andExpect(jsonPath("$.data.education").doesNotExist());
    }

    @Test
    void rejectStalePatch() throws Exception {
        String updateBody = """
                {"expectedVersion": 0, "set": {"major": "新闻传播学"}}
                """;
        readMvc.perform(patch("/personal-space/profile")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk());
        readMvc.perform(patch("/personal-space/profile")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void rejectInvalidPatch() throws Exception {
        for (String readBody : new String[]{
                "{\"expectedVersion\":0,\"set\":{\"version\":1}}",
                "{\"expectedVersion\":0,\"set\":{\"major\":null}}",
                "{\"expectedVersion\":0,\"set\":{\"major\":\"新闻学\"},\"clear\":[\"major\"]}",
                "{\"expectedVersion\":0,\"set\":{\"intentionCity\":\"上海\"}}",
                "{\"expectedVersion\":-1,\"set\":{\"major\":\"新闻学\"}}"
        }) {
            readMvc.perform(patch("/personal-space/profile")
                            .header("Fusion-Token", createToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(readBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }
    }

    @Test
    void createProfileByPatch() throws Exception {
        UserEntity createOther = new UserEntity();
        createOther.setUsername("patch-space-user");
        createOther.setStudentId("patch-space-student");
        createOther.setRole(UserRole.NORMAL);
        createOther.setStatus(UserStatus.NORMAL);
        createOther.setCreatedAt(LocalDateTime.now());
        readUsers.save(createOther);
        String createOtherToken = StpUtil.getStpLogic().createLoginSession(createOther.getId());
        try {
            readMvc.perform(patch("/personal-space/profile")
                            .header("Fusion-Token", createOtherToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"expectedVersion": 0, "set": {"major": "广告学"}}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.version").value("0"))
                    .andExpect(jsonPath("$.data.major").value("广告学"));
        } finally {
            StpUtil.logout(createOther.getId());
        }
    }

    @Test
    void manageMemory() throws Exception {
        readMvc.perform(get("/personal-space/memory").header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("0"))
                .andExpect(jsonPath("$.data.entries").isEmpty());

        readMvc.perform(put("/personal-space/memory/responseStyle")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 0, "value": "简洁并给出行动清单"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("0"))
                .andExpect(jsonPath("$.data.entries.responseStyle.value")
                        .value("简洁并给出行动清单"));

        readMvc.perform(put("/personal-space/memory/targetCities")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 0, "value": ["上海", "杭州"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("1"))
                .andExpect(jsonPath("$.data.entries.targetCities.value[0]").value("上海"));

        readMvc.perform(put("/personal-space/memory/targetCities")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 1, "value": ["上海", "杭州"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("1"));

        readMvc.perform(delete("/personal-space/memory/responseStyle")
                        .header("Fusion-Token", createToken)
                        .param("expectedVersion", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("2"))
                .andExpect(jsonPath("$.data.entries.responseStyle").doesNotExist());

        readMvc.perform(delete("/personal-space/memory")
                        .header("Fusion-Token", createToken)
                        .param("expectedVersion", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("3"))
                .andExpect(jsonPath("$.data.entries").isEmpty());
    }

    @Test
    void rejectInvalidMemory() throws Exception {
        readMvc.perform(put("/personal-space/memory/privateNote")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"value\":\"秘密\"}"))
                .andExpect(status().isBadRequest());
        readMvc.perform(put("/personal-space/memory/targetCities")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"value\":\"上海\"}"))
                .andExpect(status().isBadRequest());
        readMvc.perform(put("/personal-space/memory/currentGoal")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"value\":[\"找实习\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectStaleMemory() throws Exception {
        readMvc.perform(put("/personal-space/memory/currentGoal")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"value\":\"找实习\"}"))
                .andExpect(status().isOk());
        readMvc.perform(put("/personal-space/memory/targetCities")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"value\":[\"上海\"]}"))
                .andExpect(status().isOk());
        readMvc.perform(put("/personal-space/memory/currentGoal")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"value\":\"找全职\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void limitMemorySize() {
        List<String> createValues = java.util.stream.IntStream.range(0, 10)
                .mapToObj(readIndex -> readIndex + "城" + "长".repeat(100))
                .toList();
        manageMemory.saveMemory(createUser.getId(), "targetCities",
                new UserMemoryUpdateRequest(0L, createValues));

        assertThatThrownBy(() -> manageMemory.saveMemory(
                createUser.getId(), "targetIndustries",
                new UserMemoryUpdateRequest(0L, createValues)))
                .hasMessageContaining("4KB");
    }

    @Test
    void encryptSnapshots() {
        Map<String, Object> writeSnapshot = Map.of(
                "exists", true,
                "fields", Map.of("phone", "13800000000"));
        ChangeSnapshotCipher.EncryptedSnapshot createFirst =
                changeCipher.encryptSnapshot(writeSnapshot, "test-aad");
        ChangeSnapshotCipher.EncryptedSnapshot createSecond =
                changeCipher.encryptSnapshot(writeSnapshot, "test-aad");

        assertThat(createFirst.payload()).isNotEqualTo(createSecond.payload());
        assertThat(new String(createFirst.payload(), StandardCharsets.ISO_8859_1))
                .doesNotContain("13800000000");
        assertThat(changeCipher.decryptSnapshot(
                createFirst.payload(), createFirst.keyVersion(), "test-aad"))
                .extracting("exists").isEqualTo(true);

        byte[] updatePayload = Arrays.copyOf(createFirst.payload(), createFirst.payload().length);
        updatePayload[updatePayload.length - 1] ^= 1;
        assertThatThrownBy(() -> changeCipher.decryptSnapshot(
                updatePayload, createFirst.keyVersion(), "test-aad"))
                .hasMessageContaining("无法解密");
        assertThatThrownBy(() -> changeCipher.decryptSnapshot(
                createFirst.payload(), createFirst.keyVersion(), "wrong-aad"))
                .hasMessageContaining("无法解密");
    }

    @Test
    void recordHistory() throws Exception {
        readMvc.perform(patch("/personal-space/profile")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 0,
                                  "set": {
                                    "major": "新闻传播学",
                                    "gender": "FEMALE",
                                    "birthDate": "2000-01-15",
                                    "intentionCity": ["上海", "杭州"]
                                  },
                                  "clear": ["phone"]
                                }
                                """))
                .andExpect(status().isOk());
        readMvc.perform(put("/personal-space/memory/currentGoal")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"value\":\"寻找媒体实习\"}"))
                .andExpect(status().isOk());

        List<UserChangeActionEntity> loadActions = readActions.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserChangeActionEntity>()
                        .eq(UserChangeActionEntity::getUserId, createUser.getId())
                        .orderByDesc(UserChangeActionEntity::getId));
        assertThat(loadActions).hasSize(2);
        List<UserChangeItemEntity> loadItems = readChangeItems.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserChangeItemEntity>()
                        .in(UserChangeItemEntity::getActionId,
                                loadActions.stream().map(UserChangeActionEntity::getId).toList()));
        assertThat(loadItems).hasSize(2)
                .extracting(UserChangeItemEntity::getResourceType)
                .containsExactlyInAnyOrder(ChangeResourceType.PROFILE, ChangeResourceType.MEMORY);

        UserChangeItemEntity readProfileChange = loadItems.stream()
                .filter(readItem -> readItem.getResourceType() == ChangeResourceType.PROFILE)
                .findFirst()
                .orElseThrow();
        assertThat(readProfileChange.getChangedFields()).contains("major", "phone");
        assertThat(new String(readProfileChange.getAfterCiphertext(), StandardCharsets.ISO_8859_1))
                .doesNotContain("新闻传播学");
        String readAad = readChanges.buildAad(
                createUser.getId(),
                readProfileChange.getActionId(),
                readProfileChange.getResourceType(),
                readProfileChange.getResourceKey(),
                "after");
        assertThat(changeCipher.decryptSnapshot(
                readProfileChange.getAfterCiphertext(),
                readProfileChange.getKeyVersion(),
                readAad).toString())
                .contains("新闻传播学");

        readMvc.perform(get("/personal-space/actions/{actionId}/confirmation",
                        readProfileChange.getActionId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresConfirmation").value(false))
                .andExpect(jsonPath("$.data.changes[?(@.field == 'major')].afterDisplay")
                        .value(org.hamcrest.Matchers.hasItem("新闻传播学")))
                .andExpect(jsonPath("$.data.changes[?(@.field == 'phone')].beforeMasked")
                        .value(org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$.data.changes[?(@.field == 'phone')].beforeDisplay")
                        .value(org.hamcrest.Matchers.hasItem("••••0000")))
                .andExpect(jsonPath("$.data.changes[0].beforeCiphertext").doesNotExist());

        readMvc.perform(get("/personal-space/actions")
                        .header("Fusion-Token", createToken)
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actions.length()").value(1))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextBeforeId").isNotEmpty())
                .andExpect(jsonPath("$.data.actions[0].items[0].beforeCiphertext").doesNotExist())
                .andExpect(jsonPath("$.data.actions[0].items[0].changedFields[0]")
                        .value("currentGoal"));

        Long readActionId = loadActions.get(0).getId();
        UserEntity createOther = new UserEntity();
        createOther.setUsername("history-other-user");
        createOther.setStudentId("history-other-student");
        createOther.setRole(UserRole.NORMAL);
        createOther.setStatus(UserStatus.NORMAL);
        createOther.setCreatedAt(LocalDateTime.now());
        readUsers.save(createOther);
        String createOtherToken = StpUtil.getStpLogic().createLoginSession(createOther.getId());
        try {
            readMvc.perform(get("/personal-space/actions/{actionId}", readActionId)
                            .header("Fusion-Token", createOtherToken))
                    .andExpect(status().isNotFound());
            readMvc.perform(get("/personal-space/actions/{actionId}/confirmation",
                            readProfileChange.getActionId())
                            .header("Fusion-Token", createOtherToken))
                    .andExpect(status().isNotFound());
        } finally {
            StpUtil.logout(createOther.getId());
        }
    }

    @Test
    void revertProfile() throws Exception {
        readMvc.perform(patch("/personal-space/profile")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 0,
                                  "set": {
                                    "major": "新闻传播学",
                                    "gender": "FEMALE",
                                    "birthDate": "2000-01-15",
                                    "intentionCity": ["上海", "杭州"]
                                  },
                                  "clear": ["phone"]
                                }
                                """))
                .andExpect(status().isOk());
        UserChangeActionEntity readOriginal = loadLatestAction();

        readMvc.perform(post("/personal-space/actions/{actionId}/revert", readOriginal.getId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.revertsActionId")
                        .value(readOriginal.getId().toString()));
        UserChangeActionEntity readRevert = loadLatestAction();
        assertThat(readRevert.getId()).isNotEqualTo(readOriginal.getId());
        assertThat(readProfiles.getProfile(createUser.getId()).getMajor())
                .isEqualTo("新闻传播学");

        readMvc.perform(post("/personal-space/actions/{actionId}/confirm", readRevert.getId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPLIED"));
        assertThat(readProfiles.getProfile(createUser.getId()))
                .extracting("major", "phone", "gender", "birthDate", "intentionCity", "version")
                .containsExactly(null, "13800000000", null, null, null, 2L);
        assertThat(readActions.selectById(readOriginal.getId()).getStatus())
                .isEqualTo(ChangeActionStatus.APPLIED);

        readMvc.perform(post("/personal-space/actions/{actionId}/revert", readRevert.getId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk());
        UserChangeActionEntity readRedo = loadLatestAction();
        readMvc.perform(post("/personal-space/actions/{actionId}/confirm", readRedo.getId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk());
        assertThat(readProfiles.getProfile(createUser.getId()))
                .extracting("major", "phone", "gender", "birthDate", "intentionCity", "version")
                .containsExactly(
                        "新闻传播学", null, com.fusioncareer.enums.Gender.FEMALE,
                        LocalDate.of(2000, 1, 15), "[\"上海\", \"杭州\"]", 3L);
    }

    @Test
    void revertMemoryCreate() throws Exception {
        readMvc.perform(put("/personal-space/memory/currentGoal")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"value\":\"寻找媒体实习\"}"))
                .andExpect(status().isOk());
        UserChangeActionEntity readOriginal = loadLatestAction();

        readMvc.perform(post("/personal-space/actions/{actionId}/revert", readOriginal.getId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk());
        UserChangeActionEntity readRevert = loadLatestAction();
        readMvc.perform(post("/personal-space/actions/{actionId}/confirm", readRevert.getId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk());
        assertThat(readMemoryRows.selectById(createUser.getId())).isNull();

        readMvc.perform(post("/personal-space/actions/{actionId}/revert", readRevert.getId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk());
        UserChangeActionEntity readRedo = loadLatestAction();
        readMvc.perform(post("/personal-space/actions/{actionId}/confirm", readRedo.getId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk());
        assertThat(manageMemory.readMemory(createUser.getId()).entries())
                .containsKey("currentGoal");
    }

    @Test
    void revertResume() throws Exception {
        readMvc.perform(patch("/personal-space/resume")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"set\":{\"education\":\"新闻学硕士\"}}"))
                .andExpect(status().isOk());
        UserChangeActionEntity readOriginal = loadLatestAction();
        readMvc.perform(post("/personal-space/actions/{actionId}/revert", readOriginal.getId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk());
        UserChangeActionEntity readRevert = loadLatestAction();
        readMvc.perform(post("/personal-space/actions/{actionId}/confirm", readRevert.getId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk());

        assertThat(readResumes.getResume(createUser.getId()))
                .extracting("education", "version")
                .containsExactly("不应出现在总览中的简历正文", 2L);
    }

    @Test
    void rejectStaleConfirm() throws Exception {
        readMvc.perform(patch("/personal-space/profile")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"set\":{\"major\":\"新闻学\"}}"))
                .andExpect(status().isOk());
        UserChangeActionEntity readOriginal = loadLatestAction();
        readMvc.perform(post("/personal-space/actions/{actionId}/revert", readOriginal.getId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk());
        UserChangeActionEntity readRevert = loadLatestAction();

        readMvc.perform(patch("/personal-space/profile")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"set\":{\"hometown\":\"上海\"}}"))
                .andExpect(status().isOk());
        readMvc.perform(post("/personal-space/actions/{actionId}/confirm", readRevert.getId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isConflict());
        assertThat(readProfiles.getProfile(createUser.getId()))
                .extracting("major", "hometown", "version")
                .containsExactly("新闻学", "上海", 2L);
    }

    @Test
    void rejectRevertConflict() throws Exception {
        readMvc.perform(patch("/personal-space/profile")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"set\":{\"major\":\"新闻学\"}}"))
                .andExpect(status().isOk());
        UserChangeActionEntity readOriginal = loadLatestAction();
        readMvc.perform(patch("/personal-space/profile")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"set\":{\"major\":\"广告学\"}}"))
                .andExpect(status().isOk());

        readMvc.perform(post("/personal-space/actions/{actionId}/revert", readOriginal.getId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
        assertThat(readProfiles.getProfile(createUser.getId()).getMajor()).isEqualTo("广告学");

        UserChangeActionEntity readCurrent = loadLatestAction();
        readMvc.perform(post("/personal-space/actions/{actionId}/revert", readCurrent.getId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk());
        UserChangeActionEntity readRevert = loadLatestAction();
        readMvc.perform(post("/personal-space/actions/{actionId}/reject", readRevert.getId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
        readMvc.perform(post("/personal-space/actions/{actionId}/confirm", readRevert.getId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isConflict());
        assertThat(readProfiles.getProfile(createUser.getId()).getMajor()).isEqualTo("广告学");
    }

    private UserChangeActionEntity loadLatestAction() {
        return readActions.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserChangeActionEntity>()
                        .eq(UserChangeActionEntity::getUserId, createUser.getId())
                        .orderByDesc(UserChangeActionEntity::getId)
                        .last("LIMIT 1"));
    }

    @Test
    void restoreDocument() throws Exception {
        readMvc.perform(delete("/personal-space/documents/{fileId}", createFile.getId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk());
        var readDeleteAction = readChanges.readActions(
                createUser.getId(), null, 1).actions().get(0);
        assertThat(readDeleteAction.status()).isEqualTo(ChangeActionStatus.APPLIED);
        assertThat(readDeleteAction.items()).singleElement().satisfies(readItem -> {
            assertThat(readItem.resourceType()).isEqualTo(ChangeResourceType.RESUME_FILE);
            assertThat(readItem.operation()).isEqualTo(ChangeOperation.SOFT_DELETE);
            assertThat(readItem.changedFields()).containsExactly("deleted");
        });
        readMvc.perform(get("/personal-space/documents").header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files").isEmpty())
                .andExpect(jsonPath("$.data.usedBytes").value("0"));
        readMvc.perform(get("/personal-space/documents/deleted").header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[0].id").value(createFile.getId().toString()))
                .andExpect(jsonPath("$.data.files[0].version").value("1"))
                .andExpect(jsonPath("$.data.files[0].deletedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.files[0].url").doesNotExist());
        readMvc.perform(get("/user/resume/file/{fileId}/download", createFile.getId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(41020));

        readMvc.perform(post("/personal-space/documents/{fileId}/restore", createFile.getId())
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk());
        assertThat(readChanges.readActions(createUser.getId(), null, 1)
                .actions().get(0).items().get(0).operation())
                .isEqualTo(ChangeOperation.RESTORE);
        readMvc.perform(get("/personal-space/documents").header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[0].version").value("2"))
                .andExpect(jsonPath("$.data.usedBytes").value("10"));
    }

    @Test
    void incrementVersions() {
        UserProfileRequest updateProfile = new UserProfileRequest();
        updateProfile.setMajor("新闻传播学");
        readProfiles.saveOrUpdateProfile(createUser.getId(), updateProfile);

        ResumeRequest updateResume = new ResumeRequest();
        updateResume.setSkills("Python");
        readResumes.saveOrUpdateResume(createUser.getId(), updateResume);

        QuestionnaireAnswerEntity readSubmitted = readAnswers.lambdaQuery()
                .eq(QuestionnaireAnswerEntity::getUserId, createUser.getId())
                .eq(QuestionnaireAnswerEntity::getSubmissionStatus, QuestionnaireSubmissionStatus.SUBMITTED)
                .one();
        QuestionnaireReviewRequest updateReview = new QuestionnaireReviewRequest();
        updateReview.setPassed(true);
        updateReview.setComments("版本测试");
        readAnswers.review(readSubmitted.getId(), updateReview, createUser.getId());

        assertThat(readProfiles.getProfile(createUser.getId()).getVersion())
                .isEqualTo(1L);
        assertThat(readResumes.getResume(createUser.getId()).getVersion())
                .isEqualTo(1L);
        assertThat(readAnswers.getDetail(readSubmitted.getId()).getVersion())
                .isEqualTo(1L);
    }

    @Test
    void validateApplicationFile() {
        JobPostRequest createJob = new JobPostRequest();
        createJob.setCompanyName("空间测试公司");
        createJob.setPositionName("空间测试岗位");
        createJob.setJobCategory(JobCategory.MEDIA);
        createJob.setRecruitType(RecruitType.DAILY_INTERNSHIP);
        createJob.setStatus(JobPostStatus.PUBLISHED);
        createJob.setApplicationDeadline(LocalDate.now().plusDays(1));
        Long readJobId = readJobs.createJobPost(createJob).getId();

        QuestionnaireSubmitRequest createDraft = new QuestionnaireSubmitRequest();
        createDraft.setJobPostId(readJobId);
        createDraft.setAnswers("[{\"questionId\":\"0\",\"value\":\"" + createFile.getId() + "\"}]");
        assertThat(readAnswers.saveDraft(createUser.getId(), createDraft).getSubmissionStatus())
                .isEqualTo(QuestionnaireSubmissionStatus.DRAFT);
        assertThat(readChanges.readActions(createUser.getId(), null, 1).actions().get(0).items())
                .singleElement().satisfies(readItem -> {
                    assertThat(readItem.resourceType())
                            .isEqualTo(ChangeResourceType.QUESTIONNAIRE_ANSWER);
                    assertThat(readItem.operation()).isEqualTo(ChangeOperation.CREATE);
                });

        UserEntity createOther = new UserEntity();
        createOther.setUsername("other-file-user");
        createOther.setStudentId("other-file-student");
        createOther.setRole(UserRole.NORMAL);
        createOther.setStatus(UserStatus.NORMAL);
        createOther.setCreatedAt(LocalDateTime.now());
        readUsers.save(createOther);
        ResumeFileResponse createOtherFile = readFiles.upload(createOther.getId(), new MockMultipartFile(
                "file", "other.pdf", "application/pdf", "%PDF-other".getBytes(StandardCharsets.UTF_8)));
        try {
            createDraft.setAnswers("[{\"questionId\":\"0\",\"value\":\""
                    + createOtherFile.getId() + "\"}]");
            assertThatThrownBy(() -> readAnswers.saveDraft(createUser.getId(), createDraft))
                    .hasMessageContaining("文件不存在");
        } finally {
            readFiles.purge(createOther.getId(), createOtherFile.getId());
        }
    }

    private void saveAnswer(Long jobPostId, QuestionnaireSubmissionStatus status) {
        QuestionnaireAnswerEntity createAnswer = new QuestionnaireAnswerEntity();
        createAnswer.setUserId(createUser.getId());
        createAnswer.setJobPostId(jobPostId);
        createAnswer.setAnswers("[]");
        createAnswer.setSubmissionStatus(status);
        createAnswer.setCreatedAt(LocalDateTime.now());
        createAnswer.setUpdatedAt(LocalDateTime.now());
        readAnswers.save(createAnswer);
    }
}
