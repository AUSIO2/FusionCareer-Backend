package com.fusioncareer.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalDate;

@Data
@TableName("fc_ai_session")
public class AiSessionEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId
    private Long userId;
    private Long epoch;
    private String summary;
    private Long summaryThroughMessageId;
    private String activeRunId;
    private LocalDateTime leaseUntil;
    private LocalDateTime lastMessageAt;
    private LocalDate usageDate;
    private Integer dailyUsageCount;
    private Integer irrelevantStrikeCount;
    private LocalDateTime aiBannedUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
