package com.fusioncareer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.JobPostQueryRequest;
import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.entity.JobPostEntity;
import com.fusioncareer.enums.JobPostSort;
import com.fusioncareer.enums.JobPostStatus;
import com.fusioncareer.mapper.JobPostMapper;
import com.fusioncareer.service.JobPostService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

import static com.fusioncareer.util.PaginationUtil.createPage;

@Service
public class JobPostServiceImpl extends ServiceImpl<JobPostMapper, JobPostEntity> implements JobPostService {

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
        return toResponse(getById(id));
    }

    @Override
    public PageResult<JobPostResponse> listJobPosts(JobPostQueryRequest query) {
        Page<JobPostEntity> readJobs = page(
                createPage(query.getPage(), query.getSize()), buildJobQuery(query));
        return mapPage(readJobs);
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

    private PageResult<JobPostResponse> mapPage(Page<JobPostEntity> readJobs) {
        PageResult<JobPostResponse> readPage = new PageResult<>(readJobs.getTotal(),
                (int) readJobs.getCurrent(), (int) readJobs.getSize());
        readJobs.getRecords().forEach(e -> readPage.add(toResponse(e)));
        return readPage;
    }

    private JobPostResponse toResponse(JobPostEntity entity) {
        if (entity == null) return null;
        JobPostResponse resp = new JobPostResponse();
        BeanUtils.copyProperties(entity, resp);
        return resp;
    }
}
