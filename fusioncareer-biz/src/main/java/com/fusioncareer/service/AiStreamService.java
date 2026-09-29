package com.fusioncareer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.client.AgentStreamClient;
import com.fusioncareer.client.AgentSummaryClient;
import com.fusioncareer.dto.req.AiMessageRequest;
import com.fusioncareer.dto.res.AiMessageResponse;
import com.fusioncareer.dto.res.AiSessionResponse;
import com.fusioncareer.dto.res.UserMemoryResponse;
import com.fusioncareer.entity.ResumeFileEntity;
import com.fusioncareer.enums.AiMessageStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 浏览器、Java 状态机与 Python 文本流之间的短生命周期中继。
 */
@Service
@RequiredArgsConstructor
public class AiStreamService {

    private static final long STREAM_TIMEOUT_MS = 200_000L;
    private static final List<String> READ_SCOPES = List.of(
            "space:read", "account:read", "profile:read", "resume:read",
            "file:read", "memory:read", "application:read", "job:search",
            "job:read", "questionnaire:read", "history:read");
    private static final List<String> WRITE_SCOPES = List.of(
            "profile:propose", "resume:propose", "memory:propose", "file:propose",
            "questionnaire:propose");

    @Value("${ai-chat.write-enabled:false}")
    private boolean writeEnabled;

    private final AiChatService manageChat;
    private final AgentStreamClient streamClient;
    private final AgentSummaryClient summaryClient;
    private final ResumeFileService readFiles;
    private final UserMemoryService readMemory;
    private final AgentContextService contextService;
    private final ObjectMapper objectMapper;

    private final Map<Long, ActiveStream> activeStreams = new ConcurrentHashMap<>();

    public SseEmitter streamMessage(Long updateUserId, AiMessageRequest readRequest) {
        AiChatService.RunStart createRun = manageChat.startRun(updateUserId, readRequest);
        SseEmitter createEmitter = new SseEmitter(STREAM_TIMEOUT_MS);
        ActiveStream createStream = new ActiveStream(updateUserId, createRun, createEmitter);
        configureEmitter(createStream);
        sendStart(createStream);
        if (createRun.reused()) {
            replayRun(createStream);
            return createEmitter;
        }
        if (!manageChat.markStreaming(
                updateUserId, createRun.session().epoch(), createRun.assistantMessage().runId())) {
            finishError(createStream, "AI_RUN_SUPERSEDED", "当前运行已失效", false);
            return createEmitter;
        }

        activeStreams.put(updateUserId, createStream);
        try {
            AgentStreamClient.StreamRequest sendRequest = buildRequest(updateUserId, createRun);
            String createContext = contextService.issueContext(
                    updateUserId,
                    createRun.assistantMessage().runId(),
                    createRun.session().epoch(),
                    createRun.userMessage().requestId(),
                    agentScopes());
            CompletableFuture<Void> readFuture = streamClient.streamChat(
                    sendRequest, createContext,
                    readEvent -> consumeEvent(createStream, readEvent));
            createStream.future().set(readFuture);
            readFuture.whenComplete((readResult, readError) -> {
                if (readError != null) {
                    finishError(createStream, "AI_UPSTREAM_UNAVAILABLE",
                            "暂时无法生成回答", true);
                } else if (!createStream.terminal().get()) {
                    finishError(createStream, "AI_STREAM_INCOMPLETE",
                            "回答流意外结束", true);
                }
            });
        } catch (RuntimeException readError) {
            finishError(createStream, "AI_UPSTREAM_UNAVAILABLE", "暂时无法生成回答", true);
        }
        return createEmitter;
    }

    public AiSessionResponse cancelRun(Long updateUserId) {
        stopStream(updateUserId);
        return manageChat.cancelRun(updateUserId);
    }

    public AiSessionResponse clearSession(Long updateUserId) {
        stopStream(updateUserId);
        return manageChat.clearSession(updateUserId);
    }

    public AiSessionResponse resetSession(Long updateUserId) {
        stopStream(updateUserId);
        return manageChat.resetSession(updateUserId);
    }

    private AgentStreamClient.StreamRequest buildRequest(
            Long readUserId,
            AiChatService.RunStart readRun) {
        AiChatService.ChatContext readContext = manageChat.readContext(
                readUserId, readRun.session().epoch(), readRun.userMessage().id());
        List<AgentStreamClient.Attachment> readAttachments = new ArrayList<>();
        for (Long readFileId : readRun.userMessage().fileIds()) {
            ResumeFileEntity readFile = readFiles.getOwnFile(readUserId, readFileId);
            readAttachments.add(new AgentStreamClient.Attachment(
                    readFile.getId().toString(), readFile.getOriginalName(), readFile.getMimeType()));
        }
        Map<String, Object> readMemoryValues = new LinkedHashMap<>();
        UserMemoryResponse readMemoryResponse = readMemory.readMemory(readUserId);
        readMemoryResponse.entries().forEach((readKey, readEntry) ->
                readMemoryValues.put(readKey, readEntry.value()));
        List<AgentStreamClient.HistoryMessage> readHistory = readContext.history().stream()
                .map(readMessage -> new AgentStreamClient.HistoryMessage(
                        readMessage.role(), readMessage.content()))
                .toList();
        return new AgentStreamClient.StreamRequest(
                readRun.assistantMessage().runId(),
                readRun.session().epoch(),
                readRun.userMessage().requestId(),
                readRun.userMessage().id().toString(),
                readRun.assistantMessage().id().toString(),
                readRun.userMessage().content(),
                readAttachments,
                readMemoryValues,
                readContext.summary(),
                readHistory);
    }

    private List<String> agentScopes() {
        if (!writeEnabled) {
            return READ_SCOPES;
        }
        List<String> readScopes = new ArrayList<>(READ_SCOPES);
        readScopes.addAll(WRITE_SCOPES);
        return List.copyOf(readScopes);
    }

    private void consumeEvent(
            ActiveStream updateStream,
            AgentStreamClient.StreamEvent readEvent) {
        if (updateStream.terminal().get() || "start".equals(readEvent.name())) {
            return;
        }
        if ("delta".equals(readEvent.name())) {
            String readText = readEvent.data().path("text").asText("");
            if (!readText.isEmpty()) {
                updateStream.content().append(readText);
                sendEvent(updateStream.emitter(), "delta", readEvent.data());
            }
            return;
        }
        if ("ping".equals(readEvent.name())) {
            sendEvent(updateStream.emitter(), "ping", readEvent.data());
            return;
        }
        if ("tool_status".equals(readEvent.name())) {
            String readName = readEvent.data().path("name").asText("");
            String readStatus = readEvent.data().path("status").asText("");
            if (readName.length() <= 64
                    && ("RUNNING".equals(readStatus)
                    || "COMPLETED".equals(readStatus)
                    || "FAILED".equals(readStatus))) {
                Map<String, Object> sendStatus = new LinkedHashMap<>();
                sendStatus.put("runId", updateStream.run().assistantMessage().runId());
                sendStatus.put("callId", readEvent.data().path("callId").asText(""));
                sendStatus.put("name", readName);
                sendStatus.put("status", readStatus);
                sendEvent(updateStream.emitter(), "tool_status", sendStatus);
            }
            return;
        }
        if ("action_proposed".equals(readEvent.name())) {
            String readActionId = readEvent.data().path("actionId").asText("");
            String readStatus = readEvent.data().path("status").asText("");
            String readToolName = readEvent.data().path("toolName").asText("");
            String readReason = readEvent.data().path("reason").asText("");
            if (readActionId.matches("[0-9]{1,20}")
                    && "PENDING".equals(readStatus)
                    && readToolName.length() <= 64
                    && readReason.length() <= 256) {
                List<String> readFields = new ArrayList<>();
                readEvent.data().path("changedFields").forEach(readField -> {
                    if (readFields.size() < 32
                            && readField.isTextual()
                            && readField.asText().length() <= 64) {
                        readFields.add(readField.asText());
                    }
                });
                Map<String, Object> sendAction = new LinkedHashMap<>();
                sendAction.put("runId", updateStream.run().assistantMessage().runId());
                sendAction.put("callId", readEvent.data().path("callId").asText(""));
                sendAction.put("toolName", readToolName);
                sendAction.put("actionId", readActionId);
                sendAction.put("status", readStatus);
                sendAction.put("reason", readReason);
                sendAction.put("resourceType", readEvent.data().path("resourceType").asText(""));
                sendAction.put("changedFields", readFields);
                List<Map<String, Object>> readResources = new ArrayList<>();
                readEvent.data().path("resources").forEach(readResource -> {
                    String readType = readResource.path("resourceType").asText("");
                    if (readResources.size() >= 16 || readType.length() > 32) {
                        return;
                    }
                    List<String> readResourceFields = new ArrayList<>();
                    readResource.path("changedFields").forEach(readField -> {
                        if (readResourceFields.size() < 32
                                && readField.isTextual()
                                && readField.asText().length() <= 64) {
                            readResourceFields.add(readField.asText());
                        }
                    });
                    Map<String, Object> readSafeResource = new LinkedHashMap<>();
                    readSafeResource.put("resourceType", readType);
                    readSafeResource.put("changedFields", readResourceFields);
                    if (!readResource.path("baseVersion").isMissingNode()
                            && !readResource.path("baseVersion").isNull()) {
                        readSafeResource.put(
                                "baseVersion", readResource.path("baseVersion").asText());
                    }
                    readResources.add(readSafeResource);
                });
                sendAction.put("resources", readResources);
                if (!readEvent.data().path("baseVersion").isMissingNode()
                        && !readEvent.data().path("baseVersion").isNull()) {
                    sendAction.put("baseVersion",
                            readEvent.data().path("baseVersion").asText());
                }
                sendEvent(updateStream.emitter(), "action_proposed", sendAction);
            }
            return;
        }
        if ("done".equals(readEvent.name())) {
            finishDone(updateStream, readEvent.data());
            return;
        }
        if ("error".equals(readEvent.name())) {
            finishError(
                    updateStream,
                    readEvent.data().path("reason").asText("AI_UPSTREAM_UNAVAILABLE"),
                    readEvent.data().path("message").asText("暂时无法生成回答"),
                    readEvent.data().path("retryable").asBoolean(true));
        }
    }

    private void finishDone(ActiveStream updateStream, JsonNode readDone) {
        if (!updateStream.terminal().compareAndSet(false, true)) {
            return;
        }
        try {
            String updateContent = readDone.path("content").asText(updateStream.content().toString());
            JsonNode readUsage = readDone.path("usage");
            Integer readPromptTokens = readUsage.path("promptTokens").isNumber()
                    ? readUsage.path("promptTokens").asInt() : null;
            Integer readCompletionTokens = readUsage.path("completionTokens").isNumber()
                    ? readUsage.path("completionTokens").asInt() : null;
            boolean hasCompleted = manageChat.completeRun(
                    updateStream.userId(),
                    updateStream.run().session().epoch(),
                    updateStream.run().assistantMessage().runId(),
                    updateContent,
                    readDone.path("model").isNull() ? null : readDone.path("model").asText(null),
                    readDone.path("finishReason").asText("stop"),
                    readPromptTokens,
                    readCompletionTokens);
            if (hasCompleted) {
                scheduleSummary(updateStream.userId(), updateStream.run().session().epoch());
                Map<String, Object> sendDone = new LinkedHashMap<>();
                sendDone.put("runId", updateStream.run().assistantMessage().runId());
                sendDone.put("messageId", updateStream.run().assistantMessage().id());
                sendDone.put("finishReason", readDone.path("finishReason").asText("stop"));
                sendDone.put("usage", objectMapper.convertValue(readUsage, Map.class));
                sendEvent(updateStream.emitter(), "done", sendDone);
            }
            updateStream.emitter().complete();
        } catch (RuntimeException readError) {
            manageChat.failRun(
                    updateStream.userId(), updateStream.run().session().epoch(),
                    updateStream.run().assistantMessage().runId(), "AI_PERSIST_FAILED");
            sendSafeError(updateStream.emitter(), updateStream.run().assistantMessage().runId(),
                    "AI_PERSIST_FAILED", "回答保存失败", true);
            updateStream.emitter().complete();
        } finally {
            activeStreams.remove(updateStream.userId(), updateStream);
        }
    }

    private void finishError(
            ActiveStream updateStream,
            String readReason,
            String readMessage,
            boolean canRetry) {
        if (!updateStream.terminal().compareAndSet(false, true)) {
            return;
        }
        try {
            boolean hasFailed = manageChat.failRun(
                    updateStream.userId(), updateStream.run().session().epoch(),
                    updateStream.run().assistantMessage().runId(), readReason);
            if (hasFailed) {
                sendSafeError(updateStream.emitter(), updateStream.run().assistantMessage().runId(),
                        readReason, readMessage, canRetry);
            }
            updateStream.emitter().complete();
        } finally {
            activeStreams.remove(updateStream.userId(), updateStream);
        }
    }

    private void replayRun(ActiveStream updateStream) {
        AiMessageResponse readMessage = updateStream.run().assistantMessage();
        updateStream.terminal().set(true);
        if (readMessage.status() == AiMessageStatus.COMPLETED) {
            Map<String, Object> sendDelta = new LinkedHashMap<>();
            sendDelta.put("runId", readMessage.runId());
            sendDelta.put("seq", 1);
            sendDelta.put("text", readMessage.content());
            sendDelta.put("snapshot", true);
            sendEvent(updateStream.emitter(), "delta", sendDelta);
            Map<String, Object> sendDone = new LinkedHashMap<>();
            sendDone.put("runId", readMessage.runId());
            sendDone.put("messageId", readMessage.id());
            sendDone.put("finishReason", readMessage.finishReason());
            sendDone.put("usage", Map.of());
            sendEvent(updateStream.emitter(), "done", sendDone);
        } else if (readMessage.status() == AiMessageStatus.CANCELLED) {
            sendEvent(updateStream.emitter(), "cancelled", Map.of(
                    "runId", readMessage.runId(),
                    "reason", readMessage.errorCode() == null ? "CANCELLED" : readMessage.errorCode()));
        } else {
            sendSafeError(updateStream.emitter(), readMessage.runId(),
                    readMessage.errorCode() == null ? "AI_RUN_FAILED" : readMessage.errorCode(),
                    "上一轮回答未完成", false);
        }
        updateStream.emitter().complete();
    }

    private void sendStart(ActiveStream readStream) {
        Map<String, Object> sendStart = new LinkedHashMap<>();
        sendStart.put("runId", readStream.run().assistantMessage().runId());
        sendStart.put("requestId", readStream.run().userMessage().requestId());
        sendStart.put("userMessageId", readStream.run().userMessage().id());
        sendStart.put("assistantMessageId", readStream.run().assistantMessage().id());
        sendEvent(readStream.emitter(), "start", sendStart);
    }

    private void scheduleSummary(Long readUserId, Long readEpoch) {
        AiChatService.SummaryWork readWork = manageChat.prepareSummary(readUserId, readEpoch);
        if (readWork == null) {
            return;
        }
        List<AgentSummaryClient.SummaryMessage> readMessages = readWork.messages().stream()
                .map(readMessage -> new AgentSummaryClient.SummaryMessage(
                        readMessage.role(), readMessage.content()))
                .toList();
        AgentSummaryClient.SummaryRequest readRequest = new AgentSummaryClient.SummaryRequest(
                readWork.previousSummary(),
                readMessages,
                readWork.throughMessageId().toString());
        summaryClient.summarize(readRequest).thenAccept(readResponse -> {
            if (readResponse != null
                    && readWork.throughMessageId().toString()
                    .equals(readResponse.throughMessageId())) {
                manageChat.updateSummary(readWork, readResponse.summary());
            }
        }).exceptionally(readError -> null);
    }

    private void sendSafeError(
            SseEmitter sendEmitter,
            String readRunId,
            String readReason,
            String readMessage,
            boolean canRetry) {
        Map<String, Object> sendError = new LinkedHashMap<>();
        sendError.put("runId", readRunId);
        sendError.put("reason", readReason);
        sendError.put("message", readMessage);
        sendError.put("retryable", canRetry);
        sendEvent(sendEmitter, "error", sendError);
    }

    private void sendEvent(SseEmitter sendEmitter, String readName, Object writeData) {
        try {
            String writeJson = objectMapper.writeValueAsString(writeData);
            synchronized (sendEmitter) {
                sendEmitter.send(SseEmitter.event()
                        .name(readName)
                        .data(writeJson, MediaType.APPLICATION_JSON));
            }
        } catch (IOException readError) {
            throw new IllegalStateException("无法发送 AI SSE 事件", readError);
        }
    }

    private void configureEmitter(ActiveStream updateStream) {
        updateStream.emitter().onTimeout(() -> stopStream(updateStream.userId()));
        updateStream.emitter().onError(readError -> stopStream(updateStream.userId()));
        updateStream.emitter().onCompletion(() -> {
            if (!updateStream.terminal().get()) {
                stopStream(updateStream.userId());
            }
        });
    }

    private void stopStream(Long updateUserId) {
        ActiveStream updateStream = activeStreams.remove(updateUserId);
        if (updateStream == null || !updateStream.terminal().compareAndSet(false, true)) {
            return;
        }
        CompletableFuture<Void> updateFuture = updateStream.future().get();
        if (updateFuture != null) {
            updateFuture.cancel(true);
        }
        manageChat.cancelRun(updateUserId);
    }

    private static final class ActiveStream {
        private final Long userId;
        private final AiChatService.RunStart run;
        private final SseEmitter emitter;
        private final AtomicBoolean terminal = new AtomicBoolean(false);
        private final AtomicReference<CompletableFuture<Void>> future = new AtomicReference<>();
        private final StringBuilder content = new StringBuilder();

        private ActiveStream(Long userId, AiChatService.RunStart run, SseEmitter emitter) {
            this.userId = userId;
            this.run = run;
            this.emitter = emitter;
        }

        Long userId() { return userId; }
        AiChatService.RunStart run() { return run; }
        SseEmitter emitter() { return emitter; }
        AtomicBoolean terminal() { return terminal; }
        AtomicReference<CompletableFuture<Void>> future() { return future; }
        StringBuilder content() { return content; }
    }
}
