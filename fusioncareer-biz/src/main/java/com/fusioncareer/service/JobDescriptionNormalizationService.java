package com.fusioncareer.service;

import com.fusioncareer.dto.req.JobPostRequest;

public interface JobDescriptionNormalizationService {

    JobPostRequest normalize(String rawDescription);
}
