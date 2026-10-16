package com.fusioncareer;

import com.fusioncareer.util.QuestionnaireDeadlineUtil;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionnaireDeadlineUtilTest {

    @Test
    void expireEitherDeadline() {
        LocalDate readYesterday = LocalDate.now().minusDays(1);
        LocalDate readTomorrow = LocalDate.now().plusDays(1);

        assertThat(QuestionnaireDeadlineUtil.isExpired(readYesterday, readTomorrow)).isTrue();
        assertThat(QuestionnaireDeadlineUtil.isExpired(readTomorrow, readYesterday)).isTrue();
        assertThat(QuestionnaireDeadlineUtil.isExpired(readTomorrow, null)).isFalse();
    }
}
