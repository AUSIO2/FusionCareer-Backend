package com.fusioncareer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.QuestionnaireApplicationCount;
import com.fusioncareer.dto.req.QuestionnaireReviewRequest;
import com.fusioncareer.dto.req.QuestionnaireSubmitRequest;
import com.fusioncareer.dto.res.JobPostQuestionResponse;
import com.fusioncareer.dto.res.MyQuestionnaireListItemResponse;
import com.fusioncareer.dto.res.MyQuestionnaireListPageResponse;
import com.fusioncareer.dto.res.QuestionnaireAnswerResponse;
import com.fusioncareer.dto.res.UserResponse;
import com.fusioncareer.entity.JobPostEntity;
import com.fusioncareer.entity.QuestionnaireAnswerEntity;
import com.fusioncareer.enums.ChangeOperation;
import com.fusioncareer.enums.ChangeResourceType;
import com.fusioncareer.enums.QuestionType;
import com.fusioncareer.enums.QuestionnaireSubmissionStatus;
import com.fusioncareer.exception.QuestionnaireErrorCode;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.mapper.QuestionnaireAnswerMapper;
import com.fusioncareer.service.JobPostQuestionService;
import com.fusioncareer.service.JobPostService;
import com.fusioncareer.service.QuestionnaireAnswerService;
import com.fusioncareer.service.ResumeFileService;
import com.fusioncareer.service.UserService;
import com.fusioncareer.service.UserChangeService;
import com.fusioncareer.util.QuestionnaireAnswerValidator;
import com.fusioncareer.util.QuestionnaireDeadlineUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.fusioncareer.util.PaginationUtil.createPage;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionnaireAnswerServiceImpl extends ServiceImpl<QuestionnaireAnswerMapper, QuestionnaireAnswerEntity>
        implements QuestionnaireAnswerService {

    private final UserService userService;
    private final JobPostService jobPostService;
    private final JobPostQuestionService jobPostQuestionService;
    private final QuestionnaireAnswerValidator answerValidator;
    private final ResumeFileService resumeFileService;
    private final UserChangeService changeService;
    private final ObjectMapper objectMapper;

    @Transactional
    @Override
    public QuestionnaireAnswerResponse saveDraft(Long userId, QuestionnaireSubmitRequest request) {
        return saveAnswer(userId, request, QuestionnaireSubmissionStatus.DRAFT, true);
    }

    @Transactional
    @Override
    public QuestionnaireAnswerResponse submit(Long userId, QuestionnaireSubmitRequest request) {
        return saveAnswer(userId, request, QuestionnaireSubmissionStatus.SUBMITTED, true);
    }

    @Transactional(readOnly = true)
    @Override
    public PreparedChange prepareProposal(
            Long userId,
            QuestionnaireSubmitRequest request,
            QuestionnaireSubmissionStatus targetStatus) {
        ValidatedAnswers readValidated = validateRequest(userId, request, targetStatus);
        QuestionnaireAnswerEntity readAnswer = getOwnAnswerIncludingDeleted(
                userId, request.getJobPostId());
        PreparedChange readChange = prepareChange(
                readAnswer, request.getJobPostId(), readValidated, targetStatus);
        if (readChange.changedFields().isEmpty()) {
            throw ServiceException.of(ResultCode.VALIDATE_FAILED, "提案没有产生实际变化");
        }
        return readChange;
    }

    private QuestionnaireAnswerResponse saveAnswer(
            Long userId,
            QuestionnaireSubmitRequest request,
            QuestionnaireSubmissionStatus targetStatus,
            boolean saveHistory) {
        ValidatedAnswers readValidated = validateRequest(userId, request, targetStatus);
        QuestionnaireAnswerEntity updateAnswer = getOwnAnswerIncludingDeleted(
                userId, request.getJobPostId());
        PreparedChange readChange = prepareChange(
                updateAnswer, request.getJobPostId(), readValidated, targetStatus);
        if (readChange.changedFields().isEmpty()) {
            return toResponse(updateAnswer);
        }

        Long updateVersion;
        if (updateAnswer == null) {
            updateAnswer = new QuestionnaireAnswerEntity();
            updateAnswer.setUserId(userId);
            updateAnswer.setJobPostId(request.getJobPostId());
            updateAnswer.setAnswers(readValidated.answersJson());
            updateAnswer.setSubmissionStatus(targetStatus);
            updateAnswer.setVersion(0L);
            try {
                if (!save(updateAnswer)) {
                    throw buildConflict();
                }
            } catch (DuplicateKeyException readError) {
                throw buildConflict();
            }
            updateVersion = 0L;
        } else {
            Long readVersion = updateAnswer.getVersion();
            updateAnswer.setDeletedAt(null);
            updateAnswer.setAnswers(readValidated.answersJson());
            updateAnswer.setSubmissionStatus(targetStatus);
            clearReviewMetadata(updateAnswer);
            updateAnswer.setUpdatedAt(LocalDateTime.now());
            updateEditableAnswer(updateAnswer, readVersion);
            updateVersion = readVersion + 1;
        }
        if (saveHistory) {
            recordChange(userId, request.getJobPostId(), targetStatus, readChange, updateVersion);
        }
        if (targetStatus == QuestionnaireSubmissionStatus.SUBMITTED) {
            log.info("用户 {} 提交岗位 {} 的问卷", userId, request.getJobPostId());
        }
        return toResponse(updateAnswer);
    }

    private ValidatedAnswers validateRequest(
            Long userId,
            QuestionnaireSubmitRequest request,
            QuestionnaireSubmissionStatus targetStatus) {
        if (request == null || request.getJobPostId() == null) {
            throw ServiceException.of(QuestionnaireErrorCode.JOB_POST_NOT_FOUND);
        }
        if (targetStatus != QuestionnaireSubmissionStatus.DRAFT
                && targetStatus != QuestionnaireSubmissionStatus.SUBMITTED) {
            throw ServiceException.of(QuestionnaireErrorCode.INVALID_SUBMISSION_STATUS);
        }
        requireOpenJob(request.getJobPostId());
        List<JobPostQuestionResponse> readQuestions =
                jobPostQuestionService.listByJobPostId(request.getJobPostId());
        Map<Long, Object> readAnswers = targetStatus == QuestionnaireSubmissionStatus.DRAFT
                ? answerValidator.validateDraftAnswers(request.getAnswers(), readQuestions)
                : answerValidator.validateRequiredAnswers(request.getAnswers(), readQuestions);
        validateFileAnswers(userId, readAnswers, readQuestions);
        return new ValidatedAnswers(writeAnswers(readAnswers));
    }

    private PreparedChange prepareChange(
            QuestionnaireAnswerEntity readAnswer,
            Long readJobPostId,
            ValidatedAnswers readValidated,
            QuestionnaireSubmissionStatus targetStatus) {
        boolean readExists = readAnswer != null && readAnswer.getDeletedAt() == null;
        QuestionnaireAnswerEntity readActive = readExists ? readAnswer : null;
        assertTransition(readActive, targetStatus);

        Map<String, Object> readBeforeFields = new LinkedHashMap<>();
        Map<String, Object> updateFields = new LinkedHashMap<>();
        addChange(readBeforeFields, updateFields, "answers",
                readActive == null ? null : readActive.getAnswers(), readValidated.answersJson());
        addChange(readBeforeFields, updateFields, "submissionStatus",
                readActive == null ? null : readActive.getSubmissionStatus(), targetStatus);
        if (readActive != null) {
            addChange(readBeforeFields, updateFields, "reviewedAt",
                    readActive.getReviewedAt(), null);
            addChange(readBeforeFields, updateFields, "reviewedBy",
                    readActive.getReviewedBy(), null);
            addChange(readBeforeFields, updateFields, "reviewPassed",
                    readActive.getReviewPassed(), null);
            addChange(readBeforeFields, updateFields, "reviewComments",
                    readActive.getReviewComments(), null);
        }
        if (updateFields.isEmpty()) {
            return new PreparedChange(
                    readJobPostId.toString(),
                    ChangeOperation.PATCH,
                    List.of(),
                    readAnswer == null ? null : readAnswer.getVersion(),
                    readExists,
                    true,
                    Map.of(),
                    Map.of());
        }
        ChangeOperation readOperation = !readExists
                ? ChangeOperation.CREATE
                : updateFields.containsKey("submissionStatus")
                ? ChangeOperation.STATE_TRANSITION
                : ChangeOperation.PATCH;
        return new PreparedChange(
                readJobPostId.toString(),
                readOperation,
                new ArrayList<>(updateFields.keySet()),
                readAnswer == null ? null : readAnswer.getVersion(),
                readExists,
                true,
                readBeforeFields,
                updateFields);
    }

    private void addChange(
            Map<String, Object> readBeforeFields,
            Map<String, Object> updateFields,
            String readField,
            Object readBefore,
            Object readAfter) {
        if (!Objects.equals(readBefore, readAfter)) {
            readBeforeFields.put(readField, readBefore);
            updateFields.put(readField, readAfter);
        }
    }

    private void assertTransition(
            QuestionnaireAnswerEntity readAnswer,
            QuestionnaireSubmissionStatus targetStatus) {
        assertCanEdit(readAnswer);
        if (readAnswer != null
                && targetStatus == QuestionnaireSubmissionStatus.DRAFT
                && readAnswer.getSubmissionStatus() == QuestionnaireSubmissionStatus.SUBMITTED) {
            throw ServiceException.of(QuestionnaireErrorCode.INVALID_SUBMISSION_STATUS,
                    "已提交的投递请使用正式提交接口修改");
        }
    }

    private String writeAnswers(Map<Long, Object> readAnswers) {
        List<Map<String, Object>> writeItems = new ArrayList<>();
        readAnswers.forEach((readQuestionId, readValue) -> {
            Map<String, Object> writeItem = new LinkedHashMap<>();
            writeItem.put("questionId", readQuestionId);
            writeItem.put("value", readValue);
            writeItems.add(writeItem);
        });
        try {
            return objectMapper.writeValueAsString(writeItems);
        } catch (JsonProcessingException readError) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR, "问卷答案序列化失败");
        }
    }

    private void recordChange(
            Long userId,
            Long jobPostId,
            QuestionnaireSubmissionStatus targetStatus,
            PreparedChange readChange,
            Long appliedVersion) {
        changeService.recordApplied(new UserChangeService.AppliedChange(
                userId,
                "QUESTIONNAIRE_UI",
                ChangeResourceType.QUESTIONNAIRE_ANSWER,
                jobPostId.toString(),
                readChange.operation(),
                readChange.changedFields(),
                readChange.expectedVersion(),
                appliedVersion,
                readChange.beforeExists(),
                readChange.afterExists(),
                readChange.beforeFields(),
                readChange.afterFields(),
                targetStatus == QuestionnaireSubmissionStatus.DRAFT
                        ? "保存问卷草稿" : "提交问卷作答"));
    }

    @Override
    public PageResult<QuestionnaireAnswerResponse> listByJobPostId(Long jobPostId, int page, int size) {
        Page<QuestionnaireAnswerEntity> readAnswers = page(
                createPage(page, size),
                new LambdaQueryWrapper<QuestionnaireAnswerEntity>()
                        .eq(QuestionnaireAnswerEntity::getJobPostId, jobPostId)
                        .isNull(QuestionnaireAnswerEntity::getDeletedAt)
                        .in(QuestionnaireAnswerEntity::getSubmissionStatus,
                                QuestionnaireSubmissionStatus.SUBMITTED,
                                QuestionnaireSubmissionStatus.REVIEWED)
                        .orderByDesc(QuestionnaireAnswerEntity::getCreatedAt)
        );

        PageResult<QuestionnaireAnswerResponse> readPage = new PageResult<>(readAnswers.getTotal(),
                (int) readAnswers.getCurrent(), (int) readAnswers.getSize());
        readAnswers.getRecords().forEach(e -> readPage.add(toResponse(e)));
        return readPage;
    }

    @Override
    public QuestionnaireAnswerResponse getByUserAndJobPost(Long userId, Long jobPostId) {
        QuestionnaireAnswerEntity entity = findByUserAndJob(userId, jobPostId);
        return toResponse(entity);
    }

    @Override
    public QuestionnaireAnswerEntity getOwnAnswerIncludingDeleted(Long userId, Long jobPostId) {
        return getOne(new LambdaQueryWrapper<QuestionnaireAnswerEntity>()
                .eq(QuestionnaireAnswerEntity::getUserId, userId)
                .eq(QuestionnaireAnswerEntity::getJobPostId, jobPostId));
    }

    @Transactional
    @Override
    public Long restoreSnapshot(
            Long userId,
            Long jobPostId,
            Long expectedVersion,
            boolean updateExists,
            Map<String, Object> updateFields) {
        QuestionnaireAnswerEntity updateAnswer = getOwnAnswerIncludingDeleted(userId, jobPostId);
        if (!updateExists) {
            if (updateAnswer == null
                    || updateAnswer.getDeletedAt() != null
                    || expectedVersion == null
                    || !expectedVersion.equals(updateAnswer.getVersion())) {
                throw buildConflict();
            }
            UpdateWrapper<QuestionnaireAnswerEntity> update = new UpdateWrapper<>();
            update.eq("id", updateAnswer.getId())
                    .eq("user_id", userId)
                    .eq("version", expectedVersion)
                    .isNull("deleted_at")
                    .set("deleted_at", LocalDateTime.now())
                    .set("updated_at", LocalDateTime.now())
                    .setSql("version = version + 1");
            if (baseMapper.update(null, update) != 1) {
                throw buildConflict();
            }
            return expectedVersion + 1;
        }

        validateSnapshotFields(updateFields);
        if (updateAnswer == null) {
            if (expectedVersion != null) {
                throw buildConflict();
            }
            updateAnswer = new QuestionnaireAnswerEntity();
            updateAnswer.setUserId(userId);
            updateAnswer.setJobPostId(jobPostId);
            applySnapshotFields(updateAnswer, updateFields);
            requireRestorableAnswer(updateAnswer);
            updateAnswer.setVersion(0L);
            try {
                if (!save(updateAnswer)) {
                    throw buildConflict();
                }
            } catch (DuplicateKeyException readError) {
                throw buildConflict();
            }
            return 0L;
        }
        if (expectedVersion == null || !expectedVersion.equals(updateAnswer.getVersion())) {
            throw buildConflict();
        }
        Long readVersion = updateAnswer.getVersion();
        applySnapshotFields(updateAnswer, updateFields);
        requireRestorableAnswer(updateAnswer);
        updateAnswer.setDeletedAt(null);
        updateAnswer.setUpdatedAt(LocalDateTime.now());
        updateSnapshotAnswer(updateAnswer, readVersion, updateFields);
        return readVersion + 1;
    }

    private void validateSnapshotFields(Map<String, Object> updateFields) {
        Set<String> readAllowed = Set.of(
                "answers", "submissionStatus", "reviewedAt",
                "reviewedBy", "reviewPassed", "reviewComments");
        if (updateFields == null
                || updateFields.isEmpty()
                || updateFields.keySet().stream().anyMatch(readField -> !readAllowed.contains(readField))) {
            throw buildConflict();
        }
    }

    private void applySnapshotFields(
            QuestionnaireAnswerEntity updateAnswer,
            Map<String, Object> updateFields) {
        if (updateFields.containsKey("answers")) {
            Object readAnswers = updateFields.get("answers");
            updateAnswer.setAnswers(readAnswers == null ? null : String.valueOf(readAnswers));
        }
        if (updateFields.containsKey("submissionStatus")) {
            Object readStatus = updateFields.get("submissionStatus");
            try {
                updateAnswer.setSubmissionStatus(readStatus instanceof QuestionnaireSubmissionStatus readEnum
                        ? readEnum : QuestionnaireSubmissionStatus.valueOf(String.valueOf(readStatus)));
            } catch (IllegalArgumentException readError) {
                throw buildConflict();
            }
        }
        if (updateFields.containsKey("reviewedAt")) {
            Object readValue = updateFields.get("reviewedAt");
            try {
                updateAnswer.setReviewedAt(readValue == null ? null
                        : readValue instanceof LocalDateTime readTime
                        ? readTime : LocalDateTime.parse(String.valueOf(readValue)));
            } catch (RuntimeException readError) {
                throw buildConflict();
            }
        }
        if (updateFields.containsKey("reviewedBy")) {
            Object readValue = updateFields.get("reviewedBy");
            try {
                updateAnswer.setReviewedBy(readValue == null ? null
                        : Long.valueOf(String.valueOf(readValue)));
            } catch (NumberFormatException readError) {
                throw buildConflict();
            }
        }
        if (updateFields.containsKey("reviewPassed")) {
            Object readValue = updateFields.get("reviewPassed");
            if (readValue != null && !(readValue instanceof Boolean)) {
                throw buildConflict();
            }
            updateAnswer.setReviewPassed((Boolean) readValue);
        }
        if (updateFields.containsKey("reviewComments")) {
            Object readValue = updateFields.get("reviewComments");
            updateAnswer.setReviewComments(readValue == null ? null : String.valueOf(readValue));
        }
    }

    private void requireRestorableAnswer(QuestionnaireAnswerEntity readAnswer) {
        if (readAnswer.getAnswers() == null || readAnswer.getSubmissionStatus() == null) {
            throw buildConflict();
        }
    }

    @Override
    public QuestionnaireAnswerResponse getDetail(Long id) {
        return toResponse(findActiveAnswer(id));
    }

    @Override
    public MyQuestionnaireListPageResponse listMyByUserId(Long userId, int page, int size,
                                                          QuestionnaireSubmissionStatus status) {
        LambdaQueryWrapper<QuestionnaireAnswerEntity> wrapper = new LambdaQueryWrapper<QuestionnaireAnswerEntity>()
                .eq(QuestionnaireAnswerEntity::getUserId, userId)
                .isNull(QuestionnaireAnswerEntity::getDeletedAt)
                .orderByDesc(QuestionnaireAnswerEntity::getUpdatedAt);
        if (status != null) {
            wrapper.eq(QuestionnaireAnswerEntity::getSubmissionStatus, status);
        }

        Page<QuestionnaireAnswerEntity> readApplications = page(createPage(page, size), wrapper);
        List<QuestionnaireAnswerEntity> readRecords = readApplications.getRecords();
        Map<Long, JobPostEntity> readJobs = loadJobPostMap(readRecords);

        PageResult<MyQuestionnaireListItemResponse> readPage =
                new PageResult<>(readApplications.getTotal(),
                        (int) readApplications.getCurrent(), (int) readApplications.getSize());
        for (QuestionnaireAnswerEntity answer : readRecords) {
            JobPostEntity job = readJobs.get(answer.getJobPostId());
            if (job == null) {
                log.warn("投递记录 {} 关联岗位 {} 不存在，列表中跳过", answer.getId(), answer.getJobPostId());
                continue;
            }
            readPage.add(toListItem(answer, job));
        }

        MyQuestionnaireListPageResponse response = new MyQuestionnaireListPageResponse();
        response.setPage(readPage);
        response.setTabCounts(mapApplicationCounts(countMyApplications(userId)));
        return response;
    }

    @Override
    public QuestionnaireApplicationCount countMyApplications(Long userId) {
        return baseMapper.countMyApplications(userId);
    }

    private Map<String, Long> mapApplicationCounts(QuestionnaireApplicationCount readCount) {
        Map<String, Long> createCounts = new LinkedHashMap<>();
        createCounts.put("all", readCount.getTotal());
        createCounts.put("draft", readCount.getDraft());
        createCounts.put("pending", readCount.getPending());
        createCounts.put("done", readCount.getDone());
        createCounts.put("withdrawn", readCount.getWithdrawn());
        return createCounts;
    }

    @Transactional
    @Override
    public QuestionnaireAnswerResponse review(Long answerId, QuestionnaireReviewRequest request, Long reviewedBy) {
        boolean passed = answerValidator.requireReviewPassed(request);
        String comments = answerValidator.normalizeReviewComments(request.getComments());
        QuestionnaireAnswerEntity entity = findActiveAnswer(answerId);
        if (entity == null) {
            throw ServiceException.of(QuestionnaireErrorCode.JOB_POST_NOT_FOUND, "投递记录不存在");
        }
        if (entity.getSubmissionStatus() == QuestionnaireSubmissionStatus.REVIEWED) {
            throw ServiceException.of(QuestionnaireErrorCode.ALREADY_REVIEWED);
        }
        if (entity.getSubmissionStatus() != QuestionnaireSubmissionStatus.SUBMITTED) {
            throw ServiceException.of(QuestionnaireErrorCode.INVALID_SUBMISSION_STATUS);
        }
        applyReview(entity, passed, comments, reviewedBy);
        updateAnswer(entity);
        return toResponse(entity);
    }

    @Transactional
    @Override
    public int reviewBatchByJobPost(Long jobPostId, QuestionnaireReviewRequest request, Long reviewedBy) {
        boolean passed = answerValidator.requireReviewPassed(request);
        String comments = answerValidator.normalizeReviewComments(request.getComments());
        List<QuestionnaireAnswerEntity> pending = list(
                new LambdaQueryWrapper<QuestionnaireAnswerEntity>()
                        .eq(QuestionnaireAnswerEntity::getJobPostId, jobPostId)
                        .isNull(QuestionnaireAnswerEntity::getDeletedAt)
                        .eq(QuestionnaireAnswerEntity::getSubmissionStatus, QuestionnaireSubmissionStatus.SUBMITTED)
        );
        if (pending.isEmpty()) {
            return 0;
        }
        for (QuestionnaireAnswerEntity entity : pending) {
            applyReview(entity, passed, comments, reviewedBy);
        }
        pending.forEach(this::updateAnswer);
        return pending.size();
    }

    private JobPostEntity requireOpenJob(Long jobPostId) {
        JobPostEntity job = jobPostService.getVisibleJob(jobPostId);
        if (job == null) {
            throw ServiceException.of(QuestionnaireErrorCode.JOB_POST_NOT_FOUND);
        }
        if (QuestionnaireDeadlineUtil.isExpired(
                job.getApplicationDeadline(), job.getWorkEndDate())) {
            throw ServiceException.of(QuestionnaireErrorCode.QUESTIONNAIRE_DEADLINE_PASSED);
        }
        return job;
    }

    private void assertCanEdit(QuestionnaireAnswerEntity existing) {
        if (existing != null && existing.getSubmissionStatus() == QuestionnaireSubmissionStatus.REVIEWED) {
            throw ServiceException.of(QuestionnaireErrorCode.INVALID_SUBMISSION_STATUS);
        }
    }

    private QuestionnaireAnswerEntity findByUserAndJob(Long userId, Long jobPostId) {
        return getOne(
                new LambdaQueryWrapper<QuestionnaireAnswerEntity>()
                        .eq(QuestionnaireAnswerEntity::getUserId, userId)
                        .eq(QuestionnaireAnswerEntity::getJobPostId, jobPostId)
                        .isNull(QuestionnaireAnswerEntity::getDeletedAt)
        );
    }

    private QuestionnaireAnswerEntity findActiveAnswer(Long answerId) {
        return getOne(new LambdaQueryWrapper<QuestionnaireAnswerEntity>()
                .eq(QuestionnaireAnswerEntity::getId, answerId)
                .isNull(QuestionnaireAnswerEntity::getDeletedAt));
    }

    private void updateAnswer(QuestionnaireAnswerEntity updateAnswer) {
        if (!updateById(updateAnswer)) {
            throw buildConflict();
        }
    }

    private void updateEditableAnswer(
            QuestionnaireAnswerEntity updateAnswer,
            Long expectedVersion) {
        UpdateWrapper<QuestionnaireAnswerEntity> update = baseAnswerUpdate(
                updateAnswer, expectedVersion)
                .set("answers", updateAnswer.getAnswers())
                .set("submission_status", updateAnswer.getSubmissionStatus().getCode())
                .set("reviewed_at", null)
                .set("reviewed_by", null)
                .set("review_passed", null)
                .set("review_comments", null)
                .set("deleted_at", null);
        applyAnswerUpdate(updateAnswer, expectedVersion, update);
    }

    private void updateSnapshotAnswer(
            QuestionnaireAnswerEntity updateAnswer,
            Long expectedVersion,
            Map<String, Object> updateFields) {
        UpdateWrapper<QuestionnaireAnswerEntity> update = baseAnswerUpdate(
                updateAnswer, expectedVersion).set("deleted_at", null);
        if (updateFields.containsKey("answers")) {
            update.set("answers", updateAnswer.getAnswers());
        }
        if (updateFields.containsKey("submissionStatus")) {
            update.set("submission_status", updateAnswer.getSubmissionStatus().getCode());
        }
        if (updateFields.containsKey("reviewedAt")) {
            update.set("reviewed_at", updateAnswer.getReviewedAt());
        }
        if (updateFields.containsKey("reviewedBy")) {
            update.set("reviewed_by", updateAnswer.getReviewedBy());
        }
        if (updateFields.containsKey("reviewPassed")) {
            update.set("review_passed", updateAnswer.getReviewPassed());
        }
        if (updateFields.containsKey("reviewComments")) {
            update.set("review_comments", updateAnswer.getReviewComments());
        }
        applyAnswerUpdate(updateAnswer, expectedVersion, update);
    }

    private UpdateWrapper<QuestionnaireAnswerEntity> baseAnswerUpdate(
            QuestionnaireAnswerEntity updateAnswer,
            Long expectedVersion) {
        UpdateWrapper<QuestionnaireAnswerEntity> update = new UpdateWrapper<>();
        return update.eq("id", updateAnswer.getId())
                .eq("user_id", updateAnswer.getUserId())
                .eq("version", expectedVersion)
                .set("updated_at", LocalDateTime.now())
                .setSql("version = version + 1");
    }

    private void applyAnswerUpdate(
            QuestionnaireAnswerEntity updateAnswer,
            Long expectedVersion,
            UpdateWrapper<QuestionnaireAnswerEntity> update) {
        if (baseMapper.update(null, update) != 1) {
            throw buildConflict();
        }
        updateAnswer.setVersion(expectedVersion + 1);
        updateAnswer.setDeletedAt(null);
    }

    private ServiceException buildConflict() {
        return ServiceException.of(ResultCode.CONFLICT, "投递记录已发生变化，请刷新后重试");
    }

    private void validateFileAnswers(
            Long userId,
            Map<Long, Object> readAnswers,
            List<JobPostQuestionResponse> readQuestions) {
        for (JobPostQuestionResponse readQuestion : readQuestions) {
            if (readQuestion.getQuestionType() != QuestionType.FILE_UPLOAD
                    || !readAnswers.containsKey(readQuestion.getId())) {
                continue;
            }
            Object readValue = readAnswers.get(readQuestion.getId());
            if (readValue == null || readValue instanceof String readText && readText.isBlank()) {
                continue;
            }
            resumeFileService.getOwnFile(userId, answerValidator.readFileId(readValue));
        }
    }

    /** 岗位 → 列表项：忽略与作答记录冲突的字段 */
    private static final CopyOptions JOB_TO_LIST_ITEM_OPTIONS = CopyOptions.create()
            .setIgnoreProperties("id", "createdAt", "updatedAt");

    private void clearReviewMetadata(QuestionnaireAnswerEntity entity) {
        entity.setReviewedAt(null);
        entity.setReviewedBy(null);
        entity.setReviewPassed(null);
        entity.setReviewComments(null);
    }

    private void applyReview(QuestionnaireAnswerEntity entity, boolean passed, String comments, Long reviewedBy) {
        entity.setSubmissionStatus(QuestionnaireSubmissionStatus.REVIEWED);
        entity.setReviewedAt(LocalDateTime.now());
        entity.setReviewedBy(reviewedBy);
        entity.setReviewPassed(passed);
        entity.setReviewComments(comments);
        entity.setUpdatedAt(LocalDateTime.now());
    }

    private Map<Long, JobPostEntity> loadJobPostMap(List<QuestionnaireAnswerEntity> records) {
        if (CollectionUtils.isEmpty(records)) {
            return Collections.emptyMap();
        }
        List<Long> jobPostIds = records.stream()
                .map(QuestionnaireAnswerEntity::getJobPostId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (jobPostIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return jobPostService.listByIds(jobPostIds).stream()
                .collect(Collectors.toMap(JobPostEntity::getId, Function.identity(), (a, b) -> a));
    }

    private MyQuestionnaireListItemResponse toListItem(QuestionnaireAnswerEntity answer, JobPostEntity job) {
        MyQuestionnaireListItemResponse item = BeanUtil.copyProperties(answer, MyQuestionnaireListItemResponse.class);
        BeanUtil.copyProperties(job, item, JOB_TO_LIST_ITEM_OPTIONS);
        item.setQuestionnaireDeadline(job.getApplicationDeadline());
        item.setExpired(QuestionnaireDeadlineUtil.isExpired(
                job.getApplicationDeadline(), job.getWorkEndDate()));
        applyStatusLabel(item, answer.getSubmissionStatus());
        return item;
    }

    private QuestionnaireAnswerResponse toResponse(QuestionnaireAnswerEntity entity) {
        if (entity == null) {
            return null;
        }
        QuestionnaireAnswerResponse resp = BeanUtil.copyProperties(entity, QuestionnaireAnswerResponse.class);
        applyStatusLabel(resp, entity.getSubmissionStatus());
        enrichUser(resp, entity.getUserId());
        return resp;
    }

    private void applyStatusLabel(MyQuestionnaireListItemResponse item, QuestionnaireSubmissionStatus status) {
        QuestionnaireSubmissionStatus resolved = resolveStatus(status);
        item.setSubmissionStatus(resolved);
        item.setStatusLabel(resolved.getLabel());
    }

    private void applyStatusLabel(QuestionnaireAnswerResponse resp, QuestionnaireSubmissionStatus status) {
        QuestionnaireSubmissionStatus resolved = resolveStatus(status);
        resp.setSubmissionStatus(resolved);
        resp.setStatusLabel(resolved.getLabel());
    }

    private QuestionnaireSubmissionStatus resolveStatus(QuestionnaireSubmissionStatus status) {
        return status != null ? status : QuestionnaireSubmissionStatus.SUBMITTED;
    }

    private record ValidatedAnswers(String answersJson) {
    }

    private void enrichUser(QuestionnaireAnswerResponse readResponse, Long readUserId) {
        try {
            UserResponse readUser = userService.getUserById(readUserId);
            if (readUser != null) {
                readResponse.setUsername(readUser.getUsername());
                readResponse.setRealName(readUser.getRealName());
                readResponse.setStudentId(readUser.getStudentId());
            }
        } catch (Exception e) {
            log.warn("查询投递用户信息失败, userId={}", readUserId, e);
        }
    }
}
