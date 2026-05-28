package com.fusioncareer.util;

import java.time.LocalDate;

/**
 * 问卷截止日判定（与岗位 work_end_date 对齐，截止日当天仍可提交）
 */
public final class QuestionnaireDeadlineUtil {

    private QuestionnaireDeadlineUtil() {
    }

    public static boolean isExpired(LocalDate deadline) {
        return deadline != null && LocalDate.now().isAfter(deadline);
    }
}
