package com.smartcampus.service.agent;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.smartcampus.dto.PendingAgentAction;

import cn.hutool.json.JSONUtil;

/**
 * Agent 写操作的短期确认凭证服务。
 *
 * <p>本服务只签发、读取和消费 Redis Token，不执行领券。Token 内记录用户、券和动作类型，
 * 将“模型给出建议”与“用户确认后执行真实业务”隔离开。</p>
 */
@Service
public class AgentActionTokenService {
    private static final String KEY_PREFIX = "agent:action:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 为已通过查询展示的可领券生成确认凭证。
     *
     * @param userId 当前登录用户，确认时必须匹配
     * @param voucherId 工具查到的真实优惠券 ID
     * @param actionType RECEIVE_NORMAL 或 SECKILL
     * @return 仅前端确认接口可使用的随机 Token，不是业务 ID
     */
    public String issue(Long userId, Long voucherId, String actionType) {
        String token = UUID.randomUUID().toString().replace("-", "");
        PendingAgentAction action = new PendingAgentAction();
        action.setUserId(userId);
        action.setVoucherId(voucherId);
        action.setActionType(actionType);
        stringRedisTemplate.opsForValue().set(KEY_PREFIX + token, JSONUtil.toJsonStr(action), 5, TimeUnit.MINUTES);
        return token;
    }

    /** 读取未过期 Token 对应的服务端动作；不存在说明已过期或已经确认。 */
    public PendingAgentAction get(String token) {
        String raw = stringRedisTemplate.opsForValue().get(KEY_PREFIX + token);
        return raw == null ? null : JSONUtil.toBean(raw, PendingAgentAction.class);
    }

    /**
     * 消费 Token，确保用户不能反复确认同一份 Agent 建议。
     * 真正的库存和一人一券校验仍由后续 VoucherOrderService 执行。
     */
    public void consume(String token) {
        stringRedisTemplate.delete(KEY_PREFIX + token);
    }
}
