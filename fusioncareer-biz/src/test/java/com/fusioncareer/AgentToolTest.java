package com.fusioncareer;

import com.fusioncareer.config.AgentContextProperties;
import com.fusioncareer.dto.req.AiMessageRequest;
import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.dto.req.ResumeRequest;
import com.fusioncareer.dto.req.UserProfileRequest;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.enums.JobCategory;
import com.fusioncareer.enums.JobPostStatus;
import com.fusioncareer.enums.RecruitType;
import com.fusioncareer.enums.UserRole;
import com.fusioncareer.enums.UserStatus;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.service.AgentContextService;
import com.fusioncareer.service.AgentToolService;
import com.fusioncareer.service.AiChatService;
import com.fusioncareer.service.JobPostService;
import com.fusioncareer.service.ResumeService;
import com.fusioncareer.service.UserProfileService;
import com.fusioncareer.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
            "job:read", "questionnaire:read", "history:read");

    @Autowired MockMvc readMvc;
    @Autowired UserService readUsers;
    @Autowired UserProfileService readProfiles;
    @Autowired ResumeService readResumes;
    @Autowired AiChatService manageChat;
    @Autowired AgentContextService contextService;
    @Autowired AgentToolService runTools;
    @Autowired AgentContextProperties contextProperties;
    @Autowired JobPostService readJobs;

    private UserEntity createUser;
    private AiChatService.RunStart createRun;
    private String createContext;

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
