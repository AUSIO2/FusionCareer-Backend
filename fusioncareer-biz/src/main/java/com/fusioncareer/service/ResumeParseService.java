package com.fusioncareer.service;

import com.fusioncareer.dto.res.ResumeUploadResponse;

public interface ResumeParseService {
    ResumeUploadResponse updateResume(Long updateUserId, Long updateFileId);
}
