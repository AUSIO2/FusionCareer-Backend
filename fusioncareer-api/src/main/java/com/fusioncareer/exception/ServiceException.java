package com.fusioncareer.exception;

import lombok.Getter;

/**
 * 通用业务逻辑异常
 * <p>
 * 请统一使用静态工厂方法构造，不要直接 new：
 * <ul>
 *   <li>{@code ServiceException.of(SomeErrorCode.XXX)} — 标准用法</li>
 *   <li>{@code ServiceException.of(SomeErrorCode.XXX, "动态描述")} — 需要覆盖消息时</li>
 * </ul>
 *
 * @author Xiong Heng
 */
@Getter
public class ServiceException extends RuntimeException {

    private final int code;

    /** 仅供工厂方法调用，外部请使用 {@link #of} */
    private ServiceException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 从实现了 {@link IErrorCode} 的枚举快速构造异常
     */
    public static ServiceException of(IErrorCode errorCode) {
        return new ServiceException(errorCode.getCode(), errorCode.getMessage());
    }

    /**
     * 从实现了 {@link IErrorCode} 的枚举构造异常，并覆盖错误描述（用于需要动态消息的场景）
     */
    public static ServiceException of(IErrorCode errorCode, String message) {
        return new ServiceException(errorCode.getCode(), message);
    }
}
