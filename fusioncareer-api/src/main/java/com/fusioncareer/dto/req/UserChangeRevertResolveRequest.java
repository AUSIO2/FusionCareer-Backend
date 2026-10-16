package com.fusioncareer.dto.req;

import com.fusioncareer.enums.ChangeResourceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 用户显式选择冲突字段后生成新的 Revert 提案。 */
public record UserChangeRevertResolveRequest(
        @NotEmpty(message = "selections 不能为空")
        @Size(max = 16, message = "selections 最多 16 项")
        List<@Valid Selection> selections) {

    public record Selection(
            @NotNull(message = "resourceType 不能为空")
            ChangeResourceType resourceType,
            @NotBlank(message = "resourceKey 不能为空")
            @Size(max = 128, message = "resourceKey 最多 128 个字符")
            String resourceKey,
            @Size(max = 32, message = "fields 最多 32 项")
            List<@NotBlank String> fields,
            boolean forceResourceState) {
    }
}
