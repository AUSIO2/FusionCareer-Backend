package com.fusioncareer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.dto.req.AiMessageRequest;
import com.fusioncareer.dto.res.AiMessagePageResponse;
import com.fusioncareer.dto.res.AiMessageResponse;
import com.fusioncareer.dto.res.AiSessionResponse;
import com.fusioncareer.entity.AiMessageEntity;
import com.fusioncareer.entity.AiSessionEntity;
import com.fusioncareer.entity.UserChangeActionEntity;
import com.fusioncareer.enums.AiMessageRole;
import com.fusioncareer.enums.AiMessageStatus;
import com.fusioncareer.enums.ChangeActionStatus;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.mapper.AiMessageMapper;
import com.fusioncareer.mapper.AiSessionMapper;
import com.fusioncareer.mapper.UserChangeActionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Collections;
import java.util.regex.Pattern;

/**
 * 当前用户唯一 Assistant Session、运行租约与消息状态机。
 */
@Service
@RequiredArgsConstructor
public class AiChatService {

    private static final int MAX_MESSAGE_LENGTH = 8_000;
    private static final int MAX_FILE_COUNT = 5;
    private static final int MAX_CONTEXT_MESSAGES = 12;
    private static final int MAX_CONTEXT_CHARS = 20_000;
    private static final int SUMMARY_TRIGGER_MESSAGES = 20;
    private static final int SUMMARY_KEEP_MESSAGES = 6;
    private static final int MAX_SUMMARY_MESSAGES = 40;
    private static final int MAX_SUMMARY_LENGTH = 2_000;
    private static final Duration RUN_LEASE = Duration.ofMinutes(2);
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final AiSessionMapper sessionMapper;
    private final AiMessageMapper messageMapper;
    private final UserChangeActionMapper actionMapper;
    private final ResumeFileService fileService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public AiSessionResponse readSession(Long readUserId) {
        requireAccount(readUserId);
        return buildSession(sessionMapper.selectById(readUserId));
    }

    @Transactional(readOnly = true)
    public AiMessagePageResponse readMessages(
            Long readUserId,
            Long readBeforeId,
            int readSize) {
        requireAccount(readUserId);
        AiSessionEntity readSession = sessionMapper.selectById(readUserId);
        if (readSession == null) {
            return new AiMessagePageResponse(List.of(), null, false);
        }
        int loadSize = Math.max(1, Math.min(readSize, 50));
        LambdaQueryWrapper<AiMessageEntity> readQuery =
                new LambdaQueryWrapper<AiMessageEntity>()
                        .eq(AiMessageEntity::getUserId, readUserId)
                        .eq(AiMessageEntity::getEpoch, readSession.getEpoch())
                        .orderByDesc(AiMessageEntity::getId)
                        .last("LIMIT " + (loadSize + 1));
        if (readBeforeId != null) {
            readQuery.lt(AiMessageEntity::getId, readBeforeId);
        }
        List<AiMessageEntity> readMessages = messageMapper.selectList(readQuery);
        boolean hasMore = readMessages.size() > loadSize;
        if (hasMore) {
            readMessages = new ArrayList<>(readMessages.subList(0, loadSize));
        }
        List<AiMessageResponse> readResponses = readMessages.stream()
                .map(this::buildMessage)
                .toList();
        Long readNextId = hasMore && !readMessages.isEmpty()
                ? readMessages.get(readMessages.size() - 1).getId() : null;
        return new AiMessagePageResponse(readResponses, readNextId, hasMore);
    }

    @Transactional(readOnly = true)
    public ChatContext readContext(
            Long readUserId,
            Long readEpoch,
            Long readBeforeId) {
        AiSessionEntity readSession = sessionMapper.selectById(readUserId);
        if (readSession == null || !Objects.equals(readSession.getEpoch(), readEpoch)) {
            return new ChatContext("", List.of());
        }
        LambdaQueryWrapper<AiMessageEntity> readQuery =
                new LambdaQueryWrapper<AiMessageEntity>()
                        .eq(AiMessageEntity::getUserId, readUserId)
                        .eq(AiMessageEntity::getEpoch, readEpoch)
                        .eq(AiMessageEntity::getStatus, AiMessageStatus.COMPLETED)
                        .lt(AiMessageEntity::getId, readBeforeId)
                        .orderByDesc(AiMessageEntity::getId)
                        .last("LIMIT " + MAX_CONTEXT_MESSAGES);
        if (readSession.getSummaryThroughMessageId() != null) {
            readQuery.gt(AiMessageEntity::getId, readSession.getSummaryThroughMessageId());
        }
        List<AiMessageEntity> readMessages = messageMapper.selectList(readQuery);
        List<AiMessageEntity> readSelected = new ArrayList<>();
        int readChars = 0;
        for (AiMessageEntity readMessage : readMessages) {
            int readLength = readMessage.getContent() == null ? 0 : readMessage.getContent().length();
            if (!readSelected.isEmpty() && readChars + readLength > MAX_CONTEXT_CHARS) {
                break;
            }
            readSelected.add(readMessage);
            readChars += readLength;
        }
        Collections.reverse(readSelected);
        List<ContextMessage> readHistory = readSelected.stream()
                .map(readMessage -> new ContextMessage(
                        readMessage.getRole() == AiMessageRole.USER ? "user" : "assistant",
                        readMessage.getContent()))
                .toList();
        return new ChatContext(
                readSession.getSummary() == null ? "" : readSession.getSummary(),
                readHistory);
    }

    @Transactional(readOnly = true)
    public SummaryWork prepareSummary(Long readUserId, Long readEpoch) {
        AiSessionEntity readSession = sessionMapper.selectById(readUserId);
        if (readSession == null || !Objects.equals(readSession.getEpoch(), readEpoch)) {
            return null;
        }
        LambdaQueryWrapper<AiMessageEntity> readQuery =
                new LambdaQueryWrapper<AiMessageEntity>()
                        .eq(AiMessageEntity::getUserId, readUserId)
                        .eq(AiMessageEntity::getEpoch, readEpoch)
                        .eq(AiMessageEntity::getStatus, AiMessageStatus.COMPLETED)
                        .in(AiMessageEntity::getRole, AiMessageRole.USER, AiMessageRole.ASSISTANT)
                        .orderByAsc(AiMessageEntity::getId)
                        .last("LIMIT " + MAX_SUMMARY_MESSAGES);
        if (readSession.getSummaryThroughMessageId() != null) {
            readQuery.gt(AiMessageEntity::getId, readSession.getSummaryThroughMessageId());
        }
        List<AiMessageEntity> readMessages = messageMapper.selectList(readQuery).stream()
                .filter(readMessage -> StringUtils.hasText(readMessage.getContent()))
                .toList();
        int readChars = readMessages.stream()
                .mapToInt(readMessage -> readMessage.getContent().length())
                .sum();
        if (readMessages.size() < SUMMARY_TRIGGER_MESSAGES
                && readChars <= MAX_CONTEXT_CHARS) {
            return null;
        }
        int readKeep = Math.min(SUMMARY_KEEP_MESSAGES, Math.max(2, readMessages.size() / 3));
        int readCutoff = Math.max(1, readMessages.size() - readKeep);
        List<AiMessageEntity> readSummaryMessages = readMessages.subList(0, readCutoff);
        AiMessageEntity readThrough = readSummaryMessages.get(readSummaryMessages.size() - 1);
        List<ContextMessage> readContext = readSummaryMessages.stream()
                .map(readMessage -> new ContextMessage(
                        readMessage.getRole() == AiMessageRole.USER ? "user" : "assistant",
                        readMessage.getContent()))
                .toList();
        return new SummaryWork(
                readUserId,
                readEpoch,
                readSession.getSummaryThroughMessageId(),
                readThrough.getId(),
                readSession.getSummary() == null ? "" : readSession.getSummary(),
                readContext);
    }

    @Transactional
    public boolean updateSummary(SummaryWork readWork, String updateSummary) {
        if (readWork == null || !StringUtils.hasText(updateSummary)) {
            return false;
        }
        String readSummary = updateSummary.trim();
        if (readSummary.length() > MAX_SUMMARY_LENGTH) {
            readSummary = readSummary.substring(0, MAX_SUMMARY_LENGTH);
        }
        UpdateWrapper<AiSessionEntity> update = new UpdateWrapper<>();
        update.eq("user_id", readWork.userId())
                .eq("epoch", readWork.epoch())
                .set("summary", readSummary)
                .set("summary_through_message_id", readWork.throughMessageId())
                .set("updated_at", LocalDateTime.now());
        if (readWork.expectedThroughMessageId() == null) {
            update.isNull("summary_through_message_id");
        } else {
            update.eq("summary_through_message_id", readWork.expectedThroughMessageId());
        }
        return sessionMapper.update(null, update) == 1;
    }

    @Transactional
    public RunStart startRun(Long updateUserId, AiMessageRequest readRequest) {
        requireAccount(updateUserId);
        String updateRequestId = validateRequestId(readRequest.clientRequestId());
        String updateContent = validateContent(readRequest.content());
        List<Long> updateFileIds = normalizeFiles(readRequest.fileIds());

        sessionMapper.createSession(updateUserId);
        AiSessionEntity readSession = sessionMapper.selectById(updateUserId);
        recoverLease(readSession);
        AiMessageEntity readUserMessage = findMessage(
                updateUserId, readSession.getEpoch(), updateRequestId, AiMessageRole.USER);
        if (readUserMessage != null) {
            return reuseRun(readSession, readUserMessage, updateContent, updateFileIds);
        }
        validateFiles(updateUserId, updateFileIds);
        String createRunId = UUID.randomUUID().toString();
        LocalDateTime updateLease = LocalDateTime.now().plus(RUN_LEASE);
        if (sessionMapper.acquireRun(
                updateUserId, readSession.getEpoch(), createRunId, updateLease) != 1) {
            throw buildConflict("已有一轮 AI 对话正在运行");
        }

        AiMessageEntity createUserMessage = createMessage(
                updateUserId, readSession.getEpoch(), createRunId, updateRequestId,
                AiMessageRole.USER, AiMessageStatus.COMPLETED,
                updateContent, writeFiles(updateFileIds));
        AiMessageEntity createAssistantMessage = createMessage(
                updateUserId, readSession.getEpoch(), createRunId, updateRequestId,
                AiMessageRole.ASSISTANT, AiMessageStatus.PENDING, "", "[]");
        if (messageMapper.insert(createUserMessage) != 1
                || messageMapper.insert(createAssistantMessage) != 1) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR, "AI 消息创建失败");
        }
        return new RunStart(
                buildSession(sessionMapper.selectById(updateUserId)),
                buildMessage(createUserMessage),
                buildMessage(createAssistantMessage),
                false);
    }

    @Transactional
    public boolean markStreaming(Long updateUserId, Long readEpoch, String readRunId) {
        if (sessionMapper.renewRun(
                updateUserId, readEpoch, readRunId, LocalDateTime.now().plus(RUN_LEASE)) != 1) {
            return false;
        }
        UpdateWrapper<AiMessageEntity> updateMessage = new UpdateWrapper<>();
        updateMessage.eq("user_id", updateUserId)
                .eq("epoch", readEpoch)
                .eq("run_id", readRunId)
                .eq("role", AiMessageRole.ASSISTANT.getCode())
                .eq("status", AiMessageStatus.PENDING.getCode())
                .set("status", AiMessageStatus.STREAMING.getCode())
                .set("updated_at", LocalDateTime.now());
        return messageMapper.update(null, updateMessage) == 1;
    }

    @Transactional
    public boolean completeRun(
            Long updateUserId,
            Long readEpoch,
            String readRunId,
            String updateContent,
            String readModel,
            String readFinishReason,
            Integer readPromptTokens,
            Integer readCompletionTokens) {
        if (sessionMapper.releaseRun(updateUserId, readEpoch, readRunId) != 1) {
            return false;
        }
        UpdateWrapper<AiMessageEntity> updateMessage = new UpdateWrapper<>();
        updateMessage.eq("user_id", updateUserId)
                .eq("epoch", readEpoch)
                .eq("run_id", readRunId)
                .eq("role", AiMessageRole.ASSISTANT.getCode())
                .in("status", AiMessageStatus.PENDING.getCode(), AiMessageStatus.STREAMING.getCode())
                .set("status", AiMessageStatus.COMPLETED.getCode())
                .set("content", updateContent == null ? "" : updateContent)
                .set("model", readModel)
                .set("finish_reason", readFinishReason)
                .set("prompt_tokens", readPromptTokens)
                .set("completion_tokens", readCompletionTokens)
                .set("error_code", null)
                .set("updated_at", LocalDateTime.now());
        if (messageMapper.update(null, updateMessage) != 1) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR, "AI 消息完成状态写入失败");
        }
        return true;
    }

    @Transactional
    public boolean failRun(
            Long updateUserId,
            Long readEpoch,
            String readRunId,
            String readErrorCode) {
        if (sessionMapper.releaseRun(updateUserId, readEpoch, readRunId) != 1) {
            return false;
        }
        UpdateWrapper<AiMessageEntity> updateMessage = new UpdateWrapper<>();
        updateMessage.eq("user_id", updateUserId)
                .eq("epoch", readEpoch)
                .eq("run_id", readRunId)
                .eq("role", AiMessageRole.ASSISTANT.getCode())
                .in("status", AiMessageStatus.PENDING.getCode(), AiMessageStatus.STREAMING.getCode())
                .set("status", AiMessageStatus.FAILED.getCode())
                .set("content", "")
                .set("error_code", readErrorCode)
                .set("finish_reason", "error")
                .set("updated_at", LocalDateTime.now());
        if (messageMapper.update(null, updateMessage) != 1) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR, "AI 消息失败状态写入失败");
        }
        rejectActions(updateUserId, readEpoch, readRunId);
        return true;
    }

    @Transactional
    public AiSessionResponse cancelRun(Long updateUserId) {
        requireAccount(updateUserId);
        AiSessionEntity readSession = sessionMapper.selectOne(
                new LambdaQueryWrapper<AiSessionEntity>()
                        .eq(AiSessionEntity::getUserId, updateUserId)
                        .last("FOR UPDATE"));
        if (readSession == null || readSession.getActiveRunId() == null) {
            return buildSession(readSession);
        }
        String readRunId = readSession.getActiveRunId();
        UpdateWrapper<AiMessageEntity> updateMessage = new UpdateWrapper<>();
        updateMessage.eq("user_id", updateUserId)
                .eq("epoch", readSession.getEpoch())
                .eq("run_id", readRunId)
                .eq("role", AiMessageRole.ASSISTANT.getCode())
                .in("status", AiMessageStatus.PENDING.getCode(), AiMessageStatus.STREAMING.getCode())
                .set("status", AiMessageStatus.CANCELLED.getCode())
                .set("content", "")
                .set("error_code", "USER_CANCELLED")
                .set("finish_reason", "cancelled")
                .set("updated_at", LocalDateTime.now());
        messageMapper.update(null, updateMessage);
        sessionMapper.releaseRun(
                updateUserId, readSession.getEpoch(), readRunId);
        rejectActions(updateUserId, readSession.getEpoch(), readRunId);
        return buildSession(sessionMapper.selectById(updateUserId));
    }

    @Transactional
    public AiSessionResponse clearSession(Long updateUserId) {
        requireAccount(updateUserId);
        AiSessionEntity readSession = sessionMapper.selectOne(
                new LambdaQueryWrapper<AiSessionEntity>()
                        .eq(AiSessionEntity::getUserId, updateUserId)
                        .last("FOR UPDATE"));
        if (readSession == null) {
            return buildSession(null);
        }
        supersedeActions(updateUserId, readSession.getEpoch());
        cancelMessages(updateUserId, readSession.getEpoch(), "SESSION_CLEARED");
        UpdateWrapper<AiSessionEntity> updateSession = new UpdateWrapper<>();
        updateSession.eq("user_id", updateUserId)
                .eq("epoch", readSession.getEpoch())
                .set("epoch", readSession.getEpoch() + 1)
                .set("summary", null)
                .set("summary_through_message_id", null)
                .set("active_run_id", null)
                .set("lease_until", null)
                .set("last_message_at", null)
                .set("updated_at", LocalDateTime.now());
        if (sessionMapper.update(null, updateSession) != 1) {
            throw buildConflict("AI Session 已发生变化，请重试");
        }
        return buildSession(sessionMapper.selectById(updateUserId));
    }

    @Transactional
    public AiSessionResponse resetSession(Long updateUserId) {
        requireAccount(updateUserId);
        AiSessionEntity readSession = sessionMapper.selectOne(
                new LambdaQueryWrapper<AiSessionEntity>()
                        .eq(AiSessionEntity::getUserId, updateUserId)
                        .last("FOR UPDATE"));
        if (readSession != null) {
            supersedeActions(updateUserId, readSession.getEpoch());
        }
        messageMapper.delete(new LambdaQueryWrapper<AiMessageEntity>()
                .eq(AiMessageEntity::getUserId, updateUserId));
        sessionMapper.deleteById(updateUserId);
        return buildSession(null);
    }

    private RunStart reuseRun(
            AiSessionEntity readSession,
            AiMessageEntity readUserMessage,
            String readContent,
            List<Long> readFileIds) {
        if (!Objects.equals(readUserMessage.getContent(), readContent)
                || !Objects.equals(parseFiles(readUserMessage.getAttachmentIds()), readFileIds)) {
            throw buildConflict("clientRequestId 已被不同消息使用");
        }
        AiMessageEntity readAssistantMessage = findMessage(
                readUserMessage.getUserId(), readUserMessage.getEpoch(),
                readUserMessage.getRequestId(), AiMessageRole.ASSISTANT);
        if (readAssistantMessage == null) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR, "AI 助手消息不存在");
        }
        if (readAssistantMessage.getStatus() == AiMessageStatus.PENDING
                || readAssistantMessage.getStatus() == AiMessageStatus.STREAMING) {
            throw buildConflict("该请求仍在运行");
        }
        return new RunStart(
                buildSession(readSession),
                buildMessage(readUserMessage),
                buildMessage(readAssistantMessage),
                true);
    }

    private void recoverLease(AiSessionEntity readSession) {
        if (readSession.getActiveRunId() == null
                || readSession.getLeaseUntil() == null
                || !readSession.getLeaseUntil().isBefore(LocalDateTime.now())) {
            return;
        }
        UpdateWrapper<AiMessageEntity> updateMessage = new UpdateWrapper<>();
        updateMessage.eq("user_id", readSession.getUserId())
                .eq("epoch", readSession.getEpoch())
                .eq("run_id", readSession.getActiveRunId())
                .eq("role", AiMessageRole.ASSISTANT.getCode())
                .in("status", AiMessageStatus.PENDING.getCode(), AiMessageStatus.STREAMING.getCode())
                .set("status", AiMessageStatus.FAILED.getCode())
                .set("content", "")
                .set("error_code", "LEASE_EXPIRED")
                .set("finish_reason", "error")
                .set("updated_at", LocalDateTime.now());
        messageMapper.update(null, updateMessage);
        rejectActions(readSession.getUserId(), readSession.getEpoch(), readSession.getActiveRunId());
    }

    private void supersedeActions(Long updateUserId, Long readEpoch) {
        UpdateWrapper<UserChangeActionEntity> updateActions = new UpdateWrapper<>();
        updateActions.eq("user_id", updateUserId)
                .eq("epoch", readEpoch)
                .eq("status", ChangeActionStatus.PENDING.getCode())
                .set("status", ChangeActionStatus.SUPERSEDED.getCode())
                .set("error_code", "SESSION_CLEARED")
                .set("updated_at", LocalDateTime.now());
        actionMapper.update(null, updateActions);
    }

    private void cancelMessages(Long updateUserId, Long readEpoch, String readErrorCode) {
        UpdateWrapper<AiMessageEntity> updateMessages = new UpdateWrapper<>();
        updateMessages.eq("user_id", updateUserId)
                .eq("epoch", readEpoch)
                .eq("role", AiMessageRole.ASSISTANT.getCode())
                .in("status", AiMessageStatus.PENDING.getCode(), AiMessageStatus.STREAMING.getCode())
                .set("status", AiMessageStatus.CANCELLED.getCode())
                .set("content", "")
                .set("error_code", readErrorCode)
                .set("finish_reason", "cancelled")
                .set("updated_at", LocalDateTime.now());
        messageMapper.update(null, updateMessages);
    }

    private void rejectActions(Long updateUserId, Long readEpoch, String readRunId) {
        UpdateWrapper<UserChangeActionEntity> updateActions = new UpdateWrapper<>();
        updateActions.eq("user_id", updateUserId)
                .eq("epoch", readEpoch)
                .eq("run_id", readRunId)
                .eq("status", ChangeActionStatus.PENDING.getCode())
                .set("status", ChangeActionStatus.REJECTED.getCode())
                .set("error_code", "RUN_NOT_COMPLETED")
                .set("updated_at", LocalDateTime.now());
        actionMapper.update(null, updateActions);
    }

    private AiMessageEntity findMessage(
            Long readUserId,
            Long readEpoch,
            String readRequestId,
            AiMessageRole readRole) {
        return messageMapper.selectOne(new LambdaQueryWrapper<AiMessageEntity>()
                .eq(AiMessageEntity::getUserId, readUserId)
                .eq(AiMessageEntity::getEpoch, readEpoch)
                .eq(AiMessageEntity::getRequestId, readRequestId)
                .eq(AiMessageEntity::getRole, readRole));
    }

    private AiMessageEntity createMessage(
            Long createUserId,
            Long createEpoch,
            String createRunId,
            String createRequestId,
            AiMessageRole createRole,
            AiMessageStatus createStatus,
            String createContent,
            String createAttachments) {
        AiMessageEntity createMessage = new AiMessageEntity();
        createMessage.setUserId(createUserId);
        createMessage.setEpoch(createEpoch);
        createMessage.setRunId(createRunId);
        createMessage.setRequestId(createRequestId);
        createMessage.setRole(createRole);
        createMessage.setStatus(createStatus);
        createMessage.setContent(createContent);
        createMessage.setAttachmentIds(createAttachments);
        return createMessage;
    }

    private List<Long> normalizeFiles(List<Long> readFileIds) {
        if (readFileIds == null || readFileIds.isEmpty()) {
            return List.of();
        }
        if (readFileIds.size() > MAX_FILE_COUNT) {
            throw buildInvalid("单次最多允许 5 个附件");
        }
        Set<Long> readUniqueIds = new LinkedHashSet<>(readFileIds);
        if (readUniqueIds.size() != readFileIds.size() || readUniqueIds.contains(null)) {
            throw buildInvalid("附件 ID 不能为空或重复");
        }
        return List.copyOf(readUniqueIds);
    }

    private void validateFiles(Long readUserId, List<Long> readFileIds) {
        readFileIds.forEach(readFileId -> fileService.getOwnFile(readUserId, readFileId));
    }

    private String validateRequestId(String readRequestId) {
        if (!StringUtils.hasText(readRequestId)
                || !REQUEST_ID_PATTERN.matcher(readRequestId).matches()) {
            throw buildInvalid("clientRequestId 格式无效");
        }
        return readRequestId;
    }

    private String validateContent(String readContent) {
        if (!StringUtils.hasText(readContent)) {
            throw buildInvalid("消息内容不能为空");
        }
        String updateContent = readContent.trim();
        if (updateContent.length() > MAX_MESSAGE_LENGTH) {
            throw buildInvalid("消息内容最多允许 8000 个字符");
        }
        return updateContent;
    }

    private String writeFiles(List<Long> writeFileIds) {
        try {
            return objectMapper.writeValueAsString(writeFileIds);
        } catch (JsonProcessingException readError) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR, "附件 ID 无法序列化");
        }
    }

    private List<Long> parseFiles(String readFileIds) {
        if (!StringUtils.hasText(readFileIds)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(readFileIds, new TypeReference<>() { });
        } catch (JsonProcessingException readError) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR, "附件 ID 格式错误");
        }
    }

    private AiSessionResponse buildSession(AiSessionEntity readSession) {
        if (readSession == null) {
            return new AiSessionResponse(false, null, false, null, null, null, null, null);
        }
        boolean hasActiveRun = readSession.getActiveRunId() != null
                && readSession.getLeaseUntil() != null
                && readSession.getLeaseUntil().isAfter(LocalDateTime.now());
        return new AiSessionResponse(
                true,
                readSession.getEpoch(),
                hasActiveRun,
                hasActiveRun ? readSession.getActiveRunId() : null,
                hasActiveRun ? readSession.getLeaseUntil() : null,
                readSession.getLastMessageAt(),
                readSession.getCreatedAt(),
                readSession.getUpdatedAt());
    }

    private AiMessageResponse buildMessage(AiMessageEntity readMessage) {
        return new AiMessageResponse(
                readMessage.getId(),
                readMessage.getEpoch(),
                readMessage.getRunId(),
                readMessage.getRequestId(),
                readMessage.getRole(),
                readMessage.getStatus(),
                readMessage.getContent(),
                parseFiles(readMessage.getAttachmentIds()),
                readMessage.getModel(),
                readMessage.getFinishReason(),
                readMessage.getPromptTokens(),
                readMessage.getCompletionTokens(),
                readMessage.getErrorCode(),
                readMessage.getCreatedAt(),
                readMessage.getUpdatedAt());
    }

    private void requireAccount(Long readUserId) {
        if (userService.getUserById(readUserId) == null) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "用户不存在");
        }
    }

    private ServiceException buildInvalid(String readMessage) {
        return ServiceException.of(ResultCode.VALIDATE_FAILED, readMessage);
    }

    private ServiceException buildConflict(String readMessage) {
        return ServiceException.of(ResultCode.CONFLICT, readMessage);
    }

    public record RunStart(
            AiSessionResponse session,
            AiMessageResponse userMessage,
            AiMessageResponse assistantMessage,
            boolean reused) {
    }

    public record ChatContext(String summary, List<ContextMessage> history) {
    }

    public record ContextMessage(String role, String content) {
    }

    public record SummaryWork(
            Long userId,
            Long epoch,
            Long expectedThroughMessageId,
            Long throughMessageId,
            String previousSummary,
            List<ContextMessage> messages) {
    }
}
