package com.fusioncareer;

import com.fusioncareer.config.AgentContextProperties;
import com.fusioncareer.dto.req.AiMessageRequest;
import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.dto.req.JobPostQuestionRequest;
import com.fusioncareer.dto.req.QuestionnaireReviewRequest;
import com.fusioncareer.dto.req.ResumeRequest;
import com.fusioncareer.dto.req.UserProfileRequest;
import com.fusioncareer.dto.res.ResumeFileResponse;
import com.fusioncareer.dto.res.UserChangeActionResponse;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.enums.ChangeActionStatus;
import com.fusioncareer.enums.ChangeOperation;
import com.fusioncareer.enums.ChangeResourceType;
import com.fusioncareer.enums.JobCategory;
import com.fusioncareer.enums.JobPostStatus;
import com.fusioncareer.enums.QuestionType;
import com.fusioncareer.enums.QuestionnaireSubmissionStatus;
import com.fusioncareer.enums.RecruitType;
import com.fusioncareer.enums.UserRole;
import com.fusioncareer.enums.UserStatus;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.service.AgentContextService;
import com.fusioncareer.service.AgentToolService;
import com.fusioncareer.service.AiChatService;
import com.fusioncareer.service.JobPostService;
import com.fusioncareer.service.JobPostQuestionService;
import com.fusioncareer.service.PersonalSpaceService;
import com.fusioncareer.service.QuestionnaireAnswerService;
import com.fusioncareer.service.ResumeService;
import com.fusioncareer.service.ResumeFileService;
import com.fusioncareer.service.UserProfileService;
import com.fusioncareer.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AgentToolTest {

    private static final List<String> READ_SCOPES = List.of(
            "space:read", "account:read", "profile:read", "resume:read",
            "file:read", "memory:read", "application:read", "job:search",
            "job:read", "questionnaire:read", "history:read",
            "profile:propose", "resume:propose", "memory:propose", "file:propose",
            "questionnaire:propose");

    @Autowired MockMvc readMvc;
    @Autowired UserService readUsers;
    @Autowired UserProfileService readProfiles;
    @Autowired ResumeService readResumes;
    @Autowired ResumeFileService readFiles;
    @Autowired AiChatService manageChat;
    @Autowired AgentContextService contextService;
    @Autowired AgentToolService runTools;
    @Autowired AgentContextProperties contextProperties;
    @Autowired JobPostService readJobs;
    @Autowired JobPostQuestionService readQuestions;
    @Autowired QuestionnaireAnswerService manageAnswers;
    @Autowired PersonalSpaceService manageSpace;

    private UserEntity createUser;
    private AiChatService.RunStart createRun;
    private String createContext;
    private final List<Long> cleanupFileIds = new ArrayList<>();

    @BeforeEach
    void createContext() {
        createUser = new UserEntity();
        createUser.setUsername("tool-user");
        createUser.setStudentId("tool-student");
        createUser.setRole(UserRole.NORMAL);
        createUser.setStatus(UserStatus.NORMAL);
        createUser.setCreatedAt(LocalDateTime.now());
        readUsers.save(createUser);

        UserProfileRequest createProfile = new UserProfileRequest();
        createProfile.setRealName("工具用户");
        createProfile.setMajor("新闻学");
        readProfiles.saveOrUpdateProfile(createUser.getId(), createProfile);
        ResumeRequest createResume = new ResumeRequest();
        createResume.setSkills("Java");
        readResumes.saveOrUpdateResume(createUser.getId(), createResume);

        createRun = manageChat.startRun(
                createUser.getId(), new AiMessageRequest("tool-request", "读取我的资料", List.of()));
        createContext = contextService.issueContext(
                createUser.getId(), createRun.assistantMessage().runId(),
                createRun.session().epoch(), createRun.userMessage().requestId(), READ_SCOPES);
    }

    @AfterEach
    void cleanupFiles() {
        for (Long readFileId : cleanupFileIds) {
            try {
                readFiles.purge(createUser.getId(), readFileId);
            } catch (RuntimeException ignored) {
                // 测试断言失败时也尽量清理磁盘文件。
            }
        }
        cleanupFileIds.clear();
    }

    @Test
    void readOwnResources() throws Exception {
        Object readProfile = runTools.executeTool(createContext, "get_my_profile", Map.of());
        assertThat(readProfile).extracting("userId", "realName", "major")
                .containsExactly(createUser.getId(), "工具用户", "新闻学");
        Object readResume = runTools.executeTool(createContext, "get_my_resume", Map.of());
        assertThat(readResume).extracting("userId", "skills")
                .containsExactly(createUser.getId(), "Java");

        readMvc.perform(post("/internal/agent/tools/get_my_profile")
                        .header("X-Internal-Token", "test-internal")
                        .header("X-Agent-Context", createContext)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(createUser.getId().toString()))
                .andExpect(jsonPath("$.data.major").value("新闻学"));
        readMvc.perform(post("/internal/agent/tools/get_my_profile")
                        .header("X-Agent-Context", createContext)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        assertThatThrownBy(() -> runTools.executeTool(
                createContext, "get_my_profile", Map.of("userId", "1")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不允许");
    }

    @Test
    void rejectInvalidContexts() {
        String readProfileOnly = contextService.issueContext(
                createUser.getId(), createRun.assistantMessage().runId(),
                createRun.session().epoch(), createRun.userMessage().requestId(),
                List.of("profile:read"));
        assertThatThrownBy(() -> runTools.executeTool(
                readProfileOnly, "get_my_resume", Map.of()))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("AgentContext");
        assertThatThrownBy(() -> runTools.executeTool(
                readProfileOnly,
                "propose_profile_patch",
                Map.of(
                        "changes", List.of(Map.of(
                                "field", "major", "operation", "SET", "value", "法学")),
                        "reason", "缺少写 scope"),
                "call-no-write-scope"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("AgentContext");
        assertThatThrownBy(() -> runTools.executeTool(
                createContext + "x", "get_my_profile", Map.of()))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("AgentContext");

        String readWrongRun = contextService.issueContext(
                createUser.getId(), "00000000-0000-0000-0000-000000000000",
                createRun.session().epoch(), createRun.userMessage().requestId(), READ_SCOPES);
        assertThatThrownBy(() -> runTools.executeTool(
                readWrongRun, "get_my_profile", Map.of()))
                .isInstanceOf(ServiceException.class);

        manageChat.clearSession(createUser.getId());
        assertThatThrownBy(() -> runTools.executeTool(
                createContext, "get_my_profile", Map.of()))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void rejectExpiredContext() {
        long readTtl = contextProperties.getTtlSeconds();
        try {
            contextProperties.setTtlSeconds(-1);
            String readExpired = contextService.issueContext(
                    createUser.getId(), createRun.assistantMessage().runId(),
                    createRun.session().epoch(), createRun.userMessage().requestId(), READ_SCOPES);
            assertThatThrownBy(() -> runTools.executeTool(
                    readExpired, "get_my_profile", Map.of()))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("AgentContext");
        } finally {
            contextProperties.setTtlSeconds(readTtl);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchVisibleJobs() {
        JobPostRequest createPublished = createJob("可见岗位", JobPostStatus.PUBLISHED);
        Long readPublishedId = readJobs.createJobPost(createPublished).getId();
        JobPostRequest createOffline = createJob("隐藏岗位", JobPostStatus.OFFLINE);
        Long readOfflineId = readJobs.createJobPost(createOffline).getId();

        Map<String, Object> readResult = (Map<String, Object>) runTools.executeTool(
                createContext, "search_jobs", Map.of("keyword", "岗位", "size", 10));
        List<Map<String, Object>> readJobList = (List<Map<String, Object>>) readResult.get("jobs");
        assertThat(readJobList).extracting(readJob -> readJob.get("id"))
                .contains(readPublishedId.toString())
                .doesNotContain(readOfflineId.toString());
        assertThat(readJobList.get(0))
                .doesNotContainKeys("createdBy", "recycleReason", "recycledAt");
        assertThatThrownBy(() -> runTools.executeTool(
                createContext, "get_job", Map.of("jobId", readOfflineId.toString())))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不可见");
    }

    @Test
    @SuppressWarnings("unchecked")
    void proposeProfileRequiresUserConfirmationAndSupportsRevert() {
        Map<String, Object> createArgs = Map.of(
                "changes", List.of(
                        Map.of("field", "major", "operation", "SET", "value", "人工智能"),
                        Map.of("field", "phone", "operation", "SET", "value", "13800138000")),
                "reason", "按用户要求更新求职资料");

        Map<String, Object> readProposal = (Map<String, Object>) runTools.executeTool(
                createContext, "propose_profile_patch", createArgs, "call-profile-1");
        Long readActionId = Long.valueOf(String.valueOf(readProposal.get("actionId")));
        assertThat(readProposal)
                .containsEntry("status", ChangeActionStatus.PENDING)
                .containsEntry("requiresConfirmation", true);
        assertThat(readProfiles.getProfile(createUser.getId()).getMajor()).isEqualTo("新闻学");

        Map<String, Object> readRetry = (Map<String, Object>) runTools.executeTool(
                createContext, "propose_profile_patch", createArgs, "call-profile-1");
        assertThat(String.valueOf(readRetry.get("actionId")))
                .isEqualTo(readActionId.toString());
        assertThatThrownBy(() -> runTools.executeTool(
                createContext,
                "propose_profile_patch",
                Map.of(
                        "changes", List.of(Map.of(
                                "field", "major", "operation", "SET", "value", "社会学")),
                        "reason", "不同参数"),
                "call-profile-1"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("幂等键");

        UserChangeActionResponse readApplied = manageSpace.confirmAction(
                createUser.getId(), readActionId);
        assertThat(readApplied.status()).isEqualTo(ChangeActionStatus.APPLIED);
        assertThat(readProfiles.getProfile(createUser.getId()).getMajor()).isEqualTo("人工智能");
        assertThat(readProfiles.getProfile(createUser.getId()).getPhone()).isEqualTo("13800138000");

        UserChangeActionResponse readRevert = manageSpace.createRevert(
                createUser.getId(), readActionId);
        manageSpace.confirmAction(createUser.getId(), readRevert.id());
        assertThat(readProfiles.getProfile(createUser.getId()).getMajor()).isEqualTo("新闻学");
        assertThat(readProfiles.getProfile(createUser.getId()).getPhone()).isNull();

        assertThatThrownBy(() -> runTools.executeTool(
                createContext, "confirm_action", Map.of("actionId", readActionId), "call-confirm"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @SuppressWarnings("unchecked")
    void proposeResumeAndMemoryStayPendingUntilConfirmed() {
        Map<String, Object> readResumeProposal = (Map<String, Object>) runTools.executeTool(
                createContext,
                "propose_resume_patch",
                Map.of(
                        "changes", List.of(Map.of(
                                "field", "skills", "operation", "SET", "value", "Java, Python")),
                        "reason", "补充技能"),
                "call-resume-1");
        assertThat(readResumes.getResume(createUser.getId()).getSkills()).isEqualTo("Java");
        manageSpace.confirmAction(createUser.getId(), Long.valueOf(
                String.valueOf(readResumeProposal.get("actionId"))));
        assertThat(readResumes.getResume(createUser.getId()).getSkills()).isEqualTo("Java, Python");

        Map<String, Object> readMemoryProposal = (Map<String, Object>) runTools.executeTool(
                createContext,
                "propose_memory_patch",
                Map.of(
                        "changes", List.of(Map.of(
                                "field", "targetCities",
                                "operation", "SET",
                                "value", List.of("上海", "杭州"))),
                        "reason", "记住目标城市"),
                "call-memory-1");
        assertThat(manageSpace.readMemory(createUser.getId()).entries()).isEmpty();
        manageSpace.confirmAction(createUser.getId(), Long.valueOf(
                String.valueOf(readMemoryProposal.get("actionId"))));
        assertThat(manageSpace.readMemory(createUser.getId()).entries().get("targetCities").value())
                .isEqualTo(List.of("上海", "杭州"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectPendingProposalWhenAgentRunFails() {
        Map<String, Object> readProposal = (Map<String, Object>) runTools.executeTool(
                createContext,
                "propose_profile_patch",
                Map.of(
                        "changes", List.of(Map.of(
                                "field", "major", "operation", "SET", "value", "法学")),
                        "reason", "运行失败前创建的提案"),
                "call-profile-failed");
        Long readActionId = Long.valueOf(String.valueOf(readProposal.get("actionId")));

        assertThat(manageChat.failRun(
                createUser.getId(),
                createRun.session().epoch(),
                createRun.assistantMessage().runId(),
                "TEST_FAILURE")).isTrue();
        assertThat(manageSpace.readAction(createUser.getId(), readActionId).status())
                .isEqualTo(ChangeActionStatus.REJECTED);
        assertThatThrownBy(() -> manageSpace.confirmAction(createUser.getId(), readActionId))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不可确认");
        assertThat(readProfiles.getProfile(createUser.getId()).getMajor()).isEqualTo("新闻学");
    }

    @Test
    @SuppressWarnings("unchecked")
    void proposeFileDeleteKeepsBlobAndSupportsRevert() {
        ResumeFileResponse createFile = readFiles.upload(
                createUser.getId(),
                new MockMultipartFile(
                        "file",
                        "agent-tool.pdf",
                        "application/pdf",
                        "%PDF-agent-tool".getBytes(StandardCharsets.UTF_8)));
        try {
            Map<String, Object> readProposal = (Map<String, Object>) runTools.executeTool(
                    createContext,
                    "propose_file_delete",
                    Map.of(
                            "fileId", createFile.getId().toString(),
                            "reason", "删除不再使用的简历文件"),
                    "call-file-delete-1");
            Long readActionId = Long.valueOf(String.valueOf(readProposal.get("actionId")));
            assertThat(readProposal)
                    .containsEntry("status", ChangeActionStatus.PENDING)
                    .containsEntry("resourceType", ChangeResourceType.RESUME_FILE);
            assertThat(readFiles.getOwnFile(createUser.getId(), createFile.getId()))
                    .extracting("deletedAt").isNull();

            manageSpace.confirmAction(createUser.getId(), readActionId);
            assertThat(readFiles.getOwnFileIncludingDeleted(
                    createUser.getId(), createFile.getId()).getDeletedAt()).isNotNull();
            assertThat(readFiles.loadAsResource(
                    readFiles.getOwnFileIncludingDeleted(
                            createUser.getId(), createFile.getId()).getStoragePath()).exists())
                    .isTrue();

            UserChangeActionResponse readRevert = manageSpace.createRevert(
                    createUser.getId(), readActionId);
            assertThat(readRevert.items()).singleElement()
                    .extracting(UserChangeActionResponse.Item::operation)
                    .isEqualTo(ChangeOperation.RESTORE);
            manageSpace.confirmAction(createUser.getId(), readRevert.id());
            assertThat(readFiles.getOwnFile(createUser.getId(), createFile.getId()).getDeletedAt())
                    .isNull();

            UserChangeActionResponse readReapply = manageSpace.createRevert(
                    createUser.getId(), readRevert.id());
            assertThat(readReapply.items()).singleElement()
                    .extracting(UserChangeActionResponse.Item::operation)
                    .isEqualTo(ChangeOperation.SOFT_DELETE);
            manageSpace.confirmAction(createUser.getId(), readReapply.id());
            assertThat(readFiles.getOwnFileIncludingDeleted(
                    createUser.getId(), createFile.getId()).getDeletedAt()).isNotNull();
        } finally {
            readFiles.purge(createUser.getId(), createFile.getId());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void proposeQuestionnaireDraftSubmitAndReviewedWithdrawal() {
        Long readJobId = readJobs.createJobPost(
                createJob("问卷提案岗位", JobPostStatus.PUBLISHED)).getId();
        JobPostQuestionRequest createQuestion = new JobPostQuestionRequest();
        createQuestion.setSortOrder(1);
        createQuestion.setTitle("自我介绍");
        createQuestion.setQuestionType(QuestionType.TEXT);
        createQuestion.setRequired(true);
        Long readQuestionId = readQuestions.saveQuestions(
                readJobId, List.of(createQuestion)).get(0).getId();
        ResumeFileResponse createAttachment = readFiles.upload(
                createUser.getId(),
                new MockMultipartFile(
                        "file",
                        "questionnaire.pdf",
                        "application/pdf",
                        "%PDF-questionnaire".getBytes(StandardCharsets.UTF_8)));
        cleanupFileIds.add(createAttachment.getId());

        List<Map<String, Object>> createDraftAnswers = List.of(
                Map.of("questionId", readQuestionId.toString(), "value", "草稿回答"),
                Map.of(
                        "questionId", "0",
                        "value", Map.of("fileId", createAttachment.getId().toString())));

        Map<String, Object> readDraftProposal = (Map<String, Object>) runTools.executeTool(
                createContext,
                "propose_questionnaire_draft",
                Map.of(
                        "jobPostId", readJobId.toString(),
                        "answers", createDraftAnswers,
                        "reason", "保存问卷草稿"),
                "call-questionnaire-draft");
        Long readDraftActionId = Long.valueOf(
                String.valueOf(readDraftProposal.get("actionId")));
        assertThat(manageAnswers.getByUserAndJobPost(createUser.getId(), readJobId)).isNull();

        manageSpace.confirmAction(createUser.getId(), readDraftActionId);
        assertThat(manageAnswers.getByUserAndJobPost(createUser.getId(), readJobId))
                .satisfies(readAnswer -> {
                    assertThat(readAnswer.getSubmissionStatus())
                            .isEqualTo(QuestionnaireSubmissionStatus.DRAFT);
                    assertThat(readAnswer.getAnswers()).contains("草稿回答");
                });

        UserChangeActionResponse readDraftRevert = manageSpace.createRevert(
                createUser.getId(), readDraftActionId);
        manageSpace.confirmAction(createUser.getId(), readDraftRevert.id());
        assertThat(manageAnswers.getByUserAndJobPost(createUser.getId(), readJobId)).isNull();
        assertThat(manageAnswers.getOwnAnswerIncludingDeleted(
                createUser.getId(), readJobId).getDeletedAt()).isNotNull();
        UserChangeActionResponse readRestoreDraft = manageSpace.createRevert(
                createUser.getId(), readDraftRevert.id());
        manageSpace.confirmAction(createUser.getId(), readRestoreDraft.id());
        assertThat(manageAnswers.getByUserAndJobPost(
                createUser.getId(), readJobId).getSubmissionStatus())
                .isEqualTo(QuestionnaireSubmissionStatus.DRAFT);

        List<Map<String, Object>> createSubmitAnswers = List.of(
                Map.of("questionId", readQuestionId.toString(), "value", "正式回答"),
                Map.of(
                        "questionId", "0",
                        "value", Map.of("fileId", createAttachment.getId().toString())));
        Map<String, Object> createSubmitArgs = Map.of(
                "jobPostId", readJobId.toString(),
                "answers", createSubmitAnswers,
                "reason", "正式提交问卷");
        Map<String, Object> readSubmitProposal = (Map<String, Object>) runTools.executeTool(
                createContext,
                "propose_questionnaire_submit",
                createSubmitArgs,
                "call-questionnaire-submit-1");
        Long readSubmitActionId = Long.valueOf(
                String.valueOf(readSubmitProposal.get("actionId")));
        assertThat(manageAnswers.getByUserAndJobPost(
                createUser.getId(), readJobId).getSubmissionStatus())
                .isEqualTo(QuestionnaireSubmissionStatus.DRAFT);
        manageSpace.confirmAction(createUser.getId(), readSubmitActionId);
        assertThat(manageAnswers.getByUserAndJobPost(
                createUser.getId(), readJobId).getSubmissionStatus())
                .isEqualTo(QuestionnaireSubmissionStatus.SUBMITTED);

        UserChangeActionResponse readSubmitRevert = manageSpace.createRevert(
                createUser.getId(), readSubmitActionId);
        manageSpace.confirmAction(createUser.getId(), readSubmitRevert.id());
        assertThat(manageAnswers.getByUserAndJobPost(createUser.getId(), readJobId))
                .satisfies(readAnswer -> {
                    assertThat(readAnswer.getSubmissionStatus())
                            .isEqualTo(QuestionnaireSubmissionStatus.DRAFT);
                    assertThat(readAnswer.getAnswers()).contains("草稿回答");
                });

        Map<String, Object> readResubmitProposal = (Map<String, Object>) runTools.executeTool(
                createContext,
                "propose_questionnaire_submit",
                createSubmitArgs,
                "call-questionnaire-submit-2");
        Long readResubmitActionId = Long.valueOf(
                String.valueOf(readResubmitProposal.get("actionId")));
        manageSpace.confirmAction(createUser.getId(), readResubmitActionId);
        var readSubmitted = manageAnswers.getByUserAndJobPost(createUser.getId(), readJobId);
        QuestionnaireReviewRequest createReview = new QuestionnaireReviewRequest();
        createReview.setPassed(true);
        createReview.setComments("审核记录必须保留");
        manageAnswers.review(readSubmitted.getId(), createReview, createUser.getId());

        UserChangeActionResponse readWithdrawal = manageSpace.createRevert(
                createUser.getId(), readResubmitActionId);
        assertThat(readWithdrawal.items()).singleElement()
                .extracting(UserChangeActionResponse.Item::operation)
                .isEqualTo(ChangeOperation.STATE_TRANSITION);
        manageSpace.confirmAction(createUser.getId(), readWithdrawal.id());
        assertThat(manageAnswers.getByUserAndJobPost(createUser.getId(), readJobId))
                .satisfies(readAnswer -> {
                    assertThat(readAnswer.getSubmissionStatus())
                            .isEqualTo(QuestionnaireSubmissionStatus.WITHDRAWN);
                    assertThat(readAnswer.getReviewComments()).isEqualTo("审核记录必须保留");
                    assertThat(readAnswer.getReviewPassed()).isTrue();
                });

        UserChangeActionResponse readRestoreReview = manageSpace.createRevert(
                createUser.getId(), readWithdrawal.id());
        manageSpace.confirmAction(createUser.getId(), readRestoreReview.id());
        assertThat(manageAnswers.getByUserAndJobPost(
                createUser.getId(), readJobId).getSubmissionStatus())
                .isEqualTo(QuestionnaireSubmissionStatus.REVIEWED);
    }

    private JobPostRequest createJob(String createName, JobPostStatus createStatus) {
        JobPostRequest createJob = new JobPostRequest();
        createJob.setCompanyName("工具测试公司");
        createJob.setPositionName(createName);
        createJob.setJobCategory(JobCategory.MEDIA);
        createJob.setRecruitType(RecruitType.DAILY_INTERNSHIP);
        createJob.setStatus(createStatus);
        createJob.setApplicationDeadline(LocalDate.now().plusDays(10));
        return createJob;
    }
}
