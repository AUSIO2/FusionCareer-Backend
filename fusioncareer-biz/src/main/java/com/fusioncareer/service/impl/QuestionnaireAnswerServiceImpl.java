package com.fusioncareer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
import com.fusioncareer.util.QuestionnaireAnswerValidator;
import com.fusioncareer.util.QuestionnaireDeadlineUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    @Transactional
    @Override
    public QuestionnaireAnswerResponse saveDraft(Long userId, QuestionnaireSubmitRequest request) {
        requireOpenJob(request.getJobPostId());
        List<JobPostQuestionResponse> readQuestions = jobPostQuestionService.listByJobPostId(request.getJobPostId());
        Map<Long, Object> readAnswers = answerValidator.validateDraftAnswers(
                request.getAnswers(), readQuestions);
        validateFileAnswers(userId, readAnswers, readQuestions);
        QuestionnaireAnswerEntity existing = findByUserAndJob(userId, request.getJobPostId());
        assertCanEdit(existing);
        if (existing != null && existing.getSubmissionStatus() == QuestionnaireSubmissionStatus.SUBMITTED) {
            throw ServiceException.of(QuestionnaireErrorCode.INVALID_SUBMISSION_STATUS,
                    "已提交的投递请使用正式提交接口修改");
        }

        if (existing != null) {
            applySubmitRequest(existing, request);
            clearReviewMetadata(existing);
            existing.setSubmissionStatus(QuestionnaireSubmissionStatus.DRAFT);
            existing.setUpdatedAt(LocalDateTime.now());
            updateAnswer(existing);
            return toResponse(existing);
        }

        QuestionnaireAnswerEntity entity = newQuestionnaireAnswer(userId, request);
        entity.setSubmissionStatus(QuestionnaireSubmissionStatus.DRAFT);
        entity.setVersion(0L);
        save(entity);
        return toResponse(entity);
    }

    @Transactional
    @Override
    public QuestionnaireAnswerResponse submit(Long userId, QuestionnaireSubmitRequest request) {
        requireOpenJob(request.getJobPostId());
        List<JobPostQuestionResponse> questions = jobPostQuestionService.listByJobPostId(request.getJobPostId());
        Map<Long, Object> readAnswers = answerValidator.validateRequiredAnswers(request.getAnswers(), questions);
        validateFileAnswers(userId, readAnswers, questions);

        QuestionnaireAnswerEntity existing = findByUserAndJob(userId, request.getJobPostId());
        if (existing != null && existing.getSubmissionStatus() == QuestionnaireSubmissionStatus.REVIEWED) {
            throw ServiceException.of(QuestionnaireErrorCode.INVALID_SUBMISSION_STATUS);
        }

        if (existing != null) {
            applySubmitRequest(existing, request);
            clearReviewMetadata(existing);
            existing.setSubmissionStatus(QuestionnaireSubmissionStatus.SUBMITTED);
            existing.setUpdatedAt(LocalDateTime.now());
            updateAnswer(existing);
            log.info("用户 {} 提交岗位 {} 的问卷", userId, request.getJobPostId());
            return toResponse(existing);
        }

        QuestionnaireAnswerEntity entity = newQuestionnaireAnswer(userId, request);
        entity.setSubmissionStatus(QuestionnaireSubmissionStatus.SUBMITTED);
        entity.setVersion(0L);
        save(entity);
        log.info("用户 {} 首次提交岗位 {} 的问卷", userId, request.getJobPostId());
        return toResponse(entity);
    }

    @Override
    public PageResult<QuestionnaireAnswerResponse> listByJobPostId(Long jobPostId, int page, int size) {
        Page<QuestionnaireAnswerEntity> readAnswers = page(
                createPage(page, size),
                new LambdaQueryWrapper<QuestionnaireAnswerEntity>()
                        .eq(QuestionnaireAnswerEntity::getJobPostId, jobPostId)
                        .isNull(QuestionnaireAnswerEntity::getDeletedAt)
                        .ne(QuestionnaireAnswerEntity::getSubmissionStatus,
                                QuestionnaireSubmissionStatus.DRAFT)
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
            throw ServiceException.of(ResultCode.CONFLICT, "投递记录已发生变化，请刷新后重试");
        }
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

    private void applySubmitRequest(QuestionnaireAnswerEntity entity, QuestionnaireSubmitRequest request) {
        BeanUtil.copyProperties(request, entity);
    }

    private QuestionnaireAnswerEntity newQuestionnaireAnswer(Long userId, QuestionnaireSubmitRequest request) {
        QuestionnaireAnswerEntity entity = BeanUtil.copyProperties(request, QuestionnaireAnswerEntity.class);
        entity.setUserId(userId);
        return entity;
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
