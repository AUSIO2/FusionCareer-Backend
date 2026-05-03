package com.fusioncareer.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fusioncareer.enums.EduLevel;
import com.fusioncareer.enums.Gender;
import com.fusioncareer.enums.Mindset;
import com.fusioncareer.enums.PoliticalStatus;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户资料实体
 *
 * @author Xiong Heng
 */
@Data
@TableName("fc_user_profile")
public class UserProfileEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId
    private Long userId;
    private String realName;
    private Gender gender;
    private LocalDate birthDate;
    private PoliticalStatus politicalStatus;
    private String phone;
    private String email;
    private String wechat;
    private String hometown;
    private String grade;
    private String major;
    private EduLevel eduLevel;
    private String supervisor;
    private String intentionOrder;
    private String intentionCity;
    private String intentionDream;
    private Mindset mindset;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
