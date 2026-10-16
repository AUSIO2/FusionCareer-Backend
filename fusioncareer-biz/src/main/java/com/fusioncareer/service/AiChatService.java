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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
    private static final int MAX_ACTION_STATES = 100;
    private static final int MAX_HISTORY_ACTION_IDS = 32;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Pattern ACTION_ID_PATTERN = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final AiSessionMapper sessionMapper;
    private final AiMessageMapper messageMapper;
    private final UserChangeActionMapper actionMapper;
    private final ResumeFileService fileService;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final JobRecommendationService recommendations;
    private final ResumeAdvicePresentationService advicePresentations;
    private final jakarta.validation.Validator validator;

    @org.springframework.beans.factory.annotation.Value("${ai-chat.run-lease-seconds:240}")
    private long runLeaseSeconds = 240;

    @org.springframework.beans.factory.annotation.Value("${ai-chat.daily-message-limit:100}")
    private int dailyMessageLimit = 100;

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
            return new ChatContext("", List.of(), List.of());
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
                        readMessage.getContent(), readActionIds(readMessage)))
                .toList();
        return new ChatContext(
                readSession.getSummary() == null ? "" : readSession.getSummary(),
                readHistory,
                readActionStates(readUserId, readEpoch));
    }

    private List<String> readActionIds(AiMessageEntity readMessage) {
        if (readMessage.getRole() != AiMessageRole.ASSISTANT
                || !StringUtils.hasText(readMessage.getPresentation())) return List.of();
        try {
            var readPresentation = objectMapper.readTree(readMessage.getPresentation());
            if (readPresentation == null) return List.of();
            var readIds = readPresentation.path("actionIds");
            if (!readIds.isArray()) return List.of();
            Set<String> readSafeIds = new LinkedHashSet<>();
            for (var readId : readIds) {
                if (!readId.isTextual() || !ACTION_ID_PATTERN.matcher(readId.asText()).matches()) continue;
                try {
                    if (Long.parseLong(readId.asText()) <= 0) continue;
                } catch (NumberFormatException readInvalidId) {
                    continue;
                }
                readSafeIds.add(readId.asText());
                if (readSafeIds.size() == MAX_HISTORY_ACTION_IDS) break;
            }
            return List.copyOf(readSafeIds);
        } catch (JsonProcessingException readInvalidPresentation) {
            return List.of();
        }
    }

    private List<ActionState> readActionStates(Long readUserId, Long readEpoch) {
        // Card decisions change independently of message text and asynchronous summaries.
        // Send only the current state projection; mutation snapshots remain private.
        return actionMapper.selectList(new LambdaQueryWrapper<UserChangeActionEntity>()
                        .select(UserChangeActionEntity::getId, UserChangeActionEntity::getStatus,
                                UserChangeActionEntity::getToolName)
                        .eq(UserChangeActionEntity::getUserId, readUserId)
                        .eq(UserChangeActionEntity::getEpoch, readEpoch)
                        .orderByDesc(UserChangeActionEntity::getUpdatedAt)
                        .orderByDesc(UserChangeActionEntity::getId)
                        .last("LIMIT " + MAX_ACTION_STATES))
                .stream()
                .map(readAction -> new ActionState(
                        readAction.getId().toString(),
                        readAction.getStatus() == null ? "UNKNOWN" : readAction.getStatus().name(),
                        readAction.getStatus() == ChangeActionStatus.PENDING,
                        readAction.getToolName()))
                .toList();
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
        if (!validator.validate(readRequest).isEmpty()) throw buildInvalid("消息参数格式无效");
        if (readRequest.interaction() != null && "resume_advice".equals(readRequest.interaction().type())) {
            if (readRequest.jobId() == null) throw buildInvalid("请从当前岗位详情发起简历建议");
            if ("file".equals(readRequest.interaction().source())) {
                try {
                    Long readSelectedFile = Long.valueOf(readRequest.interaction().fileIds().get(0));
                    if (readSelectedFile <= 0 || !updateFileIds.contains(readSelectedFile)) {
                        throw buildInvalid("简历文件必须来自本轮选中的附件");
                    }
                } catch (NumberFormatException error) { throw buildInvalid("简历文件 ID 无效"); }
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (readRequest.interaction() != null) {
            var readInteraction = readRequest.interaction();
            Map<String, Object> writeInteraction = new LinkedHashMap<>();
            writeInteraction.put("schemaVersion", readInteraction.schemaVersion());
            writeInteraction.put("type", readInteraction.type());
            if (readInteraction.preferences() != null) writeInteraction.put("preferences", readInteraction.preferences());
            if (readInteraction.source() != null) writeInteraction.put("source", readInteraction.source());
            if (readInteraction.fileIds() != null) writeInteraction.put("fileIds", readInteraction.fileIds());
            metadata.put("interaction", writeInteraction);
        }
        if (readRequest.jobId() != null) {
            try {
                if (recommendations.hydrate(Map.of("jobIds", List.of(readRequest.jobId())))
                        .get("jobs") instanceof List<?> cards
                        && Boolean.FALSE.equals(((Map<?, ?>) cards.get(0)).get("available"))) {
                    throw buildInvalid("当前岗位已不可见");
                }
            } catch (NumberFormatException error) { throw buildInvalid("岗位 ID 格式无效"); }
            metadata.put("jobId", readRequest.jobId());
        }
        String inputMetadata = writeJson(metadata);

        sessionMapper.createSession(updateUserId);
        AiSessionEntity readSession = sessionMapper.selectOne(
                new LambdaQueryWrapper<AiSessionEntity>()
                        .eq(AiSessionEntity::getUserId, updateUserId)
                        .last("FOR UPDATE"));
        recoverLease(readSession);
        AiMessageEntity readUserMessage = findMessage(
                updateUserId, readSession.getEpoch(), updateRequestId, AiMessageRole.USER);
        if (readUserMessage != null) {
            return reuseRun(readSession, readUserMessage, updateContent, updateFileIds, inputMetadata);
        }
        consumeDailyQuota(readSession);
        validateFiles(updateUserId, updateFileIds);
        String createRunId = UUID.randomUUID().toString();
        LocalDateTime updateLease = LocalDateTime.now().plusSeconds(runLeaseSeconds);
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
        createUserMessage.setInputMetadata(inputMetadata);
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

    private void consumeDailyQuota(AiSessionEntity updateSession) {
        LocalDateTime readNow = LocalDateTime.now(SHANGHAI);
        if (updateSession.getAiBannedUntil() != null
                && updateSession.getAiBannedUntil().isAfter(readNow)) {
            throw ServiceException.of(ResultCode.TOO_MANY_REQUESTS,
                    "AI 功能已临时限制，请于 " + updateSession.getAiBannedUntil() + " 后重试");
        }
        LocalDate readToday = readNow.toLocalDate();
        int readCount = Objects.equals(updateSession.getUsageDate(), readToday)
                ? Objects.requireNonNullElse(updateSession.getDailyUsageCount(), 0) : 0;
        if (readCount >= dailyMessageLimit) {
            throw ServiceException.of(ResultCode.TOO_MANY_REQUESTS,
                    "今日 AI 调用已达到 " + dailyMessageLimit + " 次，请明天再试");
        }
        updateSession.setUsageDate(readToday);
        updateSession.setDailyUsageCount(readCount + 1);
        if (sessionMapper.updateById(updateSession) != 1) {
            throw buildConflict("AI 使用额度已发生变化，请重试");
        }
    }

    @Transactional
    public TopicGuardState recordTopicDecision(
            Long updateUserId, Long readEpoch, String readRunId, boolean isRelevant) {
        AiSessionEntity updateSession = sessionMapper.selectOne(
                new LambdaQueryWrapper<AiSessionEntity>()
                        .eq(AiSessionEntity::getUserId, updateUserId)
                        .last("FOR UPDATE"));
        if (updateSession == null || !Objects.equals(updateSession.getEpoch(), readEpoch)
                || !Objects.equals(updateSession.getActiveRunId(), readRunId)) {
            throw buildConflict("AI 主题检查对应的运行已失效");
        }
        int updateStrikes = Objects.requireNonNullElse(
                updateSession.getIrrelevantStrikeCount(), 0);
        if (!isRelevant) updateStrikes++;
        updateSession.setIrrelevantStrikeCount(updateStrikes);
        LocalDateTime updateBannedUntil = updateSession.getAiBannedUntil();
        if (!isRelevant && updateStrikes >= 3) {
            long readHours = Math.min(168L, 1L << Math.min(updateStrikes - 3, 20));
            updateBannedUntil = LocalDateTime.now(SHANGHAI).plusHours(readHours);
            updateSession.setAiBannedUntil(updateBannedUntil);
        } else if (isRelevant && (updateBannedUntil == null
                || !updateBannedUntil.isAfter(LocalDateTime.now(SHANGHAI)))) {
            updateSession.setAiBannedUntil(null);
            updateBannedUntil = null;
        }
        if (sessionMapper.updateById(updateSession) != 1) {
            throw buildConflict("AI 主题检查状态已发生变化，请重试");
        }
        return new TopicGuardState(updateStrikes, updateBannedUntil);
    }

    @Transactional
    public boolean markStreaming(Long updateUserId, Long readEpoch, String readRunId) {
        if (sessionMapper.renewRun(
                updateUserId, readEpoch, readRunId, LocalDateTime.now().plusSeconds(runLeaseSeconds)) != 1) {
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
            // Usage and topic restrictions belong to the account, not the chat history.
            // Keep their row while advancing the epoch so old run credentials stay invalid.
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
        }
        messageMapper.delete(new LambdaQueryWrapper<AiMessageEntity>()
                .eq(AiMessageEntity::getUserId, updateUserId));
        return buildSession(sessionMapper.selectById(updateUserId));
    }

    private RunStart reuseRun(
            AiSessionEntity readSession,
            AiMessageEntity readUserMessage,
            String readContent,
            List<Long> readFileIds,
            String inputMetadata) {
        if (!Objects.equals(readUserMessage.getContent(), readContent)
                || !Objects.equals(parseFiles(readUserMessage.getAttachmentIds()), readFileIds)
                || !Objects.equals(readJson(readUserMessage.getInputMetadata()), readJson(inputMetadata))) {
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
        recoverExpiredRun(
                readSession.getUserId(), readSession.getEpoch(), readSession.getActiveRunId());
    }

    @Transactional
    public boolean recoverExpiredRun(
            Long updateUserId,
            Long readEpoch,
            String readRunId) {
        if (sessionMapper.releaseExpiredRun(updateUserId, readEpoch, readRunId) != 1) {
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
                .set("error_code", "LEASE_EXPIRED")
                .set("finish_reason", "error")
                .set("updated_at", LocalDateTime.now());
        messageMapper.update(null, updateMessage);
        rejectActions(updateUserId, readEpoch, readRunId);
        return true;
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
                readMessage.getUpdatedAt(),
                readJson(readMessage.getInputMetadata()),
                readMessage.getStatus() == AiMessageStatus.COMPLETED
                        ? advicePresentations.hydrate(readMessage.getUserId(), readMessage.getEpoch(),
                                readMessage.getRunId(), readJson(readMessage.getPresentation())) : Map.of());
    }

    /** Guard all card writes with the same active epoch/run and message state as text. */
    @Transactional
    public Map<String, Object> savePresentation(Long userId, Long epoch, String runId, Map<String, Object> raw) {
        AiSessionEntity session = sessionMapper.selectById(userId);
        if (session == null || !Objects.equals(session.getEpoch(), epoch)
                || !Objects.equals(session.getActiveRunId(), runId)) return null;
        var message = messageMapper.selectOne(new LambdaQueryWrapper<AiMessageEntity>()
                .eq(AiMessageEntity::getUserId, userId).eq(AiMessageEntity::getEpoch, epoch)
                .eq(AiMessageEntity::getRunId, runId).eq(AiMessageEntity::getRole, AiMessageRole.ASSISTANT));
        if (message == null) return null;
        Map<String, Object> safe = new LinkedHashMap<>(readJson(message.getPresentation()));
        safe.put("schemaVersion", 1);
        if ("job_results".equals(raw.get("type"))) {
            if (!(raw.get("jobIds") instanceof List<?> ids) || ids.size() > 10) throw buildInvalid("岗位卡格式无效");
            List<String> jobIds = ids.stream().map(String::valueOf).distinct().toList();
            for (String id : jobIds) {
                try { if (Long.parseLong(id) <= 0) throw buildInvalid("岗位卡 ID 无效"); }
                catch (NumberFormatException e) { throw buildInvalid("岗位卡 ID 无效"); }
            }
            safe.put("type", "job_results");
            safe.put("jobIds", jobIds);
            safe.put("method", "rules+llm".equals(raw.get("method")) ? "rules+llm" : "rules");
            safe.put("degraded", Boolean.TRUE.equals(raw.get("degraded")));
            safe.put("algorithmVersion", JobRecommendationService.ALGORITHM_VERSION);
            safe.remove("reasons");
            if (raw.get("reasons") instanceof Map<?, ?> rawReasons) {
                Map<String, String> reasons = new LinkedHashMap<>();
                for (String id : jobIds) {
                    if (rawReasons.get(id) instanceof String value) {
                        String reason = value.trim().replaceAll("\\s+", " ");
                        if (!reason.isEmpty()) {
                            reasons.put(id, reason.substring(0, Math.min(reason.length(), 240)));
                        }
                    }
                }
                if (!reasons.isEmpty()) safe.put("reasons", reasons);
            }
            if (raw.get("filters") instanceof Map<?, ?> filters) {
                var validated = recommendations.validate(objectMapper.convertValue(filters, new TypeReference<>() { }));
                safe.put("filters", objectMapper.convertValue(validated, new TypeReference<Map<String, Object>>() { }));
            }
        } else if ("resume_advice".equals(raw.get("type"))) {
            Map<String, Object> report = advicePresentations.validateReport(raw.get("report"));
            advicePresentations.validateContext(userId, epoch, runId, report);
            safe.put("resumeAdvice", report);
        } else if (raw.get("actionId") != null) {
            Long actionId;
            try { actionId = Long.valueOf(raw.get("actionId").toString()); }
            catch (NumberFormatException e) { throw buildInvalid("操作 ID 无效"); }
            var action = actionMapper.selectOne(new LambdaQueryWrapper<UserChangeActionEntity>()
                    .eq(UserChangeActionEntity::getId, actionId).eq(UserChangeActionEntity::getUserId, userId)
                    .eq(UserChangeActionEntity::getEpoch, epoch).eq(UserChangeActionEntity::getRunId, runId));
            if (action == null) throw buildInvalid("操作不属于当前运行");
            List<String> actionIds = new ArrayList<>();
            if (safe.get("actionIds") instanceof List<?> previous) previous.forEach(id -> actionIds.add(id.toString()));
            if (!actionIds.contains(actionId.toString())) actionIds.add(actionId.toString());
            safe.put("actionIds", actionIds);
        } else throw buildInvalid("未知消息展示类型");
        var hydrated = advicePresentations.hydrate(userId, epoch, runId, safe);
        // Store bounded advice and IDs, never arbitrary upstream fields or job visibility snapshots.
        var update = new UpdateWrapper<AiMessageEntity>().eq("id", message.getId())
                .eq("user_id", userId).eq("epoch", epoch).eq("run_id", runId)
                .in("status", AiMessageStatus.PENDING.getCode(), AiMessageStatus.STREAMING.getCode())
                .set("presentation", writeJson(safe));
        return messageMapper.update(null, update) == 1 ? hydrated : null;
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw buildInvalid("消息元数据格式无效"); }
    }

    private Map<String, Object> readJson(String value) {
        if (!StringUtils.hasText(value)) return Map.of();
        try { return objectMapper.readValue(value, new TypeReference<>() { }); }
        catch (JsonProcessingException e) { throw buildInvalid("消息元数据格式无效"); }
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

    public record TopicGuardState(int strikes, LocalDateTime bannedUntil) {
    }

    public record ChatContext(String summary, List<ContextMessage> history,
            List<ActionState> actionStates) {
        public ChatContext(String summary, List<ContextMessage> history) {
            this(summary, history, List.of());
        }
    }

    public record ActionState(String actionId, String status, boolean requiresConfirmation,
            String toolName) {
    }

    public record ContextMessage(String role, String content, List<String> actionIds) {
        public ContextMessage(String role, String content) {
            this(role, content, List.of());
        }
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
