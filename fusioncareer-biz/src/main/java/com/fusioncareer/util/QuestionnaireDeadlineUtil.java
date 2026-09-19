package com.fusioncareer.util;

import java.time.LocalDate;

/**
 * 问卷截止日判定（截止日当天仍可提交）
 */
public final class QuestionnaireDeadlineUtil {

    private QuestionnaireDeadlineUtil() {
    }

    public static boolean isExpired(LocalDate deadline) {
        return deadline != null && LocalDate.now().isAfter(deadline);
    }

    public static boolean isExpired(LocalDate applicationDeadline, LocalDate workEndDate) {
        return isExpired(applicationDeadline) || isExpired(workEndDate);
    }
}
