package com.fusioncareer.exception;

import com.fusioncareer.common.R;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler readHandler = new GlobalExceptionHandler(new MockEnvironment());

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409})
    void mapSpringErrors(int readStatus) {
        HttpStatus readHttpStatus = HttpStatus.valueOf(readStatus);
        ResponseEntity<Object> readResponse = readHandler.handleExceptionInternal(
                new ResponseStatusException(readHttpStatus, "sensitive-detail"),
                null,
                new HttpHeaders(),
                readHttpStatus,
                new ServletWebRequest(new MockHttpServletRequest()));

        assertThat(readResponse.getStatusCode().value()).isEqualTo(readStatus);
        assertThat(readResponse.getBody()).isInstanceOf(R.class);
        R<?> readBody = (R<?>) readResponse.getBody();
        assertThat(readBody.getCode()).isEqualTo(readStatus);
        assertThat(readBody.getMessage()).doesNotContain("系统内部错误", "sensitive-detail");
        assertThat(readBody.getData()).isNull();
    }

    @ParameterizedTest
    @MethodSource("provideErrors")
    void mapServiceErrors(ResultCode readCode) {
        MockHttpServletResponse readResponse = new MockHttpServletResponse();

        R<Void> readBody = readHandler.handleServiceException(ServiceException.of(readCode), readResponse);

        assertThat(readResponse.getStatus()).isEqualTo(readCode.getCode());
        assertThat(readBody.getCode()).isEqualTo(readCode.getCode());
        assertThat(readBody.getMessage()).isEqualTo(readCode.getMessage());
        assertThat(readBody.getData()).isNull();
    }

    @Test
    void preserveBusinessCode() {
        MockHttpServletResponse readResponse = new MockHttpServletResponse();

        R<Void> readBody = readHandler.handleServiceException(
                ServiceException.of(ResumeErrorCode.FILE_NOT_FOUND), readResponse);

        assertThat(readResponse.getStatus()).isEqualTo(200);
        assertThat(readBody.getCode()).isEqualTo(ResumeErrorCode.FILE_NOT_FOUND.getCode());
    }

    @Test
    void hideServerDetails() {
        MockHttpServletResponse readResponse = new MockHttpServletResponse();

        R<Void> readBody = readHandler.handleException(
                new IllegalStateException("sensitive-database-detail"), readResponse);

        assertThat(readResponse.getStatus()).isEqualTo(500);
        assertThat(readBody.getCode()).isEqualTo(500);
        assertThat(readBody.getMessage()).isEqualTo(ResultCode.INTERNAL_SERVER_ERROR.getMessage());
        assertThat(readBody.getMessage()).doesNotContain("sensitive-database-detail", "IllegalStateException");
        assertThat(readBody.getData()).isNull();
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> provideErrors() {
        return Stream.of(
                arguments(ResultCode.VALIDATE_FAILED),
                arguments(ResultCode.USER_NOT_LOGGED_IN),
                arguments(ResultCode.FORBIDDEN),
                arguments(ResultCode.NOT_FOUND),
                arguments(ResultCode.CONFLICT),
                arguments(ResultCode.INTERNAL_SERVER_ERROR));
    }
}
