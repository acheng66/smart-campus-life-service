package com.smartcampus.dto;

import lombok.Data;

/**
 * Redis 中短时保存的待确认动作。
 *
 * <p>该对象不经由前端传入；它由查询工具签发、确认接口读取，防止模型或前端伪造用户、券和动作类型。</p>
 */
@Data
public class PendingAgentAction {
    /** 签发 Token 时的登录用户，确认时必须完全一致。 */
    private Long userId;
    /** 由工具真实查询到的优惠券 ID。 */
    private Long voucherId;
    /** RECEIVE_NORMAL 或 SECKILL。 */
    private String actionType;
}
