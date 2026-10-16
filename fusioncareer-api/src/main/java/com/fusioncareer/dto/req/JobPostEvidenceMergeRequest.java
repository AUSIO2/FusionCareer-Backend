package com.fusioncareer.dto.req;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Internal request for monotonically merging newly extracted evidence into an
 * already-persisted job post.
 */
@Data
public class JobPostEvidenceMergeRequest {
    private LocalDateTime expectedUpdatedAt;
    private JobPostRequest incoming;
}
