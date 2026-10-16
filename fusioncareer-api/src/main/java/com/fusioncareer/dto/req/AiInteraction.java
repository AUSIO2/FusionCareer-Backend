package com.fusioncareer.dto.req;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record AiInteraction(
        @NotNull @Min(1) @Max(1) Integer schemaVersion,
        @NotNull @Pattern(regexp = "job_recommendation|resume_advice") String type,
        @Valid JobRecommendationRequest preferences,
        @Pattern(regexp = "online|file") String source,
        @Size(max = 1) List<@NotNull @Pattern(regexp = "[0-9]{1,19}") String> fileIds) {
    public AiInteraction(Integer schemaVersion, String type, JobRecommendationRequest preferences) {
        this(schemaVersion, type, preferences, null, null);
    }

    @AssertTrue(message = "交互参数与类型不匹配")
    public boolean isValidInteraction() {
        if ("job_recommendation".equals(type)) {
            return preferences != null && source == null && (fileIds == null || fileIds.isEmpty());
        }
        if (!"resume_advice".equals(type) || preferences != null) return false;
        return "online".equals(source) ? fileIds == null || fileIds.isEmpty()
                : "file".equals(source) && fileIds != null && fileIds.size() == 1;
    }
}
