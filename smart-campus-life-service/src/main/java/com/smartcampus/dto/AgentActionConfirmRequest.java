package com.smartcampus.dto;

import lombok.Data;

/**
 * 用户确认 Agent 建议动作的请求体。
 *
 * <p>只允许携带 {@code actionToken}；用户、券和动作类型均从 Redis 中的服务端记录读取，
 * 不能信任客户端提交的业务参数。</p>
 */
@Data
public class AgentActionConfirmRequest {
    /** 5 分钟内有效、绑定用户和优惠券的一次性确认凭证。 */
    private String actionToken;
}
