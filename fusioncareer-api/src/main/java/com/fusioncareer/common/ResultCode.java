package com.fusioncareer.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一 API 响应状态码枚举
 *
 * @author Xiong Heng
 */
@Getter
@AllArgsConstructor
public enum ResultCode {
    SUCCESS(200, "操作成功"),
    USER_NOT_LOGGED_IN(401, "用户未登录或身份认证失败"),
    FORBIDDEN(403, "没有操作权限"),
    NOT_FOUND(404, "请求的资源不存在"),
    VALIDATE_FAILED(400, "参数检验失败"),
    INTERNAL_SERVER_ERROR(500, "系统内部发生错误，请联系管理员");

    private final int code;
    private final String message;
}
