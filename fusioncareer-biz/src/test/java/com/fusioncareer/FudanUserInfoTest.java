package com.fusioncareer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.service.impl.FudanSsoServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class FudanUserInfoTest {
    private final FudanSsoServiceImpl readService = new FudanSsoServiceImpl(
            null, null, null, null, new ObjectMapper(), null, null);

    @Test
    void readV3Identity() throws Exception {
        // 字段结构来自 2026-10-07 的真实 v3 响应，值全部使用虚构测试数据。
        var readInfo = new ObjectMapper().readTree("""
                {"user_type":"本校学生","user_id":"202600001","user_name":"测试同学",
                "mobile":"13800000000","sessionId":"test-session","department":"100286",
                "email":"test@example.test"}
                """);
        String readId = ReflectionTestUtils.invokeMethod(readService, "resolveFudanUserId", readInfo);
        String readName = ReflectionTestUtils.invokeMethod(readService, "readFudanName", readInfo, readId);
        assertThat(readId).isEqualTo("202600001");
        assertThat(readName).isEqualTo("测试同学");
    }

    @Test
    void preferV3Fields() throws Exception {
        var readInfo = new ObjectMapper().readTree("""
                {"user_id":"202600001","userId":"legacy-account","user_name":"测试同学",
                "userName":"202600001","realName":"旧格式姓名"}
                """);
        String readId = ReflectionTestUtils.invokeMethod(readService, "resolveFudanUserId", readInfo);
        String readName = ReflectionTestUtils.invokeMethod(readService, "readFudanName", readInfo, readId);
        assertThat(readId).isEqualTo("202600001");
        assertThat(readName).isEqualTo("测试同学");
    }

    @Test
    void omitV3Identifier() throws Exception {
        var readInfo = new ObjectMapper().readTree("{\"user_id\":\"202600001\",\"user_name\":\"202600001\"}");
        String readName = ReflectionTestUtils.invokeMethod(readService, "readFudanName", readInfo, "202600001");
        assertThat(readName).isNull();
    }

}
