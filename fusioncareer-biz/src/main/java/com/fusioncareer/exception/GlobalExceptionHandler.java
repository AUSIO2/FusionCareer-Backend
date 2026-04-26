package com.fusioncareer.exception;

import com.fusioncareer.common.R;
import com.fusioncareer.common.ResultCode;
import com.fusioncareer.common.ServiceException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.text.MessageFormat;

/**
 * 全局异常处理 (进阶规范版)
 *
 * @author Xiong Heng
 */
@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private final Environment environment;

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            @NonNull Exception e, @Nullable Object body, @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        String errorMessage = e.getMessage();
        if (body instanceof ProblemDetail problemDetail) {
            errorMessage = MessageFormat.format("{0}({1})", problemDetail.getTitle(), problemDetail.getDetail());
        }
        log.error("系统内部错误(Spring), Status={}, Msg={}", status.value(), errorMessage, e);
        R<Void> customBody = new R<>(status.value(), "系统内部错误: " + errorMessage, null);
        return new ResponseEntity<>(customBody, headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            @NonNull MethodArgumentNotValidException ex, @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        String errorMsg = "参数错误";
        if (ex.getBindingResult().hasErrors() && !ex.getBindingResult().getAllErrors().isEmpty()) {
            errorMsg = ex.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        }
        log.warn("请求参数错误: {}", errorMsg);
        R<Void> customBody = R.validateFailed("参数错误: " + errorMsg);
        return new ResponseEntity<>(customBody, headers, status);
    }

    /**
     * 捕获业务异常 (ServiceException)
     */
    @ExceptionHandler(ServiceException.class)
    public R<Void> handleServiceException(ServiceException e, HttpServletResponse response) {
        response.setStatus(e.getCode() == HttpServletResponse.SC_INTERNAL_SERVER_ERROR ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : HttpServletResponse.SC_OK);
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return new R<>(e.getCode(), e.getMessage(), null);
    }

    /**
     * 兜底异常
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e, HttpServletResponse response) {
        log.error("系统内部错误", e);

        // 原始异常
        int httpStatus = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        String errorMessage = String.format("System Error (%s): %s", e.getClass().getSimpleName(), e.getMessage());
        response.setStatus(httpStatus);

        // 判断当前是否处于 'dev' 或 'test' 环境
        boolean isDev = environment.acceptsProfiles(Profiles.of("dev", "test"));
        if (isDev) {
            return new R<>(ResultCode.INTERNAL_SERVER_ERROR.getCode(), errorMessage, null);
        } else {
            return R.failed(ResultCode.INTERNAL_SERVER_ERROR);
        }
    }
}

