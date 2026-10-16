package com.fusioncareer.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户白名单长期记忆。
 */
@Data
@TableName("fc_user_memory")
public class UserMemoryEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId
    private Long userId;
    private String memoryJson;
    @Version
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
