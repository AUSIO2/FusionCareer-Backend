package com.fusioncareer;

import cn.dev33.satoken.stp.StpUtil;
import com.fusioncareer.dto.req.AiMessageRequest;
import com.fusioncareer.dto.req.AiInteraction;
import com.fusioncareer.dto.req.UserMemoryUpdateRequest;
import com.fusioncareer.dto.res.ResumeFileResponse;
import com.fusioncareer.entity.AiMessageEntity;
import com.fusioncareer.entity.AiSessionEntity;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.entity.UserChangeActionEntity;
import com.fusioncareer.entity.JobPostEntity;
import com.fusioncareer.entity.ResumeEntity;
import com.fusioncareer.entity.UserProfileEntity;
import com.fusioncareer.enums.AiMessageStatus;
import com.fusioncareer.enums.ChangeActionStatus;
import com.fusioncareer.enums.ChangeActionType;
import com.fusioncareer.enums.ChangeActorType;
import com.fusioncareer.enums.UserRole;
import com.fusioncareer.enums.UserStatus;
import com.fusioncareer.mapper.AiMessageMapper;
import com.fusioncareer.mapper.AiSessionMapper;
import com.fusioncareer.mapper.UserChangeActionMapper;
import com.fusioncareer.service.AiChatService;
import com.fusioncareer.service.ResumeFileService;
import com.fusioncareer.service.UserMemoryService;
import com.fusioncareer.service.UserService;
import com.fusioncareer.service.JobPostService;
import com.fusioncareer.service.ResumeService;
import com.fusioncareer.service.UserProfileService;
import com.fusioncareer.client.AgentStreamClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AiChatTest {

    @Autowired MockMvc readMvc;
    @Autowired AiChatService manageChat;
    @Autowired AiSessionMapper readSessions;
    @Autowired AiMessageMapper readMessages;
    @Autowired UserService readUsers;
    @Autowired UserMemoryService manageMemory;
    @Autowired ResumeFileService readFiles;
    @Autowired ObjectMapper readMapper;
    @Autowired UserChangeActionMapper readActions;
    @Autowired com.fusioncareer.service.PersonalSpaceRevertService manageActions;
    @Autowired JobPostService adviceJobs;
    @Autowired ResumeService adviceResumes;
    @Autowired UserProfileService adviceProfiles;
    @Autowired com.fusioncareer.service.AgentContextService quotaContexts;

    @MockBean AgentStreamClient streamClient;

    private UserEntity createUser;
    private String createToken;
    private ResumeFileResponse createFile;
    private ResumeFileResponse createOtherFile;
    private UserEntity createOther;

    @BeforeEach
    void createUser() {
        createUser = saveUser("ai-user", "ai-student");
        createToken = StpUtil.getStpLogic().createLoginSession(createUser.getId());
    }

    @AfterEach
    void clearFiles() {
        if (createFile != null) {
            readFiles.purge(createUser.getId(), createFile.getId());
        }
        if (createOtherFile != null) {
            readFiles.purge(createOther.getId(), createOtherFile.getId());
        }
        StpUtil.logout(createUser.getId());
    }

    @Test
    void readEmptySession() throws Exception {
        readMvc.perform(get("/personal-space/assistant/capabilities")
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.writeEnabled").value(true))
                .andExpect(jsonPath("$.data.readTools.length()").value(18))
                .andExpect(jsonPath("$.data.readTools").value(
                        org.hamcrest.Matchers.hasItem("analyze_resume_file")))
                .andExpect(jsonPath("$.data.writeTools.length()").value(8))
                .andExpect(jsonPath("$.data.limits.maxMessageChars").value(8000))
                .andExpect(jsonPath("$.data.limits.dailyMessageLimit").value(100));
        readMvc.perform(get("/personal-space/assistant/session")
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exists").value(false));
        readMvc.perform(get("/personal-space/assistant/messages")
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages").isEmpty());
        readMvc.perform(get("/personal-space/assistant/session"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void runMessages() throws Exception {
        AiChatService.RunStart createRun = manageChat.startRun(
                createUser.getId(), new AiMessageRequest("request-1", "  你好  ", List.of()));
        assertThat(createRun.reused()).isFalse();
        assertThat(createRun.session().epoch()).isEqualTo(1L);
        assertThat(createRun.session().activeRun()).isTrue();
        assertThat(createRun.userMessage().content()).isEqualTo("你好");
        assertThat(createRun.assistantMessage().status()).isEqualTo(AiMessageStatus.PENDING);
        assertThatThrownBy(() -> manageChat.startRun(
                createUser.getId(), new AiMessageRequest("request-2", "第二条", List.of())))
                .hasMessageContaining("正在运行");

        assertThat(manageChat.markStreaming(
                createUser.getId(), 1L, createRun.assistantMessage().runId())).isTrue();
        assertThat(manageChat.completeRun(
                createUser.getId(), 1L, createRun.assistantMessage().runId(),
                "你好，需要我帮你做什么？", "fake-model", "stop", 10, 8)).isTrue();

        AiChatService.RunStart readRetry = manageChat.startRun(
                createUser.getId(), new AiMessageRequest("request-1", "你好", List.of()));
        assertThat(readRetry.reused()).isTrue();
        assertThat(readRetry.assistantMessage().content()).isEqualTo("你好，需要我帮你做什么？");
        assertThatThrownBy(() -> manageChat.startRun(
                createUser.getId(), new AiMessageRequest("request-1", "不同正文", List.of())))
                .hasMessageContaining("不同消息");

        readMvc.perform(get("/personal-space/assistant/messages")
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.messages[0].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.messages[0].status").value("COMPLETED"));
        assertThat(readMessages.selectCount(null)).isEqualTo(2L);
        assertThat(readSessions.selectById(createUser.getId()).getActiveRunId()).isNull();
    }

    @Test
    void dailyQuotaIsPerUserAndIdempotentRetriesDoNotConsumeAgain() {
        AiChatService.RunStart first = manageChat.startRun(
                createUser.getId(), new AiMessageRequest("quota-first", "找实习", List.of()));
        manageChat.completeRun(createUser.getId(), first.session().epoch(),
                first.assistantMessage().runId(), "好的", "fake", "stop", 1, 1);
        AiSessionEntity session = readSessions.selectById(createUser.getId());
        session.setUsageDate(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        session.setDailyUsageCount(100);
        readSessions.updateById(session);

        assertThat(manageChat.startRun(createUser.getId(),
                new AiMessageRequest("quota-first", "找实习", List.of())).reused()).isTrue();
        assertThatThrownBy(() -> manageChat.startRun(createUser.getId(),
                new AiMessageRequest("quota-over", "再找岗位", List.of())))
                .hasMessageContaining("100 次");

        createOther = saveUser("quota-other", "quota-other-student");
        AiChatService.RunStart other = manageChat.startRun(
                createOther.getId(), new AiMessageRequest("quota-other", "找实习", List.of()));
        assertThat(other.reused()).isFalse();
    }

    @Test
    void thirdIrrelevantMessageStartsExponentialBan() {
        for (int index = 1; index <= 3; index++) {
            AiChatService.RunStart run = manageChat.startRun(createUser.getId(),
                    new AiMessageRequest("irrelevant-" + index, "无关消息", List.of()));
            AiChatService.TopicGuardState state = manageChat.recordTopicDecision(
                    createUser.getId(), run.session().epoch(), run.assistantMessage().runId(), false);
            assertThat(state.strikes()).isEqualTo(index);
            if (index < 3) assertThat(state.bannedUntil()).isNull();
            manageChat.completeRun(createUser.getId(), run.session().epoch(),
                    run.assistantMessage().runId(), "请询问就业相关问题", "small", "topic_rejected", 2, 1);
        }
        AiSessionEntity session = readSessions.selectById(createUser.getId());
        assertThat(session.getAiBannedUntil()).isAfter(LocalDateTime.now());
        assertThatThrownBy(() -> manageChat.startRun(createUser.getId(),
                new AiMessageRequest("irrelevant-4", "继续闲聊", List.of())))
                .hasMessageContaining("临时限制");
    }

    @Test
    void contextNeverIncludesAnotherUsersMessages() {
        createOther = saveUser("context-other", "context-other-student");
        AiChatService.RunStart mine = manageChat.startRun(createUser.getId(),
                new AiMessageRequest("context-mine", "我的岗位偏好", List.of()));
        manageChat.completeRun(createUser.getId(), mine.session().epoch(),
                mine.assistantMessage().runId(), "我的回答", "fake", "stop", 1, 1);
        AiChatService.RunStart other = manageChat.startRun(createOther.getId(),
                new AiMessageRequest("context-other", "另一个用户的秘密", List.of()));
        manageChat.completeRun(createOther.getId(), other.session().epoch(),
                other.assistantMessage().runId(), "另一个回答", "fake", "stop", 1, 1);

        AiChatService.ChatContext context = manageChat.readContext(
                createUser.getId(), mine.session().epoch(), Long.MAX_VALUE);
        assertThat(context.history()).extracting(AiChatService.ContextMessage::content)
                .contains("我的岗位偏好", "我的回答")
                .doesNotContain("另一个用户的秘密", "另一个回答");
    }

    @Test
    void clearSession() throws Exception {
        AiChatService.RunStart createRun = manageChat.startRun(
                createUser.getId(), new AiMessageRequest("request-clear", "即将清空", List.of()));
        Long readAssistantId = createRun.assistantMessage().id();

        readMvc.perform(post("/personal-space/assistant/session/clear")
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.epoch").value("2"))
                .andExpect(jsonPath("$.data.activeRun").value(false));
        assertThat(manageChat.completeRun(
                createUser.getId(), 1L, createRun.assistantMessage().runId(),
                "迟到内容", "fake-model", "stop", null, null)).isFalse();
        assertThat(readMessages.selectById(readAssistantId).getStatus())
                .isEqualTo(AiMessageStatus.CANCELLED);
        assertThat(manageChat.readMessages(createUser.getId(), null, 20).messages()).isEmpty();
    }

    @Test
    void cancelRun() throws Exception {
        AiChatService.RunStart createRun = manageChat.startRun(
                createUser.getId(), new AiMessageRequest("request-cancel", "取消我", List.of()));
        readMvc.perform(post("/personal-space/assistant/run/cancel")
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeRun").value(false));
        assertThat(readMessages.selectById(createRun.assistantMessage().id()).getStatus())
                .isEqualTo(AiMessageStatus.CANCELLED);
        assertThat(manageChat.completeRun(
                createUser.getId(), 1L, createRun.assistantMessage().runId(),
                "不应写入", "fake-model", "stop", null, null)).isFalse();
    }

    @Test
    void recoverExpiredRun() {
        AiChatService.RunStart createRun = manageChat.startRun(
                createUser.getId(), new AiMessageRequest("request-expired", "超时", List.of()));
        com.fusioncareer.entity.AiSessionEntity updateSession =
                readSessions.selectById(createUser.getId());
        updateSession.setLeaseUntil(LocalDateTime.now().minusSeconds(1));
        readSessions.updateById(updateSession);

        assertThat(manageChat.recoverExpiredRun(
                createUser.getId(), 1L, createRun.assistantMessage().runId())).isTrue();
        assertThat(manageChat.recoverExpiredRun(
                createUser.getId(), 1L, createRun.assistantMessage().runId())).isFalse();
        assertThat(readSessions.selectById(createUser.getId()).getActiveRunId()).isNull();

        AiChatService.RunStart readExpired = manageChat.startRun(
                createUser.getId(), new AiMessageRequest("request-expired", "超时", List.of()));
        assertThat(readExpired.reused()).isTrue();
        assertThat(readExpired.assistantMessage().status()).isEqualTo(AiMessageStatus.FAILED);
        assertThat(readExpired.assistantMessage().errorCode()).isEqualTo("LEASE_EXPIRED");

        AiChatService.RunStart createNext = manageChat.startRun(
                createUser.getId(), new AiMessageRequest("request-next", "下一轮", List.of()));
        assertThat(createNext.session().activeRun()).isTrue();
        manageChat.cancelRun(createUser.getId());
        assertThat(createRun.assistantMessage().runId())
                .isNotEqualTo(createNext.assistantMessage().runId());
    }

    @Test
    void summarizeLongConversationWithCursorCas() {
        AiChatService.RunStart createRun = manageChat.startRun(
                createUser.getId(), new AiMessageRequest("summary-first", "第一条", List.of()));
        manageChat.completeRun(
                createUser.getId(), 1L, createRun.assistantMessage().runId(),
                "已创建待确认提案", "fake-model", "stop", null, null);
        UserChangeActionEntity createAction = saveAction(
                createUser.getId(), 1L, ChangeActionStatus.PENDING, LocalDateTime.now());
        for (int readIndex = 0; readIndex < 18; readIndex++) {
            AiMessageEntity createMessage = new AiMessageEntity();
            createMessage.setUserId(createUser.getId());
            createMessage.setEpoch(1L);
            createMessage.setRunId("summary-run-" + readIndex);
            createMessage.setRequestId("summary-request-" + readIndex);
            createMessage.setRole(readIndex % 2 == 0
                    ? com.fusioncareer.enums.AiMessageRole.USER
                    : com.fusioncareer.enums.AiMessageRole.ASSISTANT);
            createMessage.setStatus(AiMessageStatus.COMPLETED);
            createMessage.setContent("摘要消息 " + readIndex);
            createMessage.setAttachmentIds("[]");
            readMessages.insert(createMessage);
        }

        AiChatService.SummaryWork readWork = manageChat.prepareSummary(createUser.getId(), 1L);
        assertThat(readWork).isNotNull();
        assertThat(readWork.messages()).hasSize(14);
        // A summary started before the user rejects a card may finish afterwards.
        manageActions.rejectAction(createUser.getId(), createAction.getId());
        assertThat(manageChat.updateSummary(readWork, "用户正在寻找媒体实习，提案待确认")).isTrue();
        assertThat(manageChat.updateSummary(readWork, "不应覆盖")).isFalse();
        assertThat(readSessions.selectById(createUser.getId()).getSummary())
                .isEqualTo("用户正在寻找媒体实习，提案待确认");

        AiChatService.ChatContext readContext = manageChat.readContext(
                createUser.getId(), 1L, Long.MAX_VALUE);
        assertThat(readContext.summary()).isEqualTo("用户正在寻找媒体实习，提案待确认");
        assertThat(readContext.history()).hasSize(6);
        assertThat(readContext.actionStates()).containsExactly(new AiChatService.ActionState(
                createAction.getId().toString(), "REJECTED", false, "propose_profile_patch"));
        assertThat(readMessages.selectById(createRun.assistantMessage().id()).getContent())
                .isEqualTo("已创建待确认提案");
    }

    @Test
    void contextActionStatesUseCurrentStatusWithBoundedSafeProjection() throws Exception {
        manageChat.startRun(createUser.getId(),
                new AiMessageRequest("action-states", "查看提案状态", List.of()));
        LocalDateTime readNow = LocalDateTime.now();
        for (int readIndex = 0; readIndex < 101; readIndex++) {
            saveAction(createUser.getId(), 1L, ChangeActionStatus.PENDING,
                    readNow.minusDays(1).minusSeconds(readIndex));
        }
        List<UserChangeActionEntity> createRecent = new java.util.ArrayList<>();
        for (ChangeActionStatus readStatus : ChangeActionStatus.values()) {
            createRecent.add(saveAction(createUser.getId(), 1L, readStatus, readNow));
        }
        List<AiChatService.ActionState> readStates = manageChat.readContext(
                createUser.getId(), 1L, Long.MAX_VALUE).actionStates();
        assertThat(readStates).hasSize(100);
        java.util.Collections.reverse(createRecent);
        assertThat(readStates.subList(0, createRecent.size()).stream()
                .map(AiChatService.ActionState::actionId)).containsExactlyElementsOf(
                        createRecent.stream().map(readAction -> readAction.getId().toString()).toList());
        for (AiChatService.ActionState readState : readStates) {
            assertThat(readState.requiresConfirmation()).isEqualTo("PENDING".equals(readState.status()));
        }
        var readJson = readMapper.valueToTree(readStates.get(0));
        List<String> readFields = new java.util.ArrayList<>();
        readJson.fieldNames().forEachRemaining(readFields::add);
        assertThat(readFields).containsExactlyInAnyOrder(
                "actionId", "status", "requiresConfirmation", "toolName");
        assertThat(readJson.path("actionId").isTextual()).isTrue();
        assertThat(readMapper.writeValueAsString(readStates)).doesNotContain("private-action-reason");
    }

    @Test
    void contextActionStatesAreIsolatedByUserAndCurrentEpoch() {
        manageChat.startRun(createUser.getId(),
                new AiMessageRequest("action-isolation", "查看状态", List.of()));
        createOther = saveUser("ai-action-other", "ai-action-other-student");
        saveAction(createOther.getId(), 1L, ChangeActionStatus.REJECTED, LocalDateTime.now());
        saveAction(createUser.getId(), 0L, ChangeActionStatus.REJECTED, LocalDateTime.now());
        UserChangeActionEntity createCurrent = saveAction(
                createUser.getId(), 1L, ChangeActionStatus.PENDING, LocalDateTime.now());
        assertThat(manageChat.readContext(createUser.getId(), 1L, Long.MAX_VALUE).actionStates())
                .extracting(AiChatService.ActionState::actionId)
                .containsExactly(createCurrent.getId().toString());
        assertThat(manageChat.readContext(createUser.getId(), 0L, Long.MAX_VALUE).actionStates()).isEmpty();
        assertThat(manageChat.readContext(createOther.getId(), 1L, Long.MAX_VALUE).actionStates()).isEmpty();

        manageChat.clearSession(createUser.getId());
        assertThat(manageChat.readContext(createUser.getId(), 1L, Long.MAX_VALUE).actionStates()).isEmpty();
        assertThat(manageChat.readContext(createUser.getId(), 2L, Long.MAX_VALUE).actionStates()).isEmpty();
        manageChat.resetSession(createUser.getId());
        assertThat(manageChat.readContext(createUser.getId(), 2L, Long.MAX_VALUE).actionStates()).isEmpty();
    }

    @Test
    void streamRequestCarriesRejectedCardStateOnNextTurn() throws Exception {
        AiChatService.RunStart createPrevious = manageChat.startRun(createUser.getId(),
                new AiMessageRequest("proposal-turn", "修改资料", List.of()));
        UserChangeActionEntity createAction = saveAction(
                createUser.getId(), 1L, ChangeActionStatus.PENDING, LocalDateTime.now());
        createAction.setRunId(createPrevious.assistantMessage().runId());
        readActions.updateById(createAction);
        assertThat(manageChat.savePresentation(createUser.getId(), 1L,
                createPrevious.assistantMessage().runId(), Map.of("actionId", createAction.getId().toString())))
                .isNotNull();
        manageChat.completeRun(createUser.getId(), 1L, createPrevious.assistantMessage().runId(),
                "提案仍待确认", "fake-model", "stop", null, null);
        readMvc.perform(post("/personal-space/actions/" + createAction.getId() + "/reject")
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk());
        when(streamClient.streamChat(any(), any(), any())).thenAnswer(readInvocation -> {
            @SuppressWarnings("unchecked")
            Consumer<AgentStreamClient.StreamEvent> consumeEvent = readInvocation.getArgument(2);
            consumeEvent.accept(new AgentStreamClient.StreamEvent("done", readMapper.readTree("""
                    {"content":"已拒绝，无需确认","model":"fake-model","finishReason":"stop"}
                    """)));
            return CompletableFuture.completedFuture(null);
        });
        MvcResult createStream = readMvc.perform(post("/personal-space/assistant/messages/stream")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientRequestId":"after-rejection","content":"继续","fileIds":[]}
                                """))
                .andExpect(request().asyncStarted()).andReturn();
        readMvc.perform(asyncDispatch(createStream)).andExpect(status().isOk());
        ArgumentCaptor<AgentStreamClient.StreamRequest> readRequest =
                ArgumentCaptor.forClass(AgentStreamClient.StreamRequest.class);
        verify(streamClient).streamChat(readRequest.capture(), any(), any());
        assertThat(readRequest.getValue().actionStates()).containsExactly(new AiChatService.ActionState(
                createAction.getId().toString(), "REJECTED", false, "propose_profile_patch"));
        assertThat(readRequest.getValue().history()).extracting(AgentStreamClient.HistoryMessage::content)
                .contains("提案仍待确认");
        assertThat(readRequest.getValue().history()).filteredOn(
                        readHistory -> "提案仍待确认".equals(readHistory.content()))
                .singleElement().satisfies(readHistory ->
                        assertThat(readHistory.actionIds()).containsExactly(createAction.getId().toString()));
        var readJson = readMapper.valueToTree(readRequest.getValue());
        assertThat(readJson.path("actionStates").get(0).path("status").asText()).isEqualTo("REJECTED");
        assertThat(readJson.path("actionStates").get(0).path("actionId").isTextual()).isTrue();
    }

    @Test
    void legacyStreamRequestConstructorsDefaultToEmptyActionStates() {
        AgentStreamClient.StreamRequest readOld = new AgentStreamClient.StreamRequest(
                "run", 1L, "request", "1", "2", "继续", List.of(), Map.of(), "", List.of());
        AgentStreamClient.StreamRequest readExtended = new AgentStreamClient.StreamRequest(
                "run", 1L, "request", "1", "2", "继续", List.of(), Map.of(), "", List.of(), null, null);
        assertThat(readOld.actionStates()).isEmpty();
        assertThat(readExtended.actionStates()).isEmpty();
        assertThat(new AgentStreamClient.HistoryMessage("assistant", "提案").actionIds()).isEmpty();
        assertThat(new AiChatService.ContextMessage("assistant", "提案").actionIds()).isEmpty();
    }

    @Test
    void historyActionRefsAreBoundedPositiveStringIdsAndAssistantOnly() throws Exception {
        AiChatService.RunStart createRun = manageChat.startRun(createUser.getId(),
                new AiMessageRequest("history-refs", "旧用户消息", List.of()));
        manageChat.completeRun(createUser.getId(), 1L, createRun.assistantMessage().runId(),
                "旧提案消息", "fake-model", "stop", null, null);
        List<Object> createIds = new java.util.ArrayList<>();
        createIds.addAll(List.of("0", "-1", "01", "9223372036854775808", "abc", 7));
        createIds.add(null);
        for (int readIndex = 1; readIndex <= 40; readIndex++) createIds.add(Integer.toString(readIndex));
        createIds.add("1");
        String createPresentation = readMapper.writeValueAsString(Map.of("actionIds", createIds));
        AiMessageEntity updateAssistant = readMessages.selectById(createRun.assistantMessage().id());
        updateAssistant.setPresentation(createPresentation);
        readMessages.updateById(updateAssistant);
        AiMessageEntity updateUser = readMessages.selectById(createRun.userMessage().id());
        updateUser.setPresentation(createPresentation);
        readMessages.updateById(updateUser);
        List<AiChatService.ContextMessage> readHistory = manageChat.readContext(
                createUser.getId(), 1L, Long.MAX_VALUE).history();
        assertThat(readHistory.get(0).actionIds()).isEmpty();
        assertThat(readHistory.get(1).content()).isEqualTo("旧提案消息");
        assertThat(readHistory.get(1).actionIds()).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, 32).mapToObj(Integer::toString).toList());
        updateAssistant.setPresentation("{\"actionIds\":\"1\"}");
        readMessages.updateById(updateAssistant);
        assertThat(manageChat.readContext(createUser.getId(), 1L, Long.MAX_VALUE).history().get(1).actionIds())
                .isEmpty();
    }

    @Test
    void resetKeepsMemory() throws Exception {
        AiChatService.RunStart createRun = manageChat.startRun(
                createUser.getId(), new AiMessageRequest("request-reset", "重置", List.of()));
        manageChat.completeRun(
                createUser.getId(), 1L, createRun.assistantMessage().runId(),
                "完成", "fake-model", "stop", null, null);
        manageMemory.saveMemory(createUser.getId(), "currentGoal",
                new UserMemoryUpdateRequest(0L, "寻找媒体实习"));

        readMvc.perform(delete("/personal-space/assistant/session")
                        .header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exists").value(true))
                .andExpect(jsonPath("$.data.epoch").value("2"))
                .andExpect(jsonPath("$.data.activeRun").value(false));
        assertThat(readSessions.selectById(createUser.getId()).getEpoch()).isEqualTo(2L);
        assertThat(readMessages.selectCount(null)).isZero();
        assertThat(manageMemory.readMemory(createUser.getId()).entries())
                .containsKey("currentGoal");
    }

    @Test
    void sessionResetAndClearCannotReplenishDailyQuota() throws Exception {
        var run = manageChat.startRun(createUser.getId(), new AiMessageRequest("quota-reset-original", "找岗位", List.of()));
        manageChat.completeRun(createUser.getId(), run.session().epoch(), run.assistantMessage().runId(), "结果", "fake", "stop", 1, 1);
        AiSessionEntity limited = readSessions.selectById(createUser.getId());
        limited.setUsageDate(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        limited.setDailyUsageCount(100); limited.setIrrelevantStrikeCount(2);
        readSessions.updateById(limited);
        limited = readSessions.selectById(createUser.getId());
        var originalCreatedAt = limited.getCreatedAt();

        readMvc.perform(delete("/personal-space/assistant/session").header("Fusion-Token", createToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.epoch").value("2"));
        assertQuotaState(readSessions.selectById(createUser.getId()), limited);
        assertThat(readSessions.selectById(createUser.getId()).getCreatedAt()).isEqualTo(originalCreatedAt);
        // Deleting the old request ID is not an idempotency loophole after a reset.
        readMvc.perform(post("/personal-space/assistant/messages/stream").header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientRequestId\":\"quota-reset-original\",\"content\":\"找岗位\",\"fileIds\":[]}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value(429));
        readMvc.perform(post("/personal-space/assistant/session/clear").header("Fusion-Token", createToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.epoch").value("3"));
        assertQuotaState(readSessions.selectById(createUser.getId()), limited);
        readMvc.perform(post("/personal-space/assistant/messages/stream").header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientRequestId\":\"quota-after-clear\",\"content\":\"找岗位\",\"fileIds\":[]}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value(429));
        assertThat(manageChat.readMessages(createUser.getId(), null, 10).messages()).isEmpty();
        verify(streamClient, times(0)).streamChat(any(), any(), any());
    }

    @Test
    void repeatedSessionResetAndClearCannotRemoveTopicBanOrStrikeCount() throws Exception {
        for (int index = 1; index <= 3; index++) {
            var run = manageChat.startRun(createUser.getId(), new AiMessageRequest("ban-reset-" + index, "无关消息", List.of()));
            manageChat.recordTopicDecision(createUser.getId(), run.session().epoch(), run.assistantMessage().runId(), false);
            manageChat.completeRun(createUser.getId(), run.session().epoch(), run.assistantMessage().runId(), "请询问就业相关问题", "fake", "topic_rejected", 1, 1);
        }
        AiSessionEntity restricted = readSessions.selectById(createUser.getId());
        assertThat(restricted.getIrrelevantStrikeCount()).isEqualTo(3);
        assertThat(restricted.getAiBannedUntil()).isAfter(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        for (int index = 1; index <= 2; index++) {
            readMvc.perform(delete("/personal-space/assistant/session").header("Fusion-Token", createToken))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.epoch").value(Integer.toString(index + 1)));
            assertQuotaState(readSessions.selectById(createUser.getId()), restricted);
            readMvc.perform(post("/personal-space/assistant/messages/stream").header("Fusion-Token", createToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"clientRequestId\":\"ban-reset-retry-" + index + "\",\"content\":\"找岗位\",\"fileIds\":[]}"))
                    .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value(429));
        }
        readMvc.perform(post("/personal-space/assistant/session/clear").header("Fusion-Token", createToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.epoch").value("4"));
        assertQuotaState(readSessions.selectById(createUser.getId()), restricted);
        readMvc.perform(post("/personal-space/assistant/messages/stream").header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientRequestId\":\"ban-clear-retry\",\"content\":\"找岗位\",\"fileIds\":[]}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value(429));
        assertThat(manageChat.readMessages(createUser.getId(), null, 10).messages()).isEmpty();
        verify(streamClient, times(0)).streamChat(any(), any(), any());
    }

    @Test
    void sessionResetClearsHistoryAndRunStateButOldSignedContextNeverRevives() {
        var run = manageChat.startRun(createUser.getId(), new AiMessageRequest("epoch-reset", "找岗位", List.of()));
        String signed = quotaContexts.issueContext(createUser.getId(), run.assistantMessage().runId(), run.session().epoch(),
                run.userMessage().requestId(), List.of("profile:read"));
        assertThat(quotaContexts.verifyContext(signed, "profile:read").epoch()).isEqualTo(1L);
        AiSessionEntity update = readSessions.selectById(createUser.getId());
        update.setSummary("旧摘要"); update.setSummaryThroughMessageId(run.userMessage().id()); readSessions.updateById(update);
        AiSessionEntity originalLimits = readSessions.selectById(createUser.getId());
        var reset = manageChat.resetSession(createUser.getId());
        assertThat(reset.exists()).isTrue(); assertThat(reset.epoch()).isEqualTo(2L); assertThat(reset.activeRun()).isFalse();
        AiSessionEntity after = readSessions.selectById(createUser.getId());
        assertQuotaState(after, originalLimits);
        assertThat(after.getSummary()).isNull(); assertThat(after.getSummaryThroughMessageId()).isNull();
        assertThat(after.getActiveRunId()).isNull(); assertThat(after.getLeaseUntil()).isNull(); assertThat(after.getLastMessageAt()).isNull();
        assertThat(readMessages.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiMessageEntity>()
                .eq(AiMessageEntity::getUserId, createUser.getId()))).isZero();
        assertThatThrownBy(() -> quotaContexts.verifyContext(signed, "profile:read")).hasMessageContaining("AgentContext 无效");
        assertThat(manageChat.completeRun(createUser.getId(), run.session().epoch(), run.assistantMessage().runId(),
                "旧运行迟到回复", "fake", "stop", 1, 1)).isFalse();
        var next = manageChat.startRun(createUser.getId(), new AiMessageRequest("epoch-reset", "新会话", List.of()));
        assertThat(next.session().epoch()).isEqualTo(2L); assertThat(next.assistantMessage().runId()).isNotEqualTo(run.assistantMessage().runId());
        assertThatThrownBy(() -> quotaContexts.verifyContext(signed, "profile:read")).hasMessageContaining("AgentContext 无效");
        String current = quotaContexts.issueContext(createUser.getId(), next.assistantMessage().runId(), next.session().epoch(),
                next.userMessage().requestId(), List.of("profile:read"));
        assertThat(quotaContexts.verifyContext(current, "profile:read").epoch()).isEqualTo(2L);
        assertThat(readSessions.selectById(createUser.getId()).getDailyUsageCount()).isEqualTo(originalLimits.getDailyUsageCount() + 1);
    }

    private void assertQuotaState(AiSessionEntity actual, AiSessionEntity expected) {
        assertThat(actual).isNotNull();
        assertThat(actual.getUsageDate()).isEqualTo(expected.getUsageDate());
        assertThat(actual.getDailyUsageCount()).isEqualTo(expected.getDailyUsageCount());
        assertThat(actual.getIrrelevantStrikeCount()).isEqualTo(expected.getIrrelevantStrikeCount());
        assertThat(actual.getAiBannedUntil()).isEqualTo(expected.getAiBannedUntil());
    }

    @Test
    void validateAttachments() {
        createFile = readFiles.upload(createUser.getId(), new MockMultipartFile(
                "file", "mine.pdf", "application/pdf",
                "%PDF-mine".getBytes(StandardCharsets.UTF_8)));
        AiChatService.RunStart createRun = manageChat.startRun(
                createUser.getId(), new AiMessageRequest(
                        "request-file", "读取附件", List.of(createFile.getId())));
        assertThat(createRun.userMessage().fileIds()).containsExactly(createFile.getId());
        manageChat.cancelRun(createUser.getId());

        createOther = saveUser("ai-other", "ai-other-student");
        createOtherFile = readFiles.upload(createOther.getId(), new MockMultipartFile(
                "file", "other.pdf", "application/pdf",
                "%PDF-other".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> manageChat.startRun(
                createUser.getId(), new AiMessageRequest(
                        "request-other-file", "越权附件", List.of(createOtherFile.getId()))))
                .hasMessageContaining("文件不存在");
    }

    @Test
    void streamText() throws Exception {
        when(streamClient.streamChat(any(), any(), any())).thenAnswer(readInvocation -> {
            AgentStreamClient.StreamRequest readRequest = readInvocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Consumer<AgentStreamClient.StreamEvent> consumeEvent = readInvocation.getArgument(2);
            consumeEvent.accept(new AgentStreamClient.StreamEvent(
                    "start", readMapper.readTree("{\"runId\":\"" + readRequest.runId() + "\"}")));
            consumeEvent.accept(new AgentStreamClient.StreamEvent(
                    "delta", readMapper.readTree("{\"seq\":1,\"text\":\"你\"}")));
            consumeEvent.accept(new AgentStreamClient.StreamEvent(
                    "delta", readMapper.readTree("{\"seq\":2,\"text\":\"好\"}")));
            consumeEvent.accept(new AgentStreamClient.StreamEvent(
                    "done", readMapper.readTree("""
                            {
                              "content": "你好",
                              "model": "fake-model",
                              "finishReason": "stop",
                              "usage": {"promptTokens": 3, "completionTokens": 2}
                            }
                            """)));
            return CompletableFuture.completedFuture(null);
        });

        MvcResult createStream = readMvc.perform(post("/personal-space/assistant/messages/stream")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientRequestId":"stream-1","content":"你好","fileIds":[]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        readMvc.perform(asyncDispatch(createStream))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:start")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:delta")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")));

        AiMessageEntity readAssistant = readMessages.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiMessageEntity>()
                        .eq(AiMessageEntity::getUserId, createUser.getId())
                        .eq(AiMessageEntity::getRole, com.fusioncareer.enums.AiMessageRole.ASSISTANT));
        assertThat(readAssistant.getStatus()).isEqualTo(AiMessageStatus.COMPLETED);
        assertThat(readAssistant.getContent()).isEqualTo("你好");
        assertThat(readAssistant.getModel()).isEqualTo("fake-model");

        MvcResult readReplay = readMvc.perform(post("/personal-space/assistant/messages/stream")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientRequestId":"stream-1","content":"你好","fileIds":[]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        readMvc.perform(asyncDispatch(readReplay))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("snapshot")));
        verify(streamClient, times(1)).streamChat(any(), any(), any());
    }

    @Test
    void unrelatedTopicEventUpdatesUserGuardAndReturnsWarning() throws Exception {
        when(streamClient.streamChat(any(), any(), any())).thenAnswer(readInvocation -> {
            @SuppressWarnings("unchecked")
            Consumer<AgentStreamClient.StreamEvent> consumeEvent = readInvocation.getArgument(2);
            consumeEvent.accept(new AgentStreamClient.StreamEvent(
                    "topic_check", readMapper.readTree(
                            "{\"relevant\":false,\"reason\":\"与就业主题无关\"}")));
            consumeEvent.accept(new AgentStreamClient.StreamEvent(
                    "done", readMapper.readTree("""
                            {"content":"请询问就业相关问题","model":"small",
                             "finishReason":"topic_rejected","usage":{}}
                            """)));
            return CompletableFuture.completedFuture(null);
        });

        MvcResult stream = readMvc.perform(post("/personal-space/assistant/messages/stream")
                        .header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientRequestId":"topic-reject","content":"游戏攻略","fileIds":[]}
                                """))
                .andExpect(request().asyncStarted()).andReturn();
        readMvc.perform(asyncDispatch(stream))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:topic_warning")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")));
        assertThat(readSessions.selectById(createUser.getId()).getIrrelevantStrikeCount()).isEqualTo(1);
    }

    @Test
    void resumeAdviceStreamsPersistsAndReplaysThroughExistingAssistantWithoutCreatingActions() throws Exception {
        JobPostEntity job = adviceJob("新闻编辑实习");
        Map<String, Object> report = adviceReport(job, "online");
        long actionCount = readActions.selectCount(null);
        when(streamClient.streamChat(any(), any(), any())).thenAnswer(invocation -> {
            AgentStreamClient.StreamRequest sent = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Consumer<AgentStreamClient.StreamEvent> consume = invocation.getArgument(2);
            consume.accept(new AgentStreamClient.StreamEvent("resume_advice", readMapper.valueToTree(
                    Map.of("schemaVersion", 1, "type", "resume_advice", "runId", sent.runId(), "report", report))));
            consume.accept(new AgentStreamClient.StreamEvent("done", readMapper.readTree(
                    "{\"content\":\"请核实并补充已有作品\",\"model\":\"fake-model\",\"finishReason\":\"stop\"}")));
            return CompletableFuture.completedFuture(null);
        });
        String requestBody = readMapper.writeValueAsString(Map.of("clientRequestId", "advice-stream",
                "content", "请结合这个岗位分析简历", "fileIds", List.of(), "jobId", job.getId().toString(),
                "interaction", Map.of("schemaVersion", 1, "type", "resume_advice", "source", "online")));
        MvcResult started = readMvc.perform(post("/personal-space/assistant/messages/stream")
                        .header("Fusion-Token", createToken).contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(request().asyncStarted()).andReturn();
        readMvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:resume_advice")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"resumeAdvice\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")));
        ArgumentCaptor<AgentStreamClient.StreamRequest> forwarded = ArgumentCaptor.forClass(AgentStreamClient.StreamRequest.class);
        verify(streamClient).streamChat(forwarded.capture(), any(), any());
        assertThat(forwarded.getValue().jobId()).isEqualTo(job.getId().toString());
        assertThat((Map<String, Object>) forwarded.getValue().interaction())
                .containsEntry("type", "resume_advice").containsEntry("source", "online");
        readMvc.perform(get("/personal-space/assistant/messages").header("Fusion-Token", createToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.messages[0].presentation.resumeAdvice.jobId").value(job.getId().toString()))
                .andExpect(jsonPath("$.data.messages[0].presentation.resumeAdvice.available").value(true))
                .andExpect(jsonPath("$.data.messages[0].presentation.resumeAdvice.stale").value(false))
                .andExpect(jsonPath("$.data.messages[0].presentation.resumeAdvice.safeguards.resumeModified").value(false))
                .andExpect(jsonPath("$.data.messages[0].presentation.actionIds").doesNotExist());
        var stored = readMessages.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiMessageEntity>()
                .eq(AiMessageEntity::getUserId, createUser.getId()).eq(AiMessageEntity::getRole, com.fusioncareer.enums.AiMessageRole.ASSISTANT));
        assertThat(stored.getPresentation()).contains("resumeAdvice").doesNotContain("\"available\"", "\"stale\"");
        MvcResult replay = readMvc.perform(post("/personal-space/assistant/messages/stream")
                        .header("Fusion-Token", createToken).contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(request().asyncStarted()).andReturn();
        readMvc.perform(asyncDispatch(replay)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"snapshot\":true")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:resume_advice")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"resumeAdvice\"")));
        verify(streamClient, times(1)).streamChat(any(), any(), any());
        assertThat(readActions.selectCount(null)).isEqualTo(actionCount);
        assertThat(adviceResumes.getResume(createUser.getId())).isNull();
    }

    @Test
    void resumeAdviceMergesWithExistingJobCardsAndProposalIdsInEitherEventOrder() {
        JobPostEntity job = adviceJob("记者实习");
        var run = adviceRun("advice-merge", job, "online", List.of());
        UserChangeActionEntity action = saveAction(createUser.getId(), run.session().epoch(), ChangeActionStatus.PENDING, LocalDateTime.now());
        action.setRunId(run.assistantMessage().runId()); readActions.updateById(action);
        Map<String, Object> cards = Map.of("type", "job_results", "jobIds", List.of(job.getId().toString()),
                "method", "rules", "reasons", Map.of(job.getId().toString(), "岗位与已有经历相关"));
        manageChat.savePresentation(createUser.getId(), run.session().epoch(), run.assistantMessage().runId(), cards);
        manageChat.savePresentation(createUser.getId(), run.session().epoch(), run.assistantMessage().runId(),
                Map.of("actionId", action.getId().toString()));
        var merged = manageChat.savePresentation(createUser.getId(), run.session().epoch(), run.assistantMessage().runId(),
                Map.of("type", "resume_advice", "report", adviceReport(job, "online")));
        assertThat(merged).containsEntry("jobIds", List.of(job.getId().toString()))
                .containsEntry("actionIds", List.of(action.getId().toString())).containsKeys("jobs", "resumeAdvice", "reasons");
        var reverse = manageChat.savePresentation(createUser.getId(), run.session().epoch(), run.assistantMessage().runId(), cards);
        assertThat(reverse).containsKeys("resumeAdvice", "jobs")
                .containsEntry("actionIds", List.of(action.getId().toString()));
        manageChat.completeRun(createUser.getId(), run.session().epoch(), run.assistantMessage().runId(), "结果", "test", "stop", 1, 1);
        var history = manageChat.readMessages(createUser.getId(), null, 10).messages().get(0).presentation();
        assertThat(history).containsKeys("resumeAdvice", "jobs")
                .containsEntry("actionIds", List.of(action.getId().toString()));
        assertThat(readActions.selectById(action.getId()).getStatus()).isEqualTo(ChangeActionStatus.PENDING);
    }

    @Test
    void resumeAdviceRejectsWrongJobSourceForeignFileAndVersionChangesBeforeSaving() {
        JobPostEntity job = adviceJob("采编实习");
        JobPostEntity different = adviceJob("运营实习");
        var run = adviceRun("advice-invalid", job, "online", List.of());
        Map<String, Object> wrongJob = adviceReport(different, "online");
        assertThatThrownBy(() -> saveAdvice(run, wrongJob)).hasMessageContaining("岗位与当前消息不匹配");
        Map<String, Object> wrongVersion = adviceReport(job, "online"); wrongVersion.put("jobVersion", "old");
        assertThatThrownBy(() -> saveAdvice(run, wrongVersion)).hasMessageContaining("版本已变化");
        Map<String, Object> wrongSource = adviceReport(job, "online");
        wrongSource.put("resumeSource", "file"); wrongSource.put("resumeVersion", "file:42:v0:pmissing");
        assertThatThrownBy(() -> saveAdvice(run, wrongSource)).hasMessageContaining("不是本轮附件");
        Map<String, Object> oldResume = adviceReport(job, "online");
        ResumeEntity resume = new ResumeEntity(); resume.setUserId(createUser.getId()); resume.setVersion(0L);
        resume.setInternship("已有编辑实习经历"); adviceResumes.save(resume);
        assertThatThrownBy(() -> saveAdvice(run, oldResume)).hasMessageContaining("简历或资料版本已变化");
        Map<String, Object> oldProfile = adviceReport(job, "online");
        UserProfileEntity profile = new UserProfileEntity(); profile.setUserId(createUser.getId()); profile.setVersion(0L);
        adviceProfiles.save(profile);
        assertThatThrownBy(() -> saveAdvice(run, oldProfile)).hasMessageContaining("简历或资料版本已变化");
        assertThat(readMessages.selectById(run.assistantMessage().id()).getPresentation()).isNull();
        manageChat.cancelRun(createUser.getId());
        createFile = readFiles.upload(createUser.getId(), new MockMultipartFile("file", "own-advice.pdf", "application/pdf",
                "%PDF-advice".getBytes(StandardCharsets.UTF_8)));
        var fileRun = adviceRun("advice-file-invalid", job, "file", List.of(createFile.getId()));
        createOther = saveUser("advice-foreign", "advice-foreign-student");
        createOtherFile = readFiles.upload(createOther.getId(), new MockMultipartFile("file", "foreign.pdf", "application/pdf",
                "%PDF-other-advice".getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> foreign = adviceReport(job, "online"); foreign.put("resumeSource", "file");
        foreign.put("resumeVersion", "file:" + createOtherFile.getId() + ":v0:p0");
        assertThatThrownBy(() -> saveAdvice(fileRun, foreign)).hasMessageContaining("不是本轮附件");
        Map<String, Object> fileReport = adviceReport(job, "file");
        assertThat(saveAdvice(fileRun, fileReport)).containsKey("resumeAdvice");
        readFiles.delete(createUser.getId(), createFile.getId());
        assertThatThrownBy(() -> saveAdvice(fileRun, fileReport)).hasMessageContaining("文件不存在");
    }

    @Test
    void failedCancelledAndClearedRunsHideResumeAdviceAndRejectLateCardWrites() {
        JobPostEntity job = adviceJob("编辑岗位");
        for (String ending : List.of("failed", "cancelled", "cleared")) {
            var run = adviceRun("advice-" + ending, job, "online", List.of());
            Map<String, Object> report = adviceReport(job, "online");
            assertThat(saveAdvice(run, report)).containsKey("resumeAdvice");
            if ("failed".equals(ending)) manageChat.failRun(createUser.getId(), run.session().epoch(), run.assistantMessage().runId(), "TEST_FAILURE");
            else if ("cancelled".equals(ending)) manageChat.cancelRun(createUser.getId());
            else manageChat.clearSession(createUser.getId());
            assertThat(saveAdvice(run, report)).isNull();
            var history = manageChat.readMessages(createUser.getId(), null, 20).messages();
            assertThat(history).allSatisfy(message -> assertThat(message.presentation()).isEmpty());
        }
    }

    @Test
    void resumeAdviceHistoryMarksOfflineJobAndChangedResumeWithoutLosingOriginalAdvice() {
        JobPostEntity job = adviceJob("校园记者");
        var run = adviceRun("advice-stale", job, "online", List.of());
        saveAdvice(run, adviceReport(job, "online"));
        manageChat.completeRun(createUser.getId(), run.session().epoch(), run.assistantMessage().runId(), "已有建议", "test", "stop", 1, 1);
        ResumeEntity resume = new ResumeEntity(); resume.setUserId(createUser.getId()); resume.setVersion(0L);
        adviceResumes.save(resume);
        Map<String, Object> changed = (Map<String, Object>) manageChat.readMessages(createUser.getId(), null, 10)
                .messages().get(0).presentation().get("resumeAdvice");
        assertThat(changed).containsEntry("available", true).containsEntry("stale", true);
        adviceJobs.recycleJob(job.getId(), "回归测试下线");
        Map<String, Object> offline = (Map<String, Object>) manageChat.readMessages(createUser.getId(), null, 10)
                .messages().get(0).presentation().get("resumeAdvice");
        assertThat(offline).containsEntry("available", false).containsEntry("stale", true)
                .containsEntry("summary", "已有经历与岗位部分匹配，建议补充可核验依据。");
    }

    @Test
    void invalidAdviceLeavesPreviouslySavedCardsUntouched() {
        JobPostEntity job = adviceJob("测试采编");
        var run = adviceRun("advice-invalid-format", job, "online", List.of());
        manageChat.savePresentation(createUser.getId(), run.session().epoch(), run.assistantMessage().runId(),
                Map.of("type", "job_results", "jobIds", List.of(job.getId().toString())));
        String previous = readMessages.selectById(run.assistantMessage().id()).getPresentation();
        Map<String, Object> extra = adviceReport(job, "online"); extra.put("rawResume", "不应入库的完整简历");
        assertThatThrownBy(() -> saveAdvice(run, extra)).hasMessageContaining("格式无效");
        Map<String, Object> unsafe = adviceReport(job, "online");
        unsafe.put("safeguards", Map.of("resumeModified", true, "unsupportedClaimsAsserted", false, "needsUserVerification", true));
        assertThatThrownBy(() -> saveAdvice(run, unsafe)).hasMessageContaining("格式无效");
        assertThat(readMessages.selectById(run.assistantMessage().id()).getPresentation()).isEqualTo(previous);
    }

    @Test
    void resumeAdviceRequiresVisibleRequestSelectedJobAndExactlySelectedOwnedAttachment() {
        assertThatThrownBy(() -> manageChat.startRun(createUser.getId(), new AiMessageRequest(
                "advice-no-job", "分析简历", List.of(), new AiInteraction(1, "resume_advice", null, "online", List.of()), null)))
                .hasMessageContaining("当前岗位详情");
        JobPostEntity job = adviceJob("在招岗位");
        assertThatThrownBy(() -> manageChat.startRun(createUser.getId(), new AiMessageRequest(
                "advice-file-unattached", "分析简历", List.of(),
                new AiInteraction(1, "resume_advice", null, "file", List.of("42")), job.getId().toString())))
                .hasMessageContaining("本轮选中的附件");
        createOther = saveUser("advice-selection-other", "advice-selection-other-student");
        createOtherFile = readFiles.upload(createOther.getId(), new MockMultipartFile("file", "other-selected.pdf", "application/pdf",
                "%PDF-other-selected".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> manageChat.startRun(createUser.getId(), new AiMessageRequest(
                "advice-file-foreign", "分析简历", List.of(createOtherFile.getId()),
                new AiInteraction(1, "resume_advice", null, "file", List.of(createOtherFile.getId().toString())), job.getId().toString())))
                .hasMessageContaining("文件不存在");
        adviceJobs.recycleJob(job.getId(), "回归测试下线");
        assertThatThrownBy(() -> adviceRun("advice-offline-start", job, "online", List.of()))
                .hasMessageContaining("已不可见");
        assertThat(readMessages.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiMessageEntity>()
                .eq(AiMessageEntity::getUserId, createUser.getId()))).isZero();
    }

    @Test
    void malformedUpstreamAdviceFailsStreamWithoutLeakingPrivateFieldsOrDisplayingCard() throws Exception {
        JobPostEntity job = adviceJob("岗位建议格式防护");
        Map<String, Object> report = adviceReport(job, "online");
        report.put("rawResume", "synthetic-private-field-must-not-leak");
        when(streamClient.streamChat(any(), any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<AgentStreamClient.StreamEvent> consume = invocation.getArgument(2);
            consume.accept(new AgentStreamClient.StreamEvent("resume_advice", readMapper.valueToTree(
                    Map.of("schemaVersion", 1, "type", "resume_advice", "report", report))));
            return CompletableFuture.completedFuture(null);
        });
        String body = readMapper.writeValueAsString(Map.of("clientRequestId", "advice-bad-stream", "content", "分析岗位简历",
                "fileIds", List.of(), "jobId", job.getId().toString(),
                "interaction", Map.of("schemaVersion", 1, "type", "resume_advice", "source", "online")));
        MvcResult start = readMvc.perform(post("/personal-space/assistant/messages/stream").header("Fusion-Token", createToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted()).andReturn();
        readMvc.perform(asyncDispatch(start)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:error")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("event:resume_advice"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-private-field-must-not-leak"))));
        var history = manageChat.readMessages(createUser.getId(), null, 10).messages();
        assertThat(history.get(0).status()).isEqualTo(AiMessageStatus.FAILED);
        assertThat(history.get(0).presentation()).isEmpty();
        assertThat(readSessions.selectById(createUser.getId()).getActiveRunId()).isNull();
    }

    private JobPostEntity adviceJob(String name) {
        JobPostEntity job = new JobPostEntity(); job.setCompanyName("测试新闻机构"); job.setPositionName(name);
        job.setSourceType(com.fusioncareer.enums.SourceType.PLATFORM);
        job.setRecruitType(com.fusioncareer.enums.RecruitType.DAILY_INTERNSHIP);
        job.setJobCategory(com.fusioncareer.enums.JobCategory.MEDIA);
        job.setStatus(com.fusioncareer.enums.JobPostStatus.PUBLISHED);
        job.setWorkCity("上海"); job.setWorkCities(List.of("上海")); job.setJobDesc("负责采访和新闻编辑");
        job.setReqSkills("新闻写作"); job.setApplicationDeadline(LocalDate.now().plusDays(10));
        job.setUpdatedAt(LocalDateTime.of(2026, 10, 13, 12, 0)); adviceJobs.save(job);
        return job;
    }

    private AiChatService.RunStart adviceRun(String requestId, JobPostEntity job, String source, List<Long> fileIds) {
        return manageChat.startRun(createUser.getId(), new AiMessageRequest(requestId, "分析岗位简历", fileIds,
                new AiInteraction(1, "resume_advice", null, source, fileIds.stream().map(String::valueOf).toList()), job.getId().toString()));
    }

    private Map<String, Object> adviceReport(JobPostEntity job, String source) {
        var profile = adviceProfiles.getProfile(createUser.getId());
        String suffix = ":p" + (profile == null ? "missing" : profile.getVersion());
        String version;
        if ("file".equals(source)) {
            var file = readFiles.getOwnFile(createUser.getId(), createFile.getId());
            version = "file:" + file.getId() + ":v" + file.getVersion() + suffix;
        } else {
            var resume = adviceResumes.getResume(createUser.getId());
            version = "online:r" + (resume == null ? "missing" : resume.getVersion()) + suffix;
        }
        return ResumeAdvicePresentationTest.report(job.getId(), adviceJobs.getVisibleJobPost(job.getId()).getUpdatedAt().toString(), version, source);
    }

    private Map<String, Object> saveAdvice(AiChatService.RunStart run, Map<String, Object> report) {
        return manageChat.savePresentation(createUser.getId(), run.session().epoch(), run.assistantMessage().runId(),
                Map.of("type", "resume_advice", "report", report));
    }

    private UserEntity saveUser(String createUsername, String createStudentId) {
        UserEntity createAccount = new UserEntity();
        createAccount.setUsername(createUsername);
        createAccount.setStudentId(createStudentId);
        createAccount.setRole(UserRole.NORMAL);
        createAccount.setStatus(UserStatus.NORMAL);
        createAccount.setCreatedAt(LocalDateTime.now());
        readUsers.save(createAccount);
        return createAccount;
    }

    private UserChangeActionEntity saveAction(
            Long readUserId, Long readEpoch, ChangeActionStatus readStatus, LocalDateTime readUpdatedAt) {
        UserChangeActionEntity createAction = new UserChangeActionEntity();
        createAction.setUserId(readUserId);
        createAction.setEpoch(readEpoch);
        createAction.setActorType(ChangeActorType.AGENT);
        createAction.setOrigin("ASSISTANT");
        createAction.setToolName("propose_profile_patch");
        createAction.setActionType(ChangeActionType.APPLY);
        createAction.setStatus(readStatus);
        createAction.setSchemaVersion((short) 1);
        createAction.setReason("private-action-reason");
        createAction.setUpdatedAt(readUpdatedAt);
        readActions.insert(createAction);
        return createAction;
    }
}
