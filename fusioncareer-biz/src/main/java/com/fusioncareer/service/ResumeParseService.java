package com.fusioncareer.service;

import com.fusioncareer.dto.res.ResumeUploadResponse;

import java.util.List;
import java.util.Map;

public interface ResumeParseService {
    ResumeUploadResponse updateResume(Long updateUserId, Long updateFileId);

    /** 解析当前用户文件并返回经过字段白名单清洗的提案数据，不写业务表。 */
    ParsedPatches parseForProposal(Long userId, Long fileId);

    record ParsedPatches(
            Map<String, Object> profileSet,
            Map<String, Object> resumeSet,
            List<String> warnings) {
    }
}
