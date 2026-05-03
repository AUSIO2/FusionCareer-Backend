package com.fusioncareer.exception;

import com.fusioncareer.exception.IErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 简历文件模块业务错误码
 * <p>
 * 错误码段：4100xx（4 = 客户端错误，1 = 文件模块）
 *
 * @author Xiong Heng
 */
@Getter
@AllArgsConstructor
public enum ResumeErrorCode implements IErrorCode {

    // ── 文件格式校验 ──────────────────────────────────────────────────────────

    /** 扩展名不在允许列表内 */
    UNSUPPORTED_FILE_FORMAT(41001, "不支持的文件格式，仅支持 PDF、JPG、PNG"),

    /** Content-Type（MIME）与允许类型不符 */
    INVALID_MIME_TYPE(41002, "文件类型不合法，仅支持 PDF、JPG、PNG"),

    /** 单文件超过 20MB 硬限制 */
    FILE_TOO_LARGE(41003, "单文件不能超过 20MB"),

    /** 文件名缺少扩展名或为空 */
    INVALID_FILE_NAME(41004, "文件名格式不正确，请确认文件包含扩展名"),

    /** 上传内容为空 */
    EMPTY_FILE(41005, "上传文件不能为空"),

    // ── 存储配额 ──────────────────────────────────────────────────────────────

    /** 用户个人配额（30MB）已满 */
    QUOTA_EXCEEDED(41010, "存储空间不足，请先删除旧文件后再上传"),

    // ── 文件操作权限 ──────────────────────────────────────────────────────────

    /** 尝试删除不存在的文件 */
    FILE_NOT_FOUND(41020, "文件不存在"),

    /** 尝试删除他人的文件 */
    DELETE_FORBIDDEN(41021, "无权删除他人文件"),

    // ── 系统 I/O ─────────────────────────────────────────────────────────────

    /** 写入磁盘失败 */
    SAVE_FAILED(41030, "文件保存失败，请稍后重试");

    private final int code;
    private final String message;
}
