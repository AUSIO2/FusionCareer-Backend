package com.fusioncareer.dto.res;

import java.util.List;

/**
 * 当前用户个人空间文件列表与配额。
 */
public record PersonalSpaceDocumentsResponse(
        List<ResumeFileResponse> files,
        long usedBytes,
        long quotaBytes) {
}
