package com.fusioncareer.client;

import com.fusioncareer.client.dto.ResumeParseResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class PythonServiceClientTest {

    @Test
    void sendsResumeAsMultipartAndReadsStructuredResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://algorithm.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        PythonServiceClient client = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(PythonServiceClient.class);

        server.expect(requestTo("http://algorithm.test/api/v1/resume/parse"))
                .andExpect(method(POST))
                .andExpect(header("Content-Type", startsWith("multipart/form-data")))
                .andRespond(withSuccess("""
                        {
                          "code": 200,
                          "message": "success",
                          "data": {"real_name": "张三", "edu_level": "2"}
                        }
                        """, MediaType.APPLICATION_JSON));

        ByteArrayResource file = new ByteArrayResource("resume".getBytes()) {
            @Override
            public String getFilename() {
                return "resume.pdf";
            }
        };
        ResumeParseResponse response = client.parseResume(file);

        assertThat(response.getData().getRealName()).isEqualTo("张三");
        assertThat(response.getData().getEduLevel()).isEqualTo(2);
        server.verify();
    }
}
