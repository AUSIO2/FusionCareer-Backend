package com.fusioncareer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fusioncareer.enums.ChangeOperation;
import com.fusioncareer.enums.ChangeResourceType;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一次个人空间变更中的单个资源快照。
 */
@Data
@TableName("fc_user_change_item")
public class UserChangeItemEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long actionId;
    private Short itemOrder;
    private ChangeResourceType resourceType;
    private String resourceKey;
    private ChangeOperation operation;
    private String changedFields;
    private Long expectedVersion;
    private Long appliedVersion;
    private byte[] beforeCiphertext;
    private byte[] afterCiphertext;
    private Short keyVersion;
    private LocalDateTime createdAt;
}
