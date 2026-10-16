package com.fusioncareer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.entity.AiMessageEntity;
import com.fusioncareer.enums.AiMessageRole;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.mapper.AiMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded, read-only report storage, independent of the personal-space/chat aggregation cycle. */
@Service
@RequiredArgsConstructor
public class ResumeAdvicePresentationService {
    private final AiMessageMapper messages;
    private final JobPostService jobs;
    private final UserProfileService profiles;
    private final ResumeService resumes;
    private final ResumeFileService files;
    private final JobRecommendationService recommendations;
    private final ObjectMapper mapper;

    private static final Set<String> REPORT_KEYS = Set.of("status", "jobId", "jobVersion", "resumeVersion",
            "resumeSource", "summary", "fitLevel", "requirements", "sectionSuggestions", "proofQuestions",
            "warnings", "safeguards");

    public Map<String, Object> validateReport(Object raw) {
        Map<String, Object> report = object(raw, REPORT_KEYS);
        choice(report, "status", Set.of("ok", "needs_resume"));
        positiveId(report, "jobId");
        text(report, "jobVersion", 100, true);
        text(report, "resumeVersion", 120, true);
        choice(report, "resumeSource", Set.of("online", "file"));
        text(report, "summary", 2048, false);
        choice(report, "fitLevel", Set.of("strong", "mixed", "weak", "unknown"));
        Set<String> requirementKeys = Set.of("requirementId", "text", "sourceField", "kind", "status",
                "evidenceIds", "reason", "action");
        for (Object rawItem : list(report, "requirements", 20)) {
            Map<String, Object> item = object(rawItem, requirementKeys);
            reference(item, "requirementId");
            for (String key : List.of("text", "reason", "action")) text(item, key, 2048, false);
            choice(item, "sourceField", Set.of("companyName", "department", "positionName", "jobDesc",
                    "reqOther", "reqSkills", "reqMajor", "reqEduLevel", "reqEduLevels", "reqGradYear",
                    "workCity", "workCities", "workMode", "workDaysPerWeek", "workStartDate",
                    "workEndDate", "applicationDeadline", "other"));
            choice(item, "kind", Set.of("required", "preferred", "responsibility", "constraint"));
            choice(item, "status", Set.of("matched", "partial", "gap"));
            references(item, "evidenceIds", 10);
        }
        Set<String> suggestionKeys = Set.of("priority", "section", "issue", "action", "example",
                "evidenceIds", "needsUserConfirmation");
        for (Object rawItem : list(report, "sectionSuggestions", 8)) {
            Map<String, Object> item = object(rawItem, suggestionKeys);
            choice(item, "priority", Set.of("high", "medium", "low"));
            choice(item, "section", Set.of("internship", "projects", "campus", "awards", "skills",
                    "education", "summary", "other"));
            for (String key : List.of("issue", "action", "example")) text(item, key, 2048, false);
            references(item, "evidenceIds", 10);
            if (!(item.get("needsUserConfirmation") instanceof Boolean)) throw invalid();
        }
        for (Object rawItem : list(report, "proofQuestions", 3)) {
            Map<String, Object> item = object(rawItem, Set.of("question", "whyItMatters", "relatedRequirementIds"));
            text(item, "question", 2048, false);
            text(item, "whyItMatters", 2048, false);
            references(item, "relatedRequirementIds", 20);
        }
        for (Object warning : list(report, "warnings", 10)) {
            if (!(warning instanceof String value) || value.length() > 2048) throw invalid();
        }
        Map<String, Object> safeguards = object(report.get("safeguards"),
                Set.of("resumeModified", "unsupportedClaimsAsserted", "needsUserVerification"));
        if (!Boolean.FALSE.equals(safeguards.get("resumeModified"))
                || !Boolean.FALSE.equals(safeguards.get("unsupportedClaimsAsserted"))
                || !Boolean.TRUE.equals(safeguards.get("needsUserVerification"))) throw invalid();
        try {
            String json = mapper.writeValueAsString(report);
            if (json.length() > 256_000) throw invalid();
            // Detach the event's nested collections; no arbitrary upstream fields enter history.
            return mapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException error) {
            throw invalid();
        }
    }

    /** Check the exact request-selected job/source/file and current resource versions at save time. */
    public void validateContext(Long userId, Long epoch, String runId, Map<String, Object> report) {
        AiMessageEntity message = messages.selectOne(new LambdaQueryWrapper<AiMessageEntity>()
                .eq(AiMessageEntity::getUserId, userId).eq(AiMessageEntity::getEpoch, epoch)
                .eq(AiMessageEntity::getRunId, runId).eq(AiMessageEntity::getRole, AiMessageRole.USER));
        if (message == null) throw invalidContext("分析不属于当前消息");
        try {
            Map<String, Object> metadata = mapper.readValue(message.getInputMetadata(), new TypeReference<>() { });
            String jobId = Objects.toString(metadata.get("jobId"), "");
            if (!jobId.equals(report.get("jobId"))) throw invalidContext("分析岗位与当前消息不匹配");
            JobPostResponse job = jobs.getVisibleJobPost(Long.valueOf(jobId));
            if (job == null || !Objects.toString(job.getUpdatedAt(), "missing").equals(report.get("jobVersion"))) {
                throw invalidContext("岗位已不可见或版本已变化，请重新分析");
            }
            var profile = profiles.getProfile(userId);
            String suffix = ":p" + (profile == null ? "missing" : profile.getVersion());
            String source = Objects.toString(report.get("resumeSource"), "");
            String version = Objects.toString(report.get("resumeVersion"), "");
            String expected;
            if ("online".equals(source)) {
                var resume = resumes.getResume(userId);
                expected = "online:r" + (resume == null ? "missing" : resume.getVersion()) + suffix;
            } else if ("file".equals(source)) {
                String[] parts = version.split(":");
                if (parts.length != 4 || !"file".equals(parts[0])) throw invalidContext("简历版本无效");
                Long fileId = Long.valueOf(parts[1]);
                List<Long> attachments = mapper.readValue(message.getAttachmentIds(), new TypeReference<>() { });
                if (!attachments.contains(fileId)) throw invalidContext("分析文件不是本轮附件");
                var file = files.getOwnFile(userId, fileId);
                expected = "file:" + fileId + ":v" + file.getVersion() + suffix;
                if (metadata.get("interaction") instanceof Map<?, ?> interaction
                        && "resume_advice".equals(interaction.get("type"))
                        && interaction.get("fileIds") instanceof List<?> ids
                        && !ids.contains(fileId.toString())) throw invalidContext("分析文件与本轮选择不匹配");
            } else throw invalidContext("简历来源无效");
            if (metadata.get("interaction") instanceof Map<?, ?> interaction
                    && "resume_advice".equals(interaction.get("type"))
                    && !source.equals(interaction.get("source"))) throw invalidContext("分析简历来源与本轮选择不匹配");
            if (!expected.equals(version)) throw invalidContext("简历或资料版本已变化，请重新分析");
        } catch (NumberFormatException | JsonProcessingException error) {
            throw invalidContext("分析上下文格式无效");
        }
    }

    /** Old reports remain historical advice, but never masquerade as a current-resource analysis. */
    public Map<String, Object> hydrate(Long userId, Long epoch, String runId, Map<String, Object> presentation) {
        Map<String, Object> result = recommendations.hydrate(presentation);
        if (!(presentation.get("resumeAdvice") instanceof Map<?, ?> raw)) return result;
        Map<String, Object> report = validateReport(raw);
        Map<String, Object> jobView = recommendations.hydrate(Map.of("jobIds", List.of(report.get("jobId"))));
        boolean available = jobView.get("jobs") instanceof List<?> cards && !cards.isEmpty()
                && cards.get(0) instanceof Map<?, ?> job && Boolean.TRUE.equals(job.get("available"));
        boolean stale = false;
        try { validateContext(userId, epoch, runId, report); }
        catch (ServiceException changedResource) { stale = true; }
        report.put("available", available);
        report.put("stale", stale);
        result.put("resumeAdvice", report);
        return result;
    }

    private Map<String, Object> object(Object raw, Set<String> keys) {
        if (!(raw instanceof Map<?, ?> value) || !value.keySet().equals(keys)) throw invalid();
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put((String) key, item));
        return result;
    }

    private List<?> list(Map<String, Object> object, String key, int maximum) {
        if (!(object.get(key) instanceof List<?> value) || value.size() > maximum) throw invalid();
        return value;
    }

    private String text(Map<String, Object> object, String key, int maximum, boolean required) {
        if (!(object.get(key) instanceof String value) || value.length() > maximum
                || (required && value.isBlank())) throw invalid();
        return value;
    }

    private void choice(Map<String, Object> object, String key, Set<String> choices) {
        if (!choices.contains(text(object, key, 32, true))) throw invalid();
    }

    private void positiveId(Map<String, Object> object, String key) {
        String value = text(object, key, 19, true);
        try { if (!value.matches("[1-9][0-9]{0,18}") || Long.parseLong(value) <= 0) throw invalid(); }
        catch (NumberFormatException error) { throw invalid(); }
    }

    private void reference(Map<String, Object> object, String key) {
        if (!text(object, key, 80, true).matches("[A-Za-z0-9_-]{1,80}")) throw invalid();
    }

    private void references(Map<String, Object> object, String key, int maximum) {
        for (Object item : list(object, key, maximum)) {
            if (!(item instanceof String value) || !value.matches("[A-Za-z0-9_-]{1,80}")) throw invalid();
        }
    }

    private ServiceException invalid() { return invalidContext("简历建议格式无效"); }
    private ServiceException invalidContext(String message) {
        return ServiceException.of(ResultCode.VALIDATE_FAILED, message);
    }
}
