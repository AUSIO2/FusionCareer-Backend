package com.fusioncareer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.JobPostQueryRequest;
import com.fusioncareer.dto.req.QuestionnaireSubmitRequest;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.dto.res.PersonalSpaceDocumentsResponse;
import com.fusioncareer.dto.res.QuestionnaireAnswerResponse;
import com.fusioncareer.enums.ChangeOperation;
import com.fusioncareer.enums.ChangeResourceType;
import com.fusioncareer.enums.EduLevel;
import com.fusioncareer.enums.JobCategory;
import com.fusioncareer.enums.JobPostSort;
import com.fusioncareer.enums.JobSubCategory;
import com.fusioncareer.enums.QuestionnaireSubmissionStatus;
import com.fusioncareer.enums.RecruitType;
import com.fusioncareer.enums.SourceType;
import com.fusioncareer.enums.WorkDurationType;
import com.fusioncareer.enums.WorkMode;
import com.fusioncareer.enums.WorkPeriodType;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 固定白名单的 Agent Tool 适配器。写 Tool 只允许创建待用户确认的提案。
 */
@Service
@RequiredArgsConstructor
public class AgentToolService {

    private static final Map<String, String> TOOL_SCOPES = Map.ofEntries(
            Map.entry("get_my_space", "space:read"),
            Map.entry("get_my_account", "account:read"),
            Map.entry("get_my_profile", "profile:read"),
            Map.entry("get_my_resume", "resume:read"),
            Map.entry("list_my_files", "file:read"),
            Map.entry("get_my_file", "file:read"),
            Map.entry("get_my_file_quota", "file:read"),
            Map.entry("get_my_memory", "memory:read"),
            Map.entry("list_my_applications", "application:read"),
            Map.entry("get_my_application", "application:read"),
            Map.entry("search_jobs", "job:search"),
            Map.entry("get_job", "job:read"),
            Map.entry("get_job_questionnaire", "questionnaire:read"),
            Map.entry("list_my_changes", "history:read"),
            Map.entry("get_my_change", "history:read"),
            Map.entry("propose_profile_patch", "profile:propose"),
            Map.entry("propose_resume_patch", "resume:propose"),
            Map.entry("propose_memory_patch", "memory:propose"),
            Map.entry("propose_file_delete", "file:propose"),
            Map.entry("propose_questionnaire_draft", "questionnaire:propose"),
            Map.entry("propose_questionnaire_submit", "questionnaire:propose")
    );

    private final AgentContextService contextService;
    private final PersonalSpaceService spaceService;
    private final UserService userService;
    private final ResumeFileService fileService;
    private final QuestionnaireAnswerService answerService;
    private final JobPostService jobService;
    private final JobPostQuestionService questionService;
    private final UserChangeService changeService;
    private final PersonalSpaceMutationService mutationService;
    private final UserMemoryService memoryService;
    private final ObjectMapper objectMapper;

    public Object executeTool(
            String readContextToken,
            String readToolName,
            Map<String, Object> readArguments) {
        return executeTool(readContextToken, readToolName, readArguments, null);
    }

    public Object executeTool(
            String readContextToken,
            String readToolName,
            Map<String, Object> readArguments,
            String readToolCallId) {
        String readScope = TOOL_SCOPES.get(readToolName);
        if (readScope == null) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "Agent Tool 不存在");
        }
        AgentContextService.AgentContext readContext =
                contextService.verifyContext(readContextToken, readScope);
        Map<String, Object> readArgs = readArguments == null ? Map.of() : readArguments;
        return switch (readToolName) {
            case "get_my_space" -> {
                requireKeys(readArgs, Set.of());
                yield spaceService.readSpace(readContext.userId());
            }
            case "get_my_account" -> {
                requireKeys(readArgs, Set.of());
                yield userService.getUserById(readContext.userId());
            }
            case "get_my_profile" -> {
                requireKeys(readArgs, Set.of());
                yield spaceService.readProfile(readContext.userId());
            }
            case "get_my_resume" -> {
                requireKeys(readArgs, Set.of());
                yield spaceService.readResume(readContext.userId());
            }
            case "list_my_files" -> listFiles(readContext.userId(), readArgs);
            case "get_my_file" -> readFile(readContext.userId(), readArgs);
            case "get_my_file_quota" -> readQuota(readContext.userId(), readArgs);
            case "get_my_memory" -> {
                requireKeys(readArgs, Set.of());
                yield spaceService.readMemory(readContext.userId());
            }
            case "list_my_applications" -> listApplications(readContext.userId(), readArgs);
            case "get_my_application" -> readApplication(readContext.userId(), readArgs);
            case "search_jobs" -> searchJobs(readArgs);
            case "get_job" -> readJob(readArgs);
            case "get_job_questionnaire" -> readQuestionnaire(readArgs);
            case "list_my_changes" -> listChanges(readContext.userId(), readArgs);
            case "get_my_change" -> readChange(readContext.userId(), readArgs);
            case "propose_profile_patch" -> proposeProfile(
                    readContext, readToolName, readToolCallId, readArgs);
            case "propose_resume_patch" -> proposeResume(
                    readContext, readToolName, readToolCallId, readArgs);
            case "propose_memory_patch" -> proposeMemory(
                    readContext, readToolName, readToolCallId, readArgs);
            case "propose_file_delete" -> proposeFileDelete(
                    readContext, readToolName, readToolCallId, readArgs);
            case "propose_questionnaire_draft" -> proposeQuestionnaire(
                    readContext, readToolName, readToolCallId, readArgs,
                    QuestionnaireSubmissionStatus.DRAFT);
            case "propose_questionnaire_submit" -> proposeQuestionnaire(
                    readContext, readToolName, readToolCallId, readArgs,
                    QuestionnaireSubmissionStatus.SUBMITTED);
            default -> throw ServiceException.of(ResultCode.NOT_FOUND, "Agent Tool 不存在");
        };
    }

    private Object listFiles(Long readUserId, Map<String, Object> readArgs) {
        requireKeys(readArgs, Set.of("page", "size"));
        int readPage = readInt(readArgs, "page", 1, 1, Integer.MAX_VALUE);
        int readSize = readInt(readArgs, "size", 10, 1, 20);
        PersonalSpaceDocumentsResponse readDocuments = spaceService.readDocuments(readUserId);
        long readOffset = (long) (readPage - 1) * readSize;
        int readStart = (int) Math.min(readOffset, readDocuments.files().size());
        int readEnd = Math.min(readStart + readSize, readDocuments.files().size());
        return Map.of(
                "files", readDocuments.files().subList(readStart, readEnd),
                "page", readPage,
                "size", readSize,
                "total", readDocuments.files().size());
    }

    private Object readFile(Long readUserId, Map<String, Object> readArgs) {
        requireKeys(readArgs, Set.of("fileId"));
        Long readFileId = readLong(readArgs, "fileId");
        fileService.getOwnFile(readUserId, readFileId);
        return fileService.listByUser(readUserId).stream()
                .filter(readFile -> readFile.getId().equals(readFileId))
                .findFirst()
                .orElseThrow(() -> ServiceException.of(ResultCode.NOT_FOUND, "文件不存在"));
    }

    private Object readQuota(Long readUserId, Map<String, Object> readArgs) {
        requireKeys(readArgs, Set.of());
        PersonalSpaceDocumentsResponse readDocuments = spaceService.readDocuments(readUserId);
        return Map.of(
                "usedBytes", readDocuments.usedBytes(),
                "quotaBytes", readDocuments.quotaBytes(),
                "fileCount", readDocuments.files().size());
    }

    private Object listApplications(Long readUserId, Map<String, Object> readArgs) {
        requireKeys(readArgs, Set.of("status", "page", "size"));
        int readPage = readInt(readArgs, "page", 1, 1, Integer.MAX_VALUE);
        int readSize = readInt(readArgs, "size", 10, 1, 10);
        QuestionnaireSubmissionStatus readStatus = readEnum(
                readArgs, "status", QuestionnaireSubmissionStatus.class);
        return spaceService.readApplications(readUserId, readPage, readSize, readStatus);
    }

    private Object readApplication(Long readUserId, Map<String, Object> readArgs) {
        requireKeys(readArgs, Set.of("jobPostId"));
        QuestionnaireAnswerResponse readAnswer = answerService.getByUserAndJobPost(
                readUserId, readLong(readArgs, "jobPostId"));
        if (readAnswer == null) {
            return null;
        }
        Map<String, Object> readSafeAnswer = objectMapper.convertValue(
                readAnswer, new TypeReference<>() { });
        readSafeAnswer.remove("userId");
        readSafeAnswer.remove("username");
        readSafeAnswer.remove("realName");
        readSafeAnswer.remove("studentId");
        return readSafeAnswer;
    }

    private Object searchJobs(Map<String, Object> readArgs) {
        requireKeys(readArgs, Set.of(
                "keyword", "jobCategory", "jobSubCategory", "recruitType",
                "workDurationType", "workPeriodType", "workMode", "workProvince",
                "workCity", "reqEduLevel", "salaryMin", "salaryMax", "recommended",
                "sourceType", "internalApply", "sort", "page", "size"));
        JobPostQueryRequest readQuery = new JobPostQueryRequest();
        readQuery.setPage(readInt(readArgs, "page", 1, 1, Integer.MAX_VALUE));
        readQuery.setSize(readInt(readArgs, "size", 10, 1, 10));
        readQuery.setKeyword(readText(readArgs, "keyword", 100));
        readQuery.setJobCategory(readEnum(readArgs, "jobCategory", JobCategory.class));
        readQuery.setJobSubCategory(readEnum(readArgs, "jobSubCategory", JobSubCategory.class));
        readQuery.setRecruitType(readEnum(readArgs, "recruitType", RecruitType.class));
        readQuery.setWorkDurationType(readEnum(readArgs, "workDurationType", WorkDurationType.class));
        readQuery.setWorkPeriodType(readEnum(readArgs, "workPeriodType", WorkPeriodType.class));
        readQuery.setWorkMode(readEnum(readArgs, "workMode", WorkMode.class));
        readQuery.setWorkProvince(readText(readArgs, "workProvince", 32));
        readQuery.setWorkCity(readText(readArgs, "workCity", 32));
        readQuery.setReqEduLevel(readEnum(readArgs, "reqEduLevel", EduLevel.class));
        readQuery.setSalaryMin(readInteger(readArgs, "salaryMin"));
        readQuery.setSalaryMax(readInteger(readArgs, "salaryMax"));
        readQuery.setRecommended(readBoolean(readArgs, "recommended"));
        readQuery.setSourceType(readEnum(readArgs, "sourceType", SourceType.class));
        readQuery.setInternalApply(readBoolean(readArgs, "internalApply"));
        JobPostSort readSort = readEnum(readArgs, "sort", JobPostSort.class);
        if (readSort != null) {
            readQuery.setSortBy(readSort);
        }
        PageResult<JobPostResponse> readJobs = jobService.listPublishedJobPosts(readQuery);
        Map<String, Object> readResult = new LinkedHashMap<>();
        readResult.put("jobs", readJobs.getList().stream().map(this::safeJob).toList());
        readResult.put("total", readJobs.getTotal());
        readResult.put("page", readJobs.getPage());
        readResult.put("size", readJobs.getSize());
        readResult.put("totalPages", readJobs.getTotalPages());
        return readResult;
    }

    private Object readJob(Map<String, Object> readArgs) {
        requireKeys(readArgs, Set.of("jobId"));
        JobPostResponse readJob = jobService.getVisibleJobPost(readLong(readArgs, "jobId"));
        if (readJob == null) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "岗位不存在或当前不可见");
        }
        return safeJob(readJob);
    }

    private Object readQuestionnaire(Map<String, Object> readArgs) {
        requireKeys(readArgs, Set.of("jobPostId"));
        Long readJobId = readLong(readArgs, "jobPostId");
        if (jobService.getVisibleJob(readJobId) == null) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "岗位不存在或当前不可见");
        }
        return questionService.listByJobPostId(readJobId);
    }

    private Object listChanges(Long readUserId, Map<String, Object> readArgs) {
        requireKeys(readArgs, Set.of("beforeId", "size"));
        return changeService.readActions(
                readUserId,
                readOptionalLong(readArgs, "beforeId"),
                readInt(readArgs, "size", 10, 1, 20));
    }

    private Object readChange(Long readUserId, Map<String, Object> readArgs) {
        requireKeys(readArgs, Set.of("actionId"));
        return changeService.readAction(readUserId, readLong(readArgs, "actionId"));
    }

    private Object proposeProfile(
            AgentContextService.AgentContext readContext,
            String readToolName,
            String readToolCallId,
            Map<String, Object> readArgs) {
        PatchInput readPatch = readPatch(readArgs);
        PersonalSpaceMutationService.PreparedChange readChange =
                mutationService.prepareProfileProposal(
                        readContext.userId(), readPatch.set(), readPatch.clear());
        return saveProposal(
                readContext,
                readToolName,
                readToolCallId,
                readArgs,
                readPatch.reason(),
                new ProposalChange(
                        readChange.resourceType(),
                        readChange.resourceKey(),
                        readChange.operation(),
                        readChange.changedFields(),
                        readChange.expectedVersion(),
                        readChange.beforeExists(),
                        readChange.afterExists(),
                        readChange.beforeFields(),
                        readChange.afterFields()));
    }

    private Object proposeResume(
            AgentContextService.AgentContext readContext,
            String readToolName,
            String readToolCallId,
            Map<String, Object> readArgs) {
        PatchInput readPatch = readPatch(readArgs);
        PersonalSpaceMutationService.PreparedChange readChange =
                mutationService.prepareResumeProposal(
                        readContext.userId(), readPatch.set(), readPatch.clear());
        return saveProposal(
                readContext,
                readToolName,
                readToolCallId,
                readArgs,
                readPatch.reason(),
                new ProposalChange(
                        readChange.resourceType(),
                        readChange.resourceKey(),
                        readChange.operation(),
                        readChange.changedFields(),
                        readChange.expectedVersion(),
                        readChange.beforeExists(),
                        readChange.afterExists(),
                        readChange.beforeFields(),
                        readChange.afterFields()));
    }

    private Object proposeMemory(
            AgentContextService.AgentContext readContext,
            String readToolName,
            String readToolCallId,
            Map<String, Object> readArgs) {
        PatchInput readPatch = readPatch(readArgs);
        UserMemoryService.PreparedChange readChange = memoryService.prepareProposal(
                readContext.userId(), readPatch.set(), readPatch.clear());
        return saveProposal(
                readContext,
                readToolName,
                readToolCallId,
                readArgs,
                readPatch.reason(),
                new ProposalChange(
                        readChange.resourceType(),
                        readChange.resourceKey(),
                        readChange.operation(),
                        readChange.changedFields(),
                        readChange.expectedVersion(),
                        readChange.beforeExists(),
                        readChange.afterExists(),
                        readChange.beforeFields(),
                        readChange.afterFields()));
    }

    private Object proposeFileDelete(
            AgentContextService.AgentContext readContext,
            String readToolName,
            String readToolCallId,
            Map<String, Object> readArgs) {
        requireKeys(readArgs, Set.of("fileId", "reason"));
        Long readFileId = readLong(readArgs, "fileId");
        String readReason = readRequiredText(readArgs, "reason", 256);
        var readFile = fileService.getOwnFile(readContext.userId(), readFileId);
        return saveProposal(
                readContext,
                readToolName,
                readToolCallId,
                readArgs,
                readReason,
                new ProposalChange(
                        ChangeResourceType.RESUME_FILE,
                        readFileId.toString(),
                        ChangeOperation.SOFT_DELETE,
                        List.of("deleted"),
                        readFile.getVersion(),
                        true,
                        true,
                        Map.of("deleted", false),
                        Map.of("deleted", true)));
    }

    private Object proposeQuestionnaire(
            AgentContextService.AgentContext readContext,
            String readToolName,
            String readToolCallId,
            Map<String, Object> readArgs,
            QuestionnaireSubmissionStatus readTargetStatus) {
        requireKeys(readArgs, Set.of("jobPostId", "answers", "reason"));
        Long readJobPostId = readLong(readArgs, "jobPostId");
        String readReason = readRequiredText(readArgs, "reason", 256);
        QuestionnaireSubmitRequest createRequest = new QuestionnaireSubmitRequest();
        createRequest.setJobPostId(readJobPostId);
        createRequest.setAnswers(writeToolAnswers(readArgs.get("answers")));
        QuestionnaireAnswerService.PreparedChange readChange = answerService.prepareProposal(
                readContext.userId(), createRequest, readTargetStatus);
        return saveProposal(
                readContext,
                readToolName,
                readToolCallId,
                readArgs,
                readReason,
                new ProposalChange(
                        ChangeResourceType.QUESTIONNAIRE_ANSWER,
                        readChange.resourceKey(),
                        readChange.operation(),
                        readChange.changedFields(),
                        readChange.expectedVersion(),
                        readChange.beforeExists(),
                        readChange.afterExists(),
                        readChange.beforeFields(),
                        readChange.afterFields()));
    }

    private String writeToolAnswers(Object readRawAnswers) {
        if (!(readRawAnswers instanceof List<?> readAnswers) || readAnswers.size() > 100) {
            throw buildInvalid("answers 必须是最多 100 项的数组");
        }
        List<Map<String, Object>> writeAnswers = new ArrayList<>();
        Set<Long> readQuestionIds = new LinkedHashSet<>();
        for (Object readRawAnswer : readAnswers) {
            if (!(readRawAnswer instanceof Map<?, ?> readAnswerMap)) {
                throw buildInvalid("answers 中的每一项必须是对象");
            }
            Map<String, Object> readAnswer = new LinkedHashMap<>();
            readAnswerMap.forEach((readKey, readValue) -> {
                if (!(readKey instanceof String readName)) {
                    throw buildInvalid("answers 字段名格式无效");
                }
                readAnswer.put(readName, readValue);
            });
            requireKeys(readAnswer, Set.of("questionId", "value"));
            Long readQuestionId = readLong(readAnswer, "questionId");
            if (!readQuestionIds.add(readQuestionId)) {
                throw buildInvalid("answers 中存在重复题目: " + readQuestionId);
            }
            if (!readAnswer.containsKey("value")) {
                throw buildInvalid("答案缺少 value: " + readQuestionId);
            }
            Map<String, Object> writeAnswer = new LinkedHashMap<>();
            writeAnswer.put("questionId", readQuestionId);
            writeAnswer.put("value", readAnswer.get("value"));
            writeAnswers.add(writeAnswer);
        }
        try {
            return objectMapper.writeValueAsString(writeAnswers);
        } catch (JsonProcessingException readError) {
            throw buildInvalid("answers 无法序列化");
        }
    }

    private Object saveProposal(
            AgentContextService.AgentContext readContext,
            String readToolName,
            String readToolCallId,
            Map<String, Object> readArgs,
            String readReason,
            ProposalChange readChange) {
        String readCallId = requireToolCallId(readToolCallId);
        String readArgsHash = hashCanonical(Map.of(
                "toolName", readToolName,
                "arguments", readArgs));
        String readIdempotencyKey = readContext.runId() + ":"
                + hashText(readCallId).substring(0, 32);
        UserChangeService.AppliedChange createChange = new UserChangeService.AppliedChange(
                readContext.userId(),
                "AGENT_TOOL",
                readChange.resourceType(),
                readChange.resourceKey(),
                readChange.operation(),
                readChange.changedFields(),
                readChange.expectedVersion(),
                null,
                readChange.beforeExists(),
                readChange.afterExists(),
                readChange.beforeFields(),
                readChange.afterFields(),
                readReason);
        var readAction = changeService.recordPendingApply(
                createChange,
                readContext.epoch(),
                readContext.runId(),
                readContext.requestId(),
                readToolName,
                readIdempotencyKey,
                readArgsHash);
        Map<String, Object> readResult = new LinkedHashMap<>();
        readResult.put("actionId", readAction.id());
        readResult.put("status", readAction.status());
        readResult.put("reason", readAction.reason());
        readResult.put("resourceType", readChange.resourceType());
        readResult.put("changedFields", readAction.items().isEmpty()
                ? List.of() : readAction.items().get(0).changedFields());
        readResult.put("baseVersion", readAction.items().isEmpty()
                ? null : readAction.items().get(0).expectedVersion());
        readResult.put("requiresConfirmation", true);
        return readResult;
    }

    private PatchInput readPatch(Map<String, Object> readArgs) {
        requireKeys(readArgs, Set.of("changes", "reason"));
        Object readRawChanges = readArgs.get("changes");
        if (!(readRawChanges instanceof List<?> readChanges)
                || readChanges.isEmpty() || readChanges.size() > 32) {
            throw buildInvalid("changes 必须包含 1 到 32 个字段修改");
        }
        String readReason = readRequiredText(readArgs, "reason", 256);
        Map<String, Object> updateSet = new LinkedHashMap<>();
        List<String> updateClear = new ArrayList<>();
        Set<String> readSeenFields = new LinkedHashSet<>();
        for (Object readRawChange : readChanges) {
            if (!(readRawChange instanceof Map<?, ?> readChangeMap)) {
                throw buildInvalid("changes 中的每一项必须是对象");
            }
            Map<String, Object> readChange = new LinkedHashMap<>();
            readChangeMap.forEach((readKey, readValue) -> {
                if (!(readKey instanceof String readName)) {
                    throw buildInvalid("changes 字段名格式无效");
                }
                readChange.put(readName, readValue);
            });
            requireKeys(readChange, Set.of("field", "operation", "value"));
            String readField = readRequiredText(readChange, "field", 64);
            if (!readSeenFields.add(readField)) {
                throw buildInvalid("changes 中存在重复字段: " + readField);
            }
            String readOperation = readRequiredText(readChange, "operation", 16);
            if ("SET".equals(readOperation)) {
                if (!readChange.containsKey("value")) {
                    throw buildInvalid("SET 操作必须提供 value: " + readField);
                }
                updateSet.put(readField, readChange.get("value"));
            } else if ("CLEAR".equals(readOperation)) {
                if (readChange.containsKey("value")) {
                    throw buildInvalid("CLEAR 操作不能提供 value: " + readField);
                }
                updateClear.add(readField);
            } else {
                throw buildInvalid("operation 只允许 SET 或 CLEAR");
            }
        }
        return new PatchInput(updateSet, updateClear, readReason);
    }

    private String requireToolCallId(String readToolCallId) {
        if (readToolCallId == null
                || readToolCallId.isBlank()
                || readToolCallId.length() > 128
                || readToolCallId.indexOf('\r') >= 0
                || readToolCallId.indexOf('\n') >= 0) {
            throw buildInvalid("写 Tool 缺少有效的调用标识");
        }
        return readToolCallId;
    }

    private String hashCanonical(Object readValue) {
        try {
            byte[] readJson = objectMapper.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(readValue);
            return hashBytes(readJson);
        } catch (JsonProcessingException readError) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR,
                    "Agent Tool 参数无法规范化");
        }
    }

    private String hashText(String readValue) {
        return hashBytes(readValue.getBytes(StandardCharsets.UTF_8));
    }

    private String hashBytes(byte[] readValue) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(readValue));
        } catch (NoSuchAlgorithmException readError) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR,
                    "Agent Tool 幂等摘要不可用");
        }
    }

    private Map<String, Object> safeJob(JobPostResponse readJob) {
        Map<String, Object> readSafeJob = objectMapper.convertValue(
                readJob, new TypeReference<>() { });
        readSafeJob.remove("recycleReason");
        readSafeJob.remove("recycledAt");
        readSafeJob.remove("createdBy");
        return readSafeJob;
    }

    private void requireKeys(Map<String, Object> readArgs, Set<String> readAllowed) {
        for (String readKey : readArgs.keySet()) {
            if (!readAllowed.contains(readKey)) {
                throw buildInvalid("Tool 参数不允许: " + readKey);
            }
        }
    }

    private Long readLong(Map<String, Object> readArgs, String readKey) {
        Long readValue = readOptionalLong(readArgs, readKey);
        if (readValue == null) {
            throw buildInvalid("缺少 Tool 参数: " + readKey);
        }
        return readValue;
    }

    private Long readOptionalLong(Map<String, Object> readArgs, String readKey) {
        Object readValue = readArgs.get(readKey);
        if (readValue == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(readValue));
        } catch (NumberFormatException readError) {
            throw buildInvalid("Tool 参数必须是整数: " + readKey);
        }
    }

    private int readInt(
            Map<String, Object> readArgs,
            String readKey,
            int readDefault,
            int readMin,
            int readMax) {
        Long readValue = readOptionalLong(readArgs, readKey);
        long updateValue = readValue == null ? readDefault : readValue;
        if (updateValue < readMin || updateValue > readMax) {
            throw buildInvalid("Tool 参数超出范围: " + readKey);
        }
        return (int) updateValue;
    }

    private Integer readInteger(Map<String, Object> readArgs, String readKey) {
        Long readValue = readOptionalLong(readArgs, readKey);
        if (readValue == null) {
            return null;
        }
        if (readValue < Integer.MIN_VALUE || readValue > Integer.MAX_VALUE) {
            throw buildInvalid("Tool 参数超出范围: " + readKey);
        }
        return readValue.intValue();
    }

    private String readText(Map<String, Object> readArgs, String readKey, int readMaxLength) {
        Object readValue = readArgs.get(readKey);
        if (readValue == null) {
            return null;
        }
        if (!(readValue instanceof String readText) || readText.length() > readMaxLength) {
            throw buildInvalid("Tool 参数格式无效: " + readKey);
        }
        return readText;
    }

    private String readRequiredText(
            Map<String, Object> readArgs,
            String readKey,
            int readMaxLength) {
        String readValue = readText(readArgs, readKey, readMaxLength);
        if (readValue == null || readValue.isBlank()) {
            throw buildInvalid("缺少 Tool 参数: " + readKey);
        }
        return readValue.trim();
    }

    private Boolean readBoolean(Map<String, Object> readArgs, String readKey) {
        Object readValue = readArgs.get(readKey);
        if (readValue == null) {
            return null;
        }
        if (!(readValue instanceof Boolean readBoolean)) {
            throw buildInvalid("Tool 参数必须是布尔值: " + readKey);
        }
        return readBoolean;
    }

    private <T extends Enum<T>> T readEnum(
            Map<String, Object> readArgs,
            String readKey,
            Class<T> readType) {
        Object readValue = readArgs.get(readKey);
        if (readValue == null) {
            return null;
        }
        try {
            return Enum.valueOf(readType, String.valueOf(readValue));
        } catch (IllegalArgumentException readError) {
            throw buildInvalid("Tool 枚举参数无效: " + readKey);
        }
    }

    private ServiceException buildInvalid(String readMessage) {
        return ServiceException.of(ResultCode.VALIDATE_FAILED, readMessage);
    }

    private record PatchInput(
            Map<String, Object> set,
            List<String> clear,
            String reason) {
    }

    private record ProposalChange(
            ChangeResourceType resourceType,
            String resourceKey,
            ChangeOperation operation,
            List<String> changedFields,
            Long expectedVersion,
            boolean beforeExists,
            boolean afterExists,
            Map<String, Object> beforeFields,
            Map<String, Object> afterFields) {
    }
}
