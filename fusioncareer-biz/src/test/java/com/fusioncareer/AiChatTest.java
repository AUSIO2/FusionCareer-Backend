package com.fusioncareer;

import cn.dev33.satoken.stp.StpUtil;
import com.fusioncareer.dto.req.AiMessageRequest;
import com.fusioncareer.dto.req.UserMemoryUpdateRequest;
import com.fusioncareer.dto.res.ResumeFileResponse;
import com.fusioncareer.entity.AiMessageEntity;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.enums.AiMessageStatus;
import com.fusioncareer.enums.UserRole;
import com.fusioncareer.enums.UserStatus;
import com.fusioncareer.mapper.AiMessageMapper;
import com.fusioncareer.mapper.AiSessionMapper;
import com.fusioncareer.service.AiChatService;
import com.fusioncareer.service.ResumeFileService;
import com.fusioncareer.service.UserMemoryService;
import com.fusioncareer.service.UserService;
import com.fusioncareer.client.AgentStreamClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.List;
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
                .andExpect(jsonPath("$.data.readTools.length()").value(16))
                .andExpect(jsonPath("$.data.writeTools.length()").value(8))
                .andExpect(jsonPath("$.data.limits.maxMessageChars").value(8000));
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
                "第一条回答", "fake-model", "stop", null, null);
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
        assertThat(manageChat.updateSummary(readWork, "用户正在寻找媒体实习")).isTrue();
        assertThat(manageChat.updateSummary(readWork, "不应覆盖")).isFalse();
        assertThat(readSessions.selectById(createUser.getId()).getSummary())
                .isEqualTo("用户正在寻找媒体实习");

        AiChatService.ChatContext readContext = manageChat.readContext(
                createUser.getId(), 1L, Long.MAX_VALUE);
        assertThat(readContext.summary()).isEqualTo("用户正在寻找媒体实习");
        assertThat(readContext.history()).hasSize(6);
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
                .andExpect(jsonPath("$.data.exists").value(false));
        assertThat(readSessions.selectById(createUser.getId())).isNull();
        assertThat(readMessages.selectCount(null)).isZero();
        assertThat(manageMemory.readMemory(createUser.getId()).entries())
                .containsKey("currentGoal");
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
}
