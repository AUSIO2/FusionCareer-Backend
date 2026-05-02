package com.fusioncareer.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.ResumeRequest;
import com.fusioncareer.dto.res.ResumeResponse;
import com.fusioncareer.entity.ResumeEntity;

public interface ResumeService extends IService<ResumeEntity> {

    ResumeResponse getResume(Long userId);

    void saveOrUpdateResume(Long userId, ResumeRequest request);

    PageResult<ResumeResponse> listResumes(int page, int size);

    void updateResume(Long userId, ResumeRequest request);
}
