package com.fusioncareer.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户简历 + 作品集实体
 * <p>
 * 对应表 fc_resume，以 user_id 为主键。
 * 所有内容字段统一以字符串存储。
 *
 * @author Xiong Heng
 */
@Data
@TableName("fc_resume")
public class Resume implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户ID，关联 fc_user.id，同时为本表主键
     */
    @TableId
    private Long userId;

    /**
     * 个人简况（300字以内）
     */
    private String personalIntro;

    /**
     * 基础信息
     */
    private String basicInfo;

    /**
     * 教育背景
     */
    private String education;

    /**
     * 实习经历
     */
    private String internship;

    /**
     * 在校经历
     */
    private String campus;

    /**
     * 荣誉奖励
     */
    private String awards;

    /**
     * 掌握技能
     */
    private String skills;

    /**
     * 作品集
     */
    private String portfolio;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
