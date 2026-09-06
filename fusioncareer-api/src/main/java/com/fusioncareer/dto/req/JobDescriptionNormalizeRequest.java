package com.fusioncareer.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员粘贴的原始岗位描述。
 */
@Data
public class JobDescriptionNormalizeRequest {

    @NotBlank(message = "岗位描述不能为空")
    @Size(max = 10000, message = "岗位描述不能超过10000字")
    private String rawDescription;
}
