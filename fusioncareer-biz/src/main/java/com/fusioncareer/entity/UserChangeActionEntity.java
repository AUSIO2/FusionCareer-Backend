package com.fusioncareer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fusioncareer.enums.ChangeActionStatus;
import com.fusioncareer.enums.ChangeActionType;
import com.fusioncareer.enums.ChangeActorType;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一次用户可理解的个人空间变更。
 */
@Data
@TableName("fc_user_change_action")
public class UserChangeActionEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private ChangeActorType actorType;
    private String origin;
    private Long epoch;
    private String runId;
    private String requestId;
    private String toolName;
    private ChangeActionType actionType;
    private Long revertsActionId;
    private ChangeActionStatus status;
    private String idempotencyKey;
    private String argsHash;
    private Short schemaVersion;
    private String reason;
    private String errorCode;
    private LocalDateTime confirmedAt;
    private LocalDateTime appliedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
