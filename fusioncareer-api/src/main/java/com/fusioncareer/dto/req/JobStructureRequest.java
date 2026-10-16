package com.fusioncareer.dto.req;

import com.fusioncareer.enums.JobPostStatus;
import com.fusioncareer.enums.SourceType;
import lombok.Data;

@Data
public class JobStructureRequest {
    private String text;
    private String sourceUrl;
    private SourceType sourceType = SourceType.PLATFORM;
    private JobPostStatus defaultStatus = JobPostStatus.OFFLINE;
}
