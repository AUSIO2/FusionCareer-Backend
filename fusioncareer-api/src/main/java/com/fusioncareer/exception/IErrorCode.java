package com.fusioncareer.exception;

/**
 * 业务错误码规范接口
 * <p>
 * 所有业务模块的错误码枚举均应实现此接口，以便统一构造 {@link com.fusioncareer.common.ServiceException}。
 * <p>
 * 用法示例：
 * <pre>
 * public enum ResumeErrorCode implements IErrorCode {
 *     FILE_TOO_LARGE(41003, "单文件不能超过 20MB");
 *     ...
 * }
 *
 * throw ServiceException.of(ResumeErrorCode.FILE_TOO_LARGE);
 * </pre>
 *
 * @author Xiong Heng
 */
public interface IErrorCode {

    /**
     * 错误码（HTTP 状态码或自定义业务码）
     */
    int getCode();

    /**
     * 面向用户的错误描述
     */
    String getMessage();
}
