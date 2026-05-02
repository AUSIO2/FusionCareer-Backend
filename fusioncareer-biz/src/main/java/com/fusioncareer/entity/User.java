package com.fusioncareer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fusioncareer.enums.UserRole;
import com.fusioncareer.enums.UserStatus;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户账号实体
 * <p>
 * 对应表 fc_user，由 CAS/OAuth2 对接后写入，Sa-Token 以此为认证主体。
 *
 * @author Xiong Heng
 */
@Data
@TableName("fc_user")
public class User implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户ID（雪花算法） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录名 */
    private String username;

    /** 学工号 */
    private String studentId;

    /** 密码（CAS 对接时为空） */
    private String password;

    /** 角色 */
    private UserRole role;

    /** 状态 */
    private UserStatus status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
