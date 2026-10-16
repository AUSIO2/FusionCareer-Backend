package com.fusioncareer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.dto.res.ResumeResponse;
import com.fusioncareer.dto.res.UserProfileResponse;
import com.fusioncareer.entity.AiMessageEntity;
import com.fusioncareer.entity.ResumeFileEntity;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.mapper.AiMessageMapper;
import com.fusioncareer.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResumeAdvicePresentationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiMessageMapper messages = mock(AiMessageMapper.class);
    private final JobPostService jobs = mock(JobPostService.class);
    private final UserProfileService profiles = mock(UserProfileService.class);
    private final ResumeService resumes = mock(ResumeService.class);
    private final ResumeFileService files = mock(ResumeFileService.class);
    private final JobRecommendationService recommendations = mock(JobRecommendationService.class);
    private final ResumeAdvicePresentationService presentations = new ResumeAdvicePresentationService(
            messages, jobs, profiles, resumes, files, recommendations, mapper);
    private AiMessageEntity message;
    private JobPostResponse job;
    private UserProfileResponse profile;
    private ResumeResponse resume;

    @BeforeEach
    void context() throws Exception {
        message = new AiMessageEntity();
        message.setInputMetadata(mapper.writeValueAsString(Map.of("jobId", "11", "interaction",
                Map.of("type", "resume_advice", "source", "online"))));
        message.setAttachmentIds("[]");
        when(messages.selectOne(any())).thenReturn(message);
        job = new JobPostResponse();
        job.setId(11L);
        job.setUpdatedAt(LocalDateTime.of(2026, 10, 13, 10, 0));
        when(jobs.getVisibleJobPost(11L)).thenReturn(job);
        profile = new UserProfileResponse(); profile.setVersion(3L);
        when(profiles.getProfile(1L)).thenReturn(profile);
        resume = new ResumeResponse(); resume.setVersion(2L);
        when(resumes.getResume(1L)).thenReturn(resume);
    }

    @Test
    void acceptsOnlyBoundedStructuredReadOnlyReportAndDetachesNestedCollections() {
        Map<String, Object> original = validReport();
        Map<String, Object> validated = presentations.validateReport(original);
        assertThat(validated).isEqualTo(original).isNotSameAs(original);
        ((List<Object>) original.get("warnings")).add("后续修改");
        assertThat((List<Object>) validated.get("warnings")).containsExactly("建议需自行核验");
        assertThat(validated).doesNotContainKeys("actionId", "rawResume", "userId");
    }

    @Test
    void acceptsNeedsResumeWithoutProducingAnActionOrInventedAdvice() {
        var report = validReport();
        report.put("status", "needs_resume"); report.put("fitLevel", "unknown");
        report.put("requirements", List.of()); report.put("sectionSuggestions", List.of()); report.put("proofQuestions", List.of());
        var safe = presentations.validateReport(report);
        assertThat(safe).containsEntry("status", "needs_resume").containsEntry("fitLevel", "unknown")
                .doesNotContainKeys("actionId", "actionIds", "requiresConfirmation");
    }

    @ParameterizedTest
    @ValueSource(strings = {"companyName", "department", "positionName", "jobDesc", "reqOther",
            "reqSkills", "reqMajor", "reqEduLevel", "reqEduLevels", "reqGradYear", "workCity",
            "workCities", "workMode", "workDaysPerWeek", "workStartDate", "workEndDate",
            "applicationDeadline", "other"})
    void acceptsOnlyActualJobEvidenceFieldsWithExactNineteenDigitJobAndFileBindings(String sourceField)
            throws Exception {
        Long jobId = 700000000000000001L;
        Long fileId = 700000000000000002L;
        selectFileContext(jobId, fileId);
        Map<String, Object> report = report(jobId, job.getUpdatedAt().toString(),
                "file:" + fileId + ":v7:p3", "file");
        ((Map<String, Object>) ((List<?>) report.get("requirements")).get(0)).put("sourceField", sourceField);

        Map<String, Object> validated = presentations.validateReport(report);
        assertThat(((Map<?, ?>) ((List<?>) validated.get("requirements")).get(0)).get("sourceField"))
                .isEqualTo(sourceField);
        assertThatCode(() -> presentations.validateContext(1L, 1L, "run-1", validated))
                .doesNotThrowAnyException();
        verify(files).getOwnFile(1L, fileId);
        verify(files, never()).loadAsResource(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"rawResume", "userId", "originalName", "salary", "reqedulevels", "reqEduLevels ", ""})
    void rejectsUnknownOrMalformedJobEvidenceSourceFields(String sourceField) {
        Map<String, Object> report = validReport();
        ((Map<String, Object>) ((List<?>) report.get("requirements")).get(0)).put("sourceField", sourceField);
        assertInvalid(report);
    }

    @Test
    void additionalJobFieldsDoNotAllowWrongNineteenDigitJobFileOrVersionBindings() throws Exception {
        Long jobId = 700000000000000001L;
        Long fileId = 700000000000000002L;
        selectFileContext(jobId, fileId);
        Map<String, Object> report = report(jobId, job.getUpdatedAt().toString(),
                "file:" + fileId + ":v7:p3", "file");
        ((Map<String, Object>) ((List<?>) report.get("requirements")).get(0))
                .put("sourceField", "reqEduLevels");
        presentations.validateReport(report);

        Map<String, Object> wrongJob = new LinkedHashMap<>(report);
        wrongJob.put("jobId", "700000000000000003");
        assertContextInvalid(wrongJob, "岗位与当前消息不匹配");
        Map<String, Object> wrongFile = new LinkedHashMap<>(report);
        wrongFile.put("resumeVersion", "file:700000000000000003:v7:p3");
        assertContextInvalid(wrongFile, "不是本轮附件");
        Map<String, Object> wrongVersion = new LinkedHashMap<>(report);
        wrongVersion.put("resumeVersion", "file:" + fileId + ":v8:p3");
        assertContextInvalid(wrongVersion, "简历或资料版本已变化");
    }

    @Test
    void rejectsExtraMissingAndInvalidFieldsAtEveryDepth() {
        Map<String, Object> extra = validReport(); extra.put("rawResume", "private resume");
        assertInvalid(extra);
        Map<String, Object> missing = validReport(); missing.remove("safeguards"); assertInvalid(missing);
        Map<String, Object> nested = validReport();
        ((Map<String, Object>) ((List<?>) nested.get("requirements")).get(0)).put("contact", "private");
        assertInvalid(nested);
        for (String field : List.of("status", "fitLevel", "resumeSource", "jobId")) {
            Map<String, Object> invalid = validReport(); invalid.put(field, "not_allowed"); assertInvalid(invalid);
        }
        Map<String, Object> overflowId = validReport(); overflowId.put("jobId", "9223372036854775808");
        assertInvalid(overflowId);
        Map<String, Object> paddedId = validReport(); paddedId.put("jobId", "011"); assertInvalid(paddedId);
    }

    @Test
    void rejectsTextCollectionAndReferenceBounds() {
        Map<String, Object> longText = validReport(); longText.put("summary", "x".repeat(2049)); assertInvalid(longText);
        for (Map.Entry<String, Integer> boundary : Map.of("requirements", 20, "sectionSuggestions", 8,
                "proofQuestions", 3, "warnings", 10).entrySet()) {
            Map<String, Object> oversized = validReport();
            Object item = ((List<?>) oversized.get(boundary.getKey())).get(0);
            oversized.put(boundary.getKey(), java.util.Collections.nCopies(boundary.getValue() + 1, item));
            assertInvalid(oversized);
        }
        Map<String, Object> badReference = validReport();
        ((Map<String, Object>) ((List<?>) badReference.get("requirements")).get(0))
                .put("evidenceIds", List.of("resume-x", "<script>"));
        assertInvalid(badReference);
        Map<String, Object> badBoolean = validReport();
        ((Map<String, Object>) ((List<?>) badBoolean.get("sectionSuggestions")).get(0))
                .put("needsUserConfirmation", "true");
        assertInvalid(badBoolean);
    }

    @Test
    void rejectsAnyClaimThatResumeWasChangedOrUnsupportedFactsAsserted() {
        for (String key : List.of("resumeModified", "unsupportedClaimsAsserted", "needsUserVerification")) {
            Map<String, Object> report = validReport();
            ((Map<String, Object>) report.get("safeguards")).put(key, !"needsUserVerification".equals(key));
            assertInvalid(report);
        }
        Map<String, Object> stringBoolean = validReport();
        ((Map<String, Object>) stringBoolean.get("safeguards")).put("resumeModified", "false");
        assertInvalid(stringBoolean);
    }

    @Test
    void validatesExactUserMessageJobSourceAndResourceVersions() {
        assertThatCode(() -> presentations.validateContext(1L, 1L, "run-1", validReport())).doesNotThrowAnyException();
        Map<String, Object> wrongJob = validReport(); wrongJob.put("jobId", "12");
        assertContextInvalid(wrongJob, "岗位与当前消息不匹配");
        Map<String, Object> wrongJobVersion = validReport(); wrongJobVersion.put("jobVersion", "older");
        assertContextInvalid(wrongJobVersion, "版本已变化");
        resume.setVersion(4L); assertContextInvalid(validReport(), "简历或资料版本已变化");
        resume.setVersion(2L); profile.setVersion(4L);
        assertContextInvalid(validReport(), "简历或资料版本已变化");
        profile.setVersion(3L);
        when(messages.selectOne(any())).thenReturn(null);
        assertContextInvalid(validReport(), "不属于当前消息");
    }

    @Test
    void rejectsSourceMismatchAndFilesOutsideExactRunAttachmentSelection() throws Exception {
        message.setInputMetadata(mapper.writeValueAsString(Map.of("jobId", "11", "interaction",
                Map.of("type", "resume_advice", "source", "file", "fileIds", List.of("55")))));
        message.setAttachmentIds("[55,56]");
        assertContextInvalid(validReport(), "简历来源与本轮选择不匹配");
        ResumeFileEntity selected = new ResumeFileEntity(); selected.setId(55L); selected.setVersion(7L);
        when(files.getOwnFile(1L, 55L)).thenReturn(selected);
        Map<String, Object> selectedReport = report(11L, job.getUpdatedAt().toString(), "file:55:v7:p3", "file");
        assertThatCode(() -> presentations.validateContext(1L, 1L, "run-1", selectedReport)).doesNotThrowAnyException();
        verify(files).getOwnFile(1L, 55L);
        Map<String, Object> unattached = report(11L, job.getUpdatedAt().toString(), "file:57:v7:p3", "file");
        assertContextInvalid(unattached, "不是本轮附件");
        ResumeFileEntity unselected = new ResumeFileEntity(); unselected.setId(56L); unselected.setVersion(7L);
        when(files.getOwnFile(1L, 56L)).thenReturn(unselected);
        assertContextInvalid(report(11L, job.getUpdatedAt().toString(), "file:56:v7:p3", "file"), "本轮选择不匹配");
        selected.setVersion(8L); assertContextInvalid(selectedReport, "简历或资料版本已变化");
    }

    @Test
    void rejectsForeignOrDeletedFileWithoutReadingItsContent() throws Exception {
        message.setInputMetadata(mapper.writeValueAsString(Map.of("jobId", "11", "interaction",
                Map.of("type", "resume_advice", "source", "file", "fileIds", List.of("55")))));
        message.setAttachmentIds("[55]");
        when(files.getOwnFile(1L, 55L)).thenThrow(ServiceException.of(ResultCode.NOT_FOUND, "文件不存在"));
        assertContextInvalid(report(11L, job.getUpdatedAt().toString(), "file:55:v7:p3", "file"), "文件不存在");
        verify(files, never()).loadAsResource(any());
    }

    @Test
    void hydratesCurrentAndStaleReportsWithoutTurningThemIntoProposals() {
        when(recommendations.hydrate(any())).thenAnswer(invocation -> {
            Map<String, Object> supplied = invocation.getArgument(0);
            Map<String, Object> result = new LinkedHashMap<>(supplied);
            result.put("jobs", List.of(Map.of("id", "11", "available", true)));
            return result;
        });
        Map<String, Object> stored = Map.of("resumeAdvice", validReport(), "jobIds", List.of("11"),
                "actionIds", List.of("90"));
        Map<String, Object> current = presentations.hydrate(1L, 1L, "run-1", stored);
        assertThat((Map<String, Object>) current.get("resumeAdvice"))
                .containsEntry("available", true).containsEntry("stale", false);
        assertThat(current).containsEntry("actionIds", List.of("90")).containsEntry("jobIds", List.of("11"));
        profile.setVersion(4L);
        assertThat((Map<String, Object>) presentations.hydrate(1L, 1L, "run-1", stored).get("resumeAdvice"))
                .containsEntry("available", true).containsEntry("stale", true);
        assertThat((Map<String, Object>) stored.get("resumeAdvice")).doesNotContainKeys("available", "stale");
    }

    @Test
    void offlineJobBecomesUnavailableStaleHistoricalAdvice() {
        when(recommendations.hydrate(any())).thenAnswer(invocation -> {
            Map<String, Object> supplied = invocation.getArgument(0);
            var result = new LinkedHashMap<>(supplied);
            result.put("jobs", List.of(Map.of("id", "11", "available", false)));
            return result;
        });
        when(jobs.getVisibleJobPost(11L)).thenReturn(null);
        var hydrated = presentations.hydrate(1L, 1L, "run-1", Map.of("resumeAdvice", validReport()));
        assertThat((Map<String, Object>) hydrated.get("resumeAdvice"))
                .containsEntry("available", false).containsEntry("stale", true)
                .containsEntry("summary", "已有经历与岗位部分匹配，建议补充可核验依据。");
    }

    private void assertInvalid(Map<String, Object> report) {
        assertThatThrownBy(() -> presentations.validateReport(report))
                .isInstanceOf(ServiceException.class).hasMessageContaining("格式无效");
    }

    private void assertContextInvalid(Map<String, Object> report, String reason) {
        assertThatThrownBy(() -> presentations.validateContext(1L, 1L, "run-1", report))
                .isInstanceOf(ServiceException.class).hasMessageContaining(reason);
    }

    private Map<String, Object> validReport() {
        return report(11L, job.getUpdatedAt().toString(), "online:r2:p3", "online");
    }

    private void selectFileContext(Long jobId, Long fileId) throws Exception {
        message.setInputMetadata(mapper.writeValueAsString(Map.of("jobId", jobId.toString(), "interaction",
                Map.of("type", "resume_advice", "source", "file", "fileIds", List.of(fileId.toString())))));
        message.setAttachmentIds(mapper.writeValueAsString(List.of(fileId)));
        job.setId(jobId);
        when(jobs.getVisibleJobPost(jobId)).thenReturn(job);
        ResumeFileEntity selected = new ResumeFileEntity();
        selected.setId(fileId);
        selected.setVersion(7L);
        when(files.getOwnFile(1L, fileId)).thenReturn(selected);
    }

    static Map<String, Object> report(Long jobId, String jobVersion, String resumeVersion, String source) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok"); result.put("jobId", jobId.toString());
        result.put("jobVersion", jobVersion); result.put("resumeVersion", resumeVersion); result.put("resumeSource", source);
        result.put("summary", "已有经历与岗位部分匹配，建议补充可核验依据。"); result.put("fitLevel", "mixed");
        result.put("requirements", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of(
                "requirementId", "req-1", "text", "具备新闻写作经验", "sourceField", "reqSkills", "kind", "required",
                "status", "partial", "evidenceIds", List.of("resume-internship"),
                "reason", "已有编辑经历但缺少作品说明", "action", "补充已有作品和本人职责")))));
        result.put("sectionSuggestions", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of(
                "priority", "high", "section", "internship", "issue", "职责描述较笼统", "action", "说明实际采访与编辑职责",
                "example", "仅在属实时使用：参与采访并整理报道。", "evidenceIds", List.of("resume-internship"),
                "needsUserConfirmation", true)))));
        result.put("proofQuestions", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of(
                "question", "是否有可公开的报道作品？", "whyItMatters", "核验写作经验",
                "relatedRequirementIds", List.of("req-1"))))));
        result.put("warnings", new ArrayList<>(List.of("建议需自行核验")));
        result.put("safeguards", new LinkedHashMap<>(Map.of("resumeModified", false,
                "unsupportedClaimsAsserted", false, "needsUserVerification", true)));
        return result;
    }
}
