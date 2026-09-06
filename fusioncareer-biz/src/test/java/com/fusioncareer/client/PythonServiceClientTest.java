package com.fusioncareer.client;

import com.fusioncareer.client.dto.JobNormalizeAlgorithmResponse;
import com.fusioncareer.dto.req.JobDescriptionNormalizeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PythonServiceClientTest {

    @Test
    void sendsJobDescriptionToNormalizationEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://algorithm.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PythonServiceClient client = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(PythonServiceClient.class);

        server.expect(requestTo("http://algorithm.test/api/v1/job/normalize"))
                .andExpect(method(POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"rawDescription":"岗位名称：新媒体运营实习生"}
                        """))
                .andRespond(withSuccess("""
                        {
                          "code": 200,
                          "message": "success",
                          "data": {"positionName": "新媒体运营实习生"}
                        }
                        """, MediaType.APPLICATION_JSON));

        JobDescriptionNormalizeRequest request = new JobDescriptionNormalizeRequest();
        request.setRawDescription("岗位名称：新媒体运营实习生");
        JobNormalizeAlgorithmResponse response = client.normalizeJob(request);

        assertThat(response.getData().get("positionName")).isEqualTo("新媒体运营实习生");
        server.verify();
    }
}
