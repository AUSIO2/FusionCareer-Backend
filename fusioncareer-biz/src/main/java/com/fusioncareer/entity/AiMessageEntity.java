package com.fusioncareer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fusioncareer.enums.AiMessageRole;
import com.fusioncareer.enums.AiMessageStatus;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("fc_ai_message")
public class AiMessageEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long epoch;
    private String runId;
    private String requestId;
    private AiMessageRole role;
    private AiMessageStatus status;
    private String content;
    private String attachmentIds;
    private String model;
    private String finishReason;
    private Integer promptTokens;
    private Integer completionTokens;
    private String errorCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
