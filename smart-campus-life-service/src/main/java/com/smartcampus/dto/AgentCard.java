package com.smartcampus.dto;

import lombok.Data;

/**
 * Agent 返回给前端的可信业务卡片。
 *
 * <p>卡片由 Java 工具根据真实查询结果生成，而不是解析模型文本得到；前端据此跳转商户或展示领取按钮，
 * 不直接暴露 MyBatis 实体。</p>
 */
@Data
public class AgentCard {
    /** 卡片类型：shop、voucher 或 my-voucher。 */
    private String type;
    /** 对用户展示的商户名或优惠券标题。 */
    private String title;
    /** 已格式化的评分、使用门槛、状态等简短说明。 */
    private String description;
    /** 商户详情页使用的真实商户 ID；券卡也携带其所属商户。 */
    private Long shopId;
    /** 优惠券真实 ID；商户卡为空。 */
    private Long voucherId;
    /** 可写卡片的按钮文案，例如“确认领取”；只读卡片为空。 */
    private String actionLabel;
    /** 服务端签发的一次性确认 Token，绝不等同于 voucherId。 */
    private String actionToken;
}
