package com.fusioncareer.common;

import lombok.Getter;

/**
 * 通用业务逻辑异常
 *
 * @author Xiong Heng
 */
@Getter
public class ServiceException extends RuntimeException {
    private final int code;

    public ServiceException(String message) {
        super(message);
        this.code = ResultCode.INTERNAL_SERVER_ERROR.getCode();
    }

    public ServiceException(int code, String message) {
        super(message);
        this.code = code;
    }

    public ServiceException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }
}
