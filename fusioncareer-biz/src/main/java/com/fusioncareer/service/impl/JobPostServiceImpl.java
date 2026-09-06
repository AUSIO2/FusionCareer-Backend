package com.fusioncareer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.JobPostApplicationCount;
import com.fusioncareer.dto.req.JobPostQueryRequest;
import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.entity.JobPostEntity;
import com.fusioncareer.enums.JobPostSort;
import com.fusioncareer.enums.JobPostStatus;
import com.fusioncareer.mapper.JobPostMapper;
import com.fusioncareer.mapper.QuestionnaireAnswerMapper;
import com.fusioncareer.service.JobPostService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
        save(entity);
        return toResponse(entity);
    }

    @Transactional
    @Override
    public void createJobPostBatch(List<JobPostRequest> requests) {
        List<JobPostEntity> entities = requests.stream().map(req -> {
            JobPostEntity e = new JobPostEntity();
            BeanUtils.copyProperties(req, e);
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
        Page<JobPostEntity> readJobs = page(
                createPage(query.getPage(), query.getSize()), buildJobQuery(query));
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
        buildQuery.eq(JobPostEntity::getStatus, JobPostStatus.PUBLISHED);

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
         .and(StringUtils.hasText(readQuery.getKeyword()), readKeyword -> readKeyword
                 .like(JobPostEntity::getPositionName, readQuery.getKeyword())
                 .or()
                 .like(JobPostEntity::getCompanyName, readQuery.getKeyword()));
        sortJobs(buildQuery, readQuery.getSortBy());
        return buildQuery;
    }

    private void sortJobs(LambdaQueryWrapper<JobPostEntity> buildQuery, JobPostSort readSort) {
        if (readSort == JobPostSort.DEADLINE) {
            buildQuery.last("ORDER BY work_end_date IS NULL, work_end_date ASC, created_at DESC");
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
