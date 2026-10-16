package com.fusioncareer.dto.req;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人空间资源的字段级修改请求。
 *
 * <p>字段必须出现在 set 或 clear 之一；clear 用于明确写入 SQL NULL。</p>
 */
@Data
public class PersonalSpacePatchRequest {

    @NotNull(message = "expectedVersion 不能为空")
    @PositiveOrZero(message = "expectedVersion 不能小于 0")
    private Long expectedVersion;

    private Map<String, Object> set = new LinkedHashMap<>();

    private List<String> clear = List.of();
}
