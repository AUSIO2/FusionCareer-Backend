package com.fusioncareer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.JobPostQueryRequest;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.dto.res.PersonalSpaceDocumentsResponse;
import com.fusioncareer.dto.res.QuestionnaireAnswerResponse;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 固定白名单的只读 Agent Tool 适配器。
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
            Map.entry("get_my_change", "history:read")
    );

    private final AgentContextService contextService;
    private final PersonalSpaceService spaceService;
    private final UserService userService;
    private final ResumeFileService fileService;
    private final QuestionnaireAnswerService answerService;
    private final JobPostService jobService;
    private final JobPostQuestionService questionService;
    private final UserChangeService changeService;
    private final ObjectMapper objectMapper;

    public Object executeTool(
            String readContextToken,
            String readToolName,
            Map<String, Object> readArguments) {
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
}
