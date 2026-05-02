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
 * <p>
 * 对应表 fc_user_profile，以 user_id 为主键与 fc_user 一对一关联。
 * 包含基础信息与个人意向。
 *
 * @author Xiong Heng
 */
@Data
@TableName("fc_user_profile")
public class UserProfile implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户ID，关联 fc_user.id，同时为本表主键 */
    @TableId
    private Long userId;

    // ==================== 基础信息 ====================

    /** 姓名 */
    private String realName;

    /** 性别 */
    private Gender gender;

    /** 出生年月 */
    private LocalDate birthDate;

    /** 政治面貌 */
    private PoliticalStatus politicalStatus;

    /** 联系电话 */
    private String phone;

    /** 联系邮箱 */
    private String email;

    /** 微信号 */
    private String wechat;

    /** 生源地（省市） */
    private String hometown;

    /** 年级，如：2022级 */
    private String grade;

    /** 专业方向 */
    private String major;

    /** 学历层次 */
    private EduLevel eduLevel;

    /** 导师姓名 */
    private String supervisor;

    // ==================== 个人意向 ====================

    /** 毕业去向总体意向排序，逗号分隔，如：学术教职,企业公司 */
    private String intentionOrder;

    /** 意向地区排序，JSON 数组，如：["上海","北京"] */
    private String intentionCity;

    /** 筹备方向 / "梦中情岗"描述 */
    private String intentionDream;

    /** 目前心态 */
    private Mindset mindset;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
