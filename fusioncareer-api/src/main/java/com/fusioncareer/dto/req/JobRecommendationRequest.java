package com.fusioncareer.dto.req;

import com.fusioncareer.enums.JobCategory;
import com.fusioncareer.enums.RecruitType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

@Data
public class JobRecommendationRequest {
    @Size(max = 5)
    private List<@NotNull JobCategory> jobCategories = List.of();
    @Size(max = 20)
    private List<@NotBlank @Size(max = 100) String> workCities = List.of();
    @Size(max = 20)
    private List<@NotBlank @Size(max = 100) String> keywords = List.of();
    private RecruitType recruitType;
    @Size(max = 1000)
    private String text = "";
}
