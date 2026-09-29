package com.fusioncareer.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AiMessageRequest(
        @NotBlank(message = "clientRequestId 不能为空")
        @Size(max = 64, message = "clientRequestId 最多允许 64 个字符")
        @Pattern(regexp = "[A-Za-z0-9_-]+", message = "clientRequestId 格式无效")
        String clientRequestId,

        @NotBlank(message = "content 不能为空")
        @Size(max = 8000, message = "content 最多允许 8000 个字符")
        String content,

        @Size(max = 5, message = "单次最多允许 5 个附件")
        List<Long> fileIds) {
}
