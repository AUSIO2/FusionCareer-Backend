package com.fusioncareer.dto.res;

import com.fusioncareer.common.PageResult;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 学生「我的投递」分页列表（含 Tab 计数）
 */
@Data
public class MyQuestionnaireListPageResponse {

    private PageResult<MyQuestionnaireListItemResponse> page = new PageResult<>();

    /** all / draft / pending / done */
    private Map<String, Long> tabCounts = new LinkedHashMap<>();
}
