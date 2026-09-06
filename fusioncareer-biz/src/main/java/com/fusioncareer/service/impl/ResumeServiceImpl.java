package com.fusioncareer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.ResumeRequest;
import com.fusioncareer.dto.res.ResumeResponse;
import com.fusioncareer.entity.ResumeEntity;
import com.fusioncareer.mapper.ResumeMapper;
import com.fusioncareer.service.ResumeService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.fusioncareer.util.PaginationUtil.createPage;

@Service
public class ResumeServiceImpl extends ServiceImpl<ResumeMapper, ResumeEntity> implements ResumeService {

    @Override
    public ResumeResponse getResume(Long userId) {
        return toResponse(getById(userId));
    }

    @Transactional
    @Override
    public void saveOrUpdateResume(Long userId, ResumeRequest request) {
        ResumeEntity entity = new ResumeEntity();
        BeanUtils.copyProperties(request, entity);
        entity.setUserId(userId);
        saveOrUpdate(entity);
    }

    @Override
    public PageResult<ResumeResponse> listResumes(int page, int size) {
        Page<ResumeEntity> readResumes = page(createPage(page, size),
                new LambdaQueryWrapper<ResumeEntity>().orderByDesc(ResumeEntity::getCreatedAt));

        PageResult<ResumeResponse> readPage = new PageResult<>(readResumes.getTotal(),
                (int) readResumes.getCurrent(), (int) readResumes.getSize());
        readResumes.getRecords().forEach(e -> readPage.add(toResponse(e)));
        return readPage;
    }

    @Transactional
    @Override
    public void updateResume(Long userId, ResumeRequest request) {
        ResumeEntity entity = new ResumeEntity();
        BeanUtils.copyProperties(request, entity);
        entity.setUserId(userId);
        updateById(entity);
    }

    private ResumeResponse toResponse(ResumeEntity entity) {
        if (entity == null) return null;
        ResumeResponse resp = new ResumeResponse();
        BeanUtils.copyProperties(entity, resp);
        return resp;
    }
}
