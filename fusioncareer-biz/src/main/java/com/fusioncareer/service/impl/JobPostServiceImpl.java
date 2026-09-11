package com.fusioncareer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.JobPostApplicationCount;
import com.fusioncareer.dto.req.JobPostQueryRequest;
import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.dto.req.JobRecycleRequest;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.entity.JobPostEntity;
import com.fusioncareer.enums.JobPostSort;
import com.fusioncareer.enums.JobPostStatus;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.mapper.JobPostMapper;
import com.fusioncareer.mapper.QuestionnaireAnswerMapper;
import com.fusioncareer.service.JobPostService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.fusioncareer.util.PaginationUtil.createPage;

@Service
@RequiredArgsConstructor
public class JobPostServiceImpl extends ServiceImpl<JobPostMapper, JobPostEntity> implements JobPostService {

    private final QuestionnaireAnswerMapper readAnswerMapper;

    @Transactional
    @Override
    public JobPostResponse createJobPost(JobPostRequest request) {
        JobPostEntity entity = new JobPostEntity();
        BeanUtils.copyProperties(request, entity);
        applyDefaultDeadline(entity);
        prepareRecycleFields(entity);
        save(entity);
        return toResponse(entity);
    }

    @Transactional
    @Override
    public void createJobPostBatch(List<JobPostRequest> requests) {
        List<JobPostEntity> entities = requests.stream().map(req -> {
            JobPostEntity e = new JobPostEntity();
            BeanUtils.copyProperties(req, e);
            applyDefaultDeadline(e);
            prepareRecycleFields(e);
            return e;
        }).toList();
        saveBatch(entities);
    }

    @Override
    public JobPostResponse getJobPost(Long id) {
        JobPostResponse readJob = toResponse(getById(id));
        if (readJob != null) {
            mapApplications(List.of(readJob));
        }
        return readJob;
    }

    @Override
    public PageResult<JobPostResponse> listJobPosts(JobPostQueryRequest query) {
        LambdaQueryWrapper<JobPostEntity> readWrapper = buildJobQuery(query);
        readWrapper.ne(query.getStatus() == null,
                JobPostEntity::getStatus, JobPostStatus.RECYCLED);
        Page<JobPostEntity> readJobs = page(
                createPage(query.getPage(), query.getSize()), readWrapper);
        return mapPage(readJobs);
    }

    @Override
    public PageResult<JobPostResponse> listJobs(JobPostQueryRequest readQuery) {
        Page<JobPostEntity> readJobs = page(
                createPage(readQuery.getPage(), readQuery.getSize()), buildJobQuery(readQuery));
        List<JobPostResponse> readItems = readJobs.getRecords().stream().map(this::toResponse).toList();
        PageResult<JobPostResponse> readPage = new PageResult<>(
                readJobs.getTotal(), (int) readJobs.getCurrent(), (int) readJobs.getSize());
        readPage.addAll(readItems);
        return readPage;
    }

    @Override
    public PageResult<JobPostResponse> listPublishedJobPosts(JobPostQueryRequest query) {
        LambdaQueryWrapper<JobPostEntity> buildQuery = buildJobQuery(query);
        LocalDate readToday = LocalDate.now();
        buildQuery.eq(JobPostEntity::getStatus, JobPostStatus.PUBLISHED)
                .and(readJob -> readJob.isNull(JobPostEntity::getApplicationDeadline)
                        .or().ge(JobPostEntity::getApplicationDeadline, readToday))
                .and(readJob -> readJob.isNull(JobPostEntity::getWorkEndDate)
                        .or().ge(JobPostEntity::getWorkEndDate, readToday));

        Page<JobPostEntity> readJobs = page(
                createPage(query.getPage(), query.getSize()), buildQuery);
        return mapPage(readJobs);
    }

    @Transactional
    @Override
    public void updateJobPost(Long id, JobPostRequest request) {
        JobPostEntity entity = new JobPostEntity();
        BeanUtils.copyProperties(request, entity);
        entity.setId(id);
        updateById(entity);
    }

    @Transactional
    @Override
    public void recycleJob(Long id, String reason) {
        JobPostEntity updateJob = new JobPostEntity();
        updateJob.setId(id);
        updateJob.setStatus(JobPostStatus.RECYCLED);
        updateJob.setRecommended(false);
        updateJob.setRecycleReason(cleanReason(reason));
        updateJob.setRecycledAt(LocalDateTime.now());
        if (!updateById(updateJob)) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "岗位不存在");
        }
    }

    @Transactional
    @Override
    public void recycleJobs(List<JobRecycleRequest> requests) {
        LocalDateTime readNow = LocalDateTime.now();
        List<JobPostEntity> updateJobs = requests.stream().map(readRequest -> {
            JobPostEntity updateJob = new JobPostEntity();
            updateJob.setId(readRequest.getId());
            updateJob.setStatus(JobPostStatus.RECYCLED);
            updateJob.setRecommended(false);
            updateJob.setRecycleReason(cleanReason(readRequest.getReason()));
            updateJob.setRecycledAt(readNow);
            return updateJob;
        }).toList();
        if (!updateJobs.isEmpty()) {
            updateBatchById(updateJobs);
        }
    }

    @Transactional
    @Override
    public void restoreJob(Long id) {
        boolean readUpdated = lambdaUpdate()
                .eq(JobPostEntity::getId, id)
                .eq(JobPostEntity::getStatus, JobPostStatus.RECYCLED)
                .set(JobPostEntity::getStatus, JobPostStatus.OFFLINE)
                .set(JobPostEntity::getRecycleReason, null)
                .set(JobPostEntity::getRecycledAt, null)
                .update();
        if (!readUpdated) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "回收站中不存在该岗位");
        }
    }

    private String cleanReason(String readReason) {
        String readValue = StringUtils.hasText(readReason) ? readReason.trim() : "人工删除";
        return readValue.length() <= 512 ? readValue : readValue.substring(0, 512);
    }

    private void applyDefaultDeadline(JobPostEntity updateJob) {
        if (updateJob.getApplicationDeadline() == null) {
            updateJob.setApplicationDeadline(LocalDate.now().plusMonths(1));
        }
    }

    private void prepareRecycleFields(JobPostEntity updateJob) {
        if (updateJob.getStatus() == JobPostStatus.RECYCLED) {
            updateJob.setRecommended(false);
            updateJob.setRecycleReason(cleanReason(updateJob.getRecycleReason()));
            updateJob.setRecycledAt(LocalDateTime.now());
        }
    }

    // ==================== 私有方法 ====================

    private LambdaQueryWrapper<JobPostEntity> buildJobQuery(JobPostQueryRequest readQuery) {
        LambdaQueryWrapper<JobPostEntity> buildQuery = new LambdaQueryWrapper<>();
        buildQuery.eq(readQuery.getJobCategory() != null, JobPostEntity::getJobCategory, readQuery.getJobCategory())
         .eq(readQuery.getJobSubCategory() != null, JobPostEntity::getJobSubCategory, readQuery.getJobSubCategory())
         .eq(readQuery.getRecruitType() != null, JobPostEntity::getRecruitType, readQuery.getRecruitType())
         .eq(readQuery.getWorkDurationType() != null, JobPostEntity::getWorkDurationType, readQuery.getWorkDurationType())
         .eq(readQuery.getWorkPeriodType() != null, JobPostEntity::getWorkPeriodType, readQuery.getWorkPeriodType())
         .eq(readQuery.getWorkMode() != null, JobPostEntity::getWorkMode, readQuery.getWorkMode())
         .eq(StringUtils.hasText(readQuery.getWorkProvince()), JobPostEntity::getWorkProvince, readQuery.getWorkProvince())
         .eq(StringUtils.hasText(readQuery.getWorkCity()), JobPostEntity::getWorkCity, readQuery.getWorkCity())
         .ge(readQuery.getSalaryMin() != null, JobPostEntity::getSalaryMax, readQuery.getSalaryMin())
         .le(readQuery.getSalaryMax() != null, JobPostEntity::getSalaryMin, readQuery.getSalaryMax())
         .eq(readQuery.getRecommended() != null, JobPostEntity::getRecommended, readQuery.getRecommended())
         .eq(readQuery.getStatus() != null, JobPostEntity::getStatus, readQuery.getStatus())
         .eq(readQuery.getSourceType() != null, JobPostEntity::getSourceType, readQuery.getSourceType())
         .and(Boolean.TRUE.equals(readQuery.getInternalApply()), readSource -> readSource
                 .isNull(JobPostEntity::getSourceUrl)
                 .or().eq(JobPostEntity::getSourceUrl, ""))
         .and(StringUtils.hasText(readQuery.getKeyword()), readKeyword -> readKeyword
                 .like(JobPostEntity::getPositionName, readQuery.getKeyword())
                 .or()
                 .like(JobPostEntity::getCompanyName, readQuery.getKeyword()));
        sortJobs(buildQuery, readQuery.getSortBy());
        return buildQuery;
    }

    private void sortJobs(LambdaQueryWrapper<JobPostEntity> buildQuery, JobPostSort readSort) {
        if (readSort == JobPostSort.DEADLINE) {
            buildQuery.last("ORDER BY application_deadline IS NULL, application_deadline ASC, created_at DESC");
            return;
        }
        buildQuery.orderByDesc(JobPostEntity::getCreatedAt);
    }

    private PageResult<JobPostResponse> mapPage(Page<JobPostEntity> readEntities) {
        List<JobPostResponse> readJobs = readEntities.getRecords().stream()
                .map(this::toResponse)
                .toList();
        mapApplications(readJobs);

        PageResult<JobPostResponse> readPage = new PageResult<>(readEntities.getTotal(),
                (int) readEntities.getCurrent(), (int) readEntities.getSize());
        readPage.addAll(readJobs);
        return readPage;
    }

    private void mapApplications(List<JobPostResponse> updateJobs) {
        if (updateJobs.isEmpty()) {
            return;
        }
        List<Long> readJobIds = updateJobs.stream().map(JobPostResponse::getId).toList();
        Map<Long, Long> readCounts = readAnswerMapper.countApplications(readJobIds).stream()
                .collect(Collectors.toMap(JobPostApplicationCount::getJobPostId,
                        JobPostApplicationCount::getApplicationCount));
        updateJobs.forEach(updateJob -> updateJob.setApplicationCount(
                readCounts.getOrDefault(updateJob.getId(), 0L)));
    }

    private JobPostResponse toResponse(JobPostEntity entity) {
        if (entity == null) return null;
        JobPostResponse resp = new JobPostResponse();
        BeanUtils.copyProperties(entity, resp);
        resp.setApplicationCount(0L);
        return resp;
    }
}
