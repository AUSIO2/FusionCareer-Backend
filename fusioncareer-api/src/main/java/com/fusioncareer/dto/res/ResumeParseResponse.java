package com.fusioncareer.dto.res;

import com.fusioncareer.dto.req.ResumeRequest;
import com.fusioncareer.dto.req.UserProfileRequest;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ResumeParseResponse {
    private UserProfileRequest profilePatch;
    private ResumeRequest resumePatch;
    private List<String> warnings = new ArrayList<>();
}
