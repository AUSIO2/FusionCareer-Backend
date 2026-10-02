package com.fusioncareer.dto.res;

import com.fusioncareer.dto.req.JobPostRequest;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class JobStructureResponse {
    private List<JobPostRequest> jobs = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
}
