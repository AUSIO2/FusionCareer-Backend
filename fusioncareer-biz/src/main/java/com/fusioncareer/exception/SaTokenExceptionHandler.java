package com.fusioncareer.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.fusioncareer.common.R;
import com.fusioncareer.common.ResultCode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Sa-Token 鉴权异常处理器
 * <p>
 * 独立于 {@link GlobalExceptionHandler}，专门捕获 Sa-Token 框架抛出的鉴权异常。
 * 通过 {@code @Order(0)} 确保优先级高于全局兜底处理器。
 *
 * @author Xiong Heng
 */
@Slf4j
@Order(0)
@RestControllerAdvice
public class SaTokenExceptionHandler {

    /**
     * 捕获未登录异常 (NotLoginException)
     * 当用户未携带 Token 或 Token 已失效时触发
     */
    @ExceptionHandler(NotLoginException.class)
    public R<Void> handleNotLoginException(NotLoginException e, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        log.warn("鉴权失败 - 未登录: type={}, msg={}", e.getType(), e.getMessage());
        return new R<>(ResultCode.USER_NOT_LOGGED_IN.getCode(), e.getMessage(), null);
    }

    /**
     * 捕获无角色异常 (NotRoleException)
     * 当用户缺少访问所需的角色时触发
     */
    @ExceptionHandler(NotRoleException.class)
    public R<Void> handleNotRoleException(NotRoleException e, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        log.warn("鉴权失败 - 缺少角色: role={}", e.getRole());
        return new R<>(ResultCode.FORBIDDEN.getCode(), "缺少角色: " + e.getRole(), null);
    }

    /**
     * 捕获无权限异常 (NotPermissionException)
     * 当用户缺少访问所需的权限码时触发
     */
    @ExceptionHandler(NotPermissionException.class)
    public R<Void> handleNotPermissionException(NotPermissionException e, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        log.warn("鉴权失败 - 缺少权限: permission={}", e.getPermission());
        return new R<>(ResultCode.FORBIDDEN.getCode(), "缺少权限: " + e.getPermission(), null);
    }
}
