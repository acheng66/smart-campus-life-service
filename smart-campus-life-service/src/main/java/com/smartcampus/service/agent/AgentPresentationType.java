package com.smartcampus.service.agent;

/** 本轮最终交给前端的可信卡片类型；一次请求最多确定一种。 */
public enum AgentPresentationType {
    NONE,
    SHOP,
    VOUCHER,
    MY_VOUCHER
}
