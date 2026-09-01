package com.fusioncareer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.JobPostQueryRequest;
import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.entity.JobPostEntity;
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
                createPage(query.getPage(), query.getSize()), buildWrapper(query));
        return mapPage(readJobs);
    }

    @Override
    public PageResult<JobPostResponse> listPublishedJobPosts(JobPostQueryRequest query) {
        LambdaQueryWrapper<JobPostEntity> wrapper = buildWrapper(query);
        wrapper.eq(JobPostEntity::getStatus, JobPostStatus.PUBLISHED);

        Page<JobPostEntity> readJobs = page(
                createPage(query.getPage(), query.getSize()), wrapper);
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

    private LambdaQueryWrapper<JobPostEntity> buildWrapper(JobPostQueryRequest q) {
        LambdaQueryWrapper<JobPostEntity> w = new LambdaQueryWrapper<>();
        w.eq(q.getJobCategory() != null, JobPostEntity::getJobCategory, q.getJobCategory())
         .eq(q.getJobSubCategory() != null, JobPostEntity::getJobSubCategory, q.getJobSubCategory())
         .eq(q.getRecruitType() != null, JobPostEntity::getRecruitType, q.getRecruitType())
         .eq(q.getWorkDurationType() != null, JobPostEntity::getWorkDurationType, q.getWorkDurationType())
         .eq(q.getWorkPeriodType() != null, JobPostEntity::getWorkPeriodType, q.getWorkPeriodType())
         .eq(q.getWorkMode() != null, JobPostEntity::getWorkMode, q.getWorkMode())
         .eq(StringUtils.hasText(q.getWorkCity()), JobPostEntity::getWorkCity, q.getWorkCity())
         .eq(q.getStatus() != null, JobPostEntity::getStatus, q.getStatus())
         .eq(q.getSourceType() != null, JobPostEntity::getSourceType, q.getSourceType())
         .and(StringUtils.hasText(q.getKeyword()), kw -> kw
                 .like(JobPostEntity::getPositionName, q.getKeyword())
                 .or()
                 .like(JobPostEntity::getCompanyName, q.getKeyword()))
         .orderByDesc(JobPostEntity::getCreatedAt);
        return w;
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
