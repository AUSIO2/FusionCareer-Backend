package com.fusioncareer.dto.res;

import lombok.Data;
import com.fusioncareer.enums.ResumeParseStatus;

import java.util.ArrayList;
import java.util.List;

@Data
public class ResumeUploadResponse {
    private ResumeFileResponse file;
    private ResumeParseStatus parseStatus;
    private List<String> updatedProfileFields = new ArrayList<>();
    private List<String> updatedResumeFields = new ArrayList<>();
    private String message;
}
