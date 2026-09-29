package com.fusioncareer;

import com.fusioncareer.util.PersonNameUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonNameTest {
    @Test
    void selectName() {
        assertThat(PersonNameUtil.readName("202600001", " 张同学 ", "王老师")).isEqualTo("张同学");
        assertThat(PersonNameUtil.readName("202600001", "202600001", "Jane Smith")).isEqualTo("Jane Smith");
        assertThat(PersonNameUtil.readName(null, null, "王老师")).isEqualTo("王老师");
    }

    @Test
    void rejectIdentifiers() {
        for (String readName : new String[]{"202600001", "２０２６００００１", "2026 00001", " ", "null", "undefined", null}) {
            assertThat(PersonNameUtil.readName("202600001", readName)).isNull();
        }
        assertThat(PersonNameUtil.readName("visitor-01", " VISITOR-01 ")).isNull();
        assertThat(PersonNameUtil.readName("a001", "ａ００１")).isNull();
    }
}
