package com.fusioncareer.dto.req;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record AiInteraction(
        @NotNull @Min(1) @Max(1) Integer schemaVersion,
        @NotNull @Pattern(regexp = "job_recommendation") String type,
        @NotNull @Valid JobRecommendationRequest preferences) { }
