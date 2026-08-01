package com.smartcampus.service.agent;

/**
 * 单轮 Agent 的主要业务意图。
 *
 * <p>意图由服务端在进入 ChatClient 前确定，作为卡片类型的安全边界；模型仍可在边界内自主规划
 * 查询工具，但不能通过错误工具调用把“查券”响应切换成店铺推荐。</p>
 */
public enum AgentIntent {
    /** 推荐、比较或查找店铺，最终只允许展示店铺卡片。 */
    SHOP_RECOMMENDATION,
    /** 查询某家店当前有哪些券，最终只允许展示可领取券卡片。 */
    SHOP_VOUCHER_QUERY,
    /** 查询当前用户已经领取的券，最终只允许展示我的券卡片。 */
    MY_VOUCHER_QUERY,
    /** 校验某张券的资格、库存或活动状态，默认只返回文字结论。 */
    ELIGIBILITY_CHECK,
    /** 闲聊、规则问答或暂时无法归入以上业务的请求，不允许生成业务卡片。 */
    GENERAL
}
