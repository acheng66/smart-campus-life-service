package com.smartcampus.service.agent.workflow;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcampus.dto.AgentExecutionTrace;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * Agent 工作流的状态推进与 Redis 持久化服务。
 *
 * <p>每个 traceId 保存一份完整快照，并为用户维护最近执行索引。写入失败只会降低可观测性，
 * 不会覆盖已经得到的真实业务回答；非法状态转换则直接抛错，避免主链路悄悄跳过必要阶段。</p>
 */
@Slf4j
@Service
public class AgentWorkflowService {
    private static final String EXECUTION_KEY_PREFIX = "agent:workflow:execution:";
    private static final String USER_INDEX_KEY_PREFIX = "agent:workflow:user:";
    private static final String ACTIVE_KEY = "agent:workflow:active";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final long ttlDays;
    private final int recentLimit;
    private final long staleMinutes;

    public AgentWorkflowService(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper,
            @Value("${agent.workflow.ttl-days:7}") long ttlDays,
            @Value("${agent.workflow.recent-limit:50}") int recentLimit,
            @Value("${agent.workflow.stale-minutes:30}") long staleMinutes) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.ttlDays = Math.max(ttlDays, 1L);
        this.recentLimit = Math.max(1, Math.min(recentLimit, 200));
        this.staleMinutes = Math.max(staleMinutes, 5L);
    }

    /** 创建工作流并立即持久化，使后续任何阶段失败时都有可查询的起点。 */
    public AgentWorkflowExecution start(String traceId, Long userId, String conversationId, String question) {
        LocalDateTime now = LocalDateTime.now();
        AgentWorkflowExecution execution = new AgentWorkflowExecution();
        execution.setTraceId(traceId);
        execution.setUserId(userId);
        execution.setConversationId(conversationId);
        execution.setQuestion(question);
        execution.setState(AgentWorkflowState.CREATED);
        execution.setCreatedAt(now);
        execution.setUpdatedAt(now);
        execution.getTransitions().add(new AgentWorkflowTransition(null, AgentWorkflowState.CREATED,
                "WORKFLOW_CREATED", "请求已进入 Agent 主链路", now));
        persist(execution, true);
        return execution;
    }

    /**
     * 推进到下一个阶段并写入一条时间线记录。
     * 状态规则由 {@link AgentWorkflowStateMachine} 统一校验。
     */
    public void transition(AgentWorkflowExecution execution, AgentWorkflowState target,
            String event, String detail) {
        if (execution == null) {
            return;
        }
        synchronized (execution) {
            AgentWorkflowState source = execution.getState();
            AgentWorkflowStateMachine.requireTransition(source, target);
            LocalDateTime now = LocalDateTime.now();
            execution.setState(target);
            execution.setUpdatedAt(now);
            execution.getTransitions().add(new AgentWorkflowTransition(source, target,
                    safeText(event, 60), safeText(detail, 200), now));
            persist(execution, false);
        }
    }

    /** 将现有评测执行轨迹同步到持久化工作流，不保存模型 Prompt 和完整业务数据。 */
    public void captureTrace(AgentWorkflowExecution execution, AgentExecutionTrace trace) {
        if (execution == null || trace == null) {
            return;
        }
        synchronized (execution) {
            execution.setIntent(trace.getIntent());
            execution.setMode(trace.getMode());
            execution.setPresentationType(trace.getPresentationType());
            execution.setToolCalls(trace.getToolCalls() == null
                    ? new ArrayList<>() : new ArrayList<>(trace.getToolCalls()));
            execution.setRagHitCount(trace.getRagHitCount());
            execution.setFallback(trace.isFallback());
            execution.setDurationMs(trace.getDurationMs());
            execution.setUpdatedAt(LocalDateTime.now());
            persist(execution, false);
        }
    }

    /** 完成终态；只有已经通过响应校验的工作流才能进入 COMPLETED。 */
    public void complete(AgentWorkflowExecution execution, AgentExecutionTrace trace) {
        captureTrace(execution, trace);
        transition(execution, AgentWorkflowState.COMPLETED, "WORKFLOW_COMPLETED", "回答已返回给当前用户");
        execution.setCompletedAt(execution.getUpdatedAt());
        persist(execution, false);
    }

    /** 从任意非终态进入 FAILED，并保存经过截断的异常类型和消息。 */
    public void fail(AgentWorkflowExecution execution, Throwable error, String event) {
        if (execution == null) {
            return;
        }
        synchronized (execution) {
            if (execution.getState() == null || execution.getState().isTerminal()) {
                return;
            }
            AgentWorkflowState source = execution.getState();
            LocalDateTime now = LocalDateTime.now();
            execution.setState(AgentWorkflowState.FAILED);
            execution.setFailureType(error == null ? "UNKNOWN" : error.getClass().getSimpleName());
            execution.setFailureMessage(safeText(error == null ? "未知错误" : error.getMessage(), 200));
            execution.setUpdatedAt(now);
            execution.setCompletedAt(now);
            execution.getTransitions().add(new AgentWorkflowTransition(source, AgentWorkflowState.FAILED,
                    safeText(event, 60), execution.getFailureMessage(), now));
            persist(execution, false);
        }
    }

    /** 按 traceId 查询；归属不匹配时与不存在一样返回 null，避免枚举他人执行记录。 */
    public AgentWorkflowExecution findOwned(String traceId, Long userId) {
        if (StrUtil.isBlank(traceId) || userId == null) {
            return null;
        }
        AgentWorkflowExecution execution = read(traceId);
        return execution != null && userId.equals(execution.getUserId()) ? execution : null;
    }

    /** 查询当前用户最近的工作流快照，Redis 中已自然过期的 traceId 会被忽略。 */
    public List<AgentWorkflowExecution> recentOwned(Long userId, int requestedLimit) {
        if (userId == null) {
            return Collections.emptyList();
        }
        int limit = Math.max(1, Math.min(requestedLimit, recentLimit));
        List<String> traceIds = stringRedisTemplate.opsForList()
                .range(USER_INDEX_KEY_PREFIX + userId, 0, limit - 1L);
        if (traceIds == null || traceIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<AgentWorkflowExecution> result = new ArrayList<>();
        for (String traceId : traceIds) {
            AgentWorkflowExecution execution = findOwned(traceId, userId);
            if (execution != null) {
                result.add(execution);
            }
        }
        return result;
    }

    /**
     * 服务重启后清理长期悬挂的执行。
     *
     * <p>同步 HTTP 调用无法从模型调用中点安全续跑，因此恢复策略不是盲目重放工具，而是把超过阈值的
     * 非终态记录明确标记为 FAILED，保留故障位置并允许客户端重新发起一次新请求。</p>
     */
    @Scheduled(fixedDelayString = "${agent.workflow.recovery-interval-ms:60000}")
    public void recoverStaleExecutions() {
        Set<String> activeTraceIds = stringRedisTemplate.opsForSet().members(ACTIVE_KEY);
        if (activeTraceIds == null || activeTraceIds.isEmpty()) {
            return;
        }
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(staleMinutes);
        for (String traceId : activeTraceIds) {
            AgentWorkflowExecution execution = read(traceId);
            if (execution == null || execution.getState() == null || execution.getState().isTerminal()) {
                stringRedisTemplate.opsForSet().remove(ACTIVE_KEY, traceId);
            } else if (execution.getUpdatedAt() != null && execution.getUpdatedAt().isBefore(deadline)) {
                fail(execution, new IllegalStateException("工作流超过 " + staleMinutes + " 分钟未推进"),
                        "STALE_WORKFLOW_RECOVERED");
            }
        }
    }

    private void persist(AgentWorkflowExecution execution, boolean createIndex) {
        try {
            String traceId = execution.getTraceId();
            stringRedisTemplate.opsForValue().set(EXECUTION_KEY_PREFIX + traceId,
                    objectMapper.writeValueAsString(execution), ttlDays, TimeUnit.DAYS);
            if (createIndex) {
                String indexKey = USER_INDEX_KEY_PREFIX + execution.getUserId();
                stringRedisTemplate.opsForList().leftPush(indexKey, traceId);
                stringRedisTemplate.opsForList().trim(indexKey, 0, recentLimit - 1L);
                stringRedisTemplate.expire(indexKey, ttlDays, TimeUnit.DAYS);
                stringRedisTemplate.opsForSet().add(ACTIVE_KEY, traceId);
            }
            if (execution.getState() != null && execution.getState().isTerminal()) {
                stringRedisTemplate.opsForSet().remove(ACTIVE_KEY, traceId);
            }
        } catch (Exception e) {
            log.warn("持久化 Agent 工作流失败, traceId={}", execution.getTraceId(), e);
        }
    }

    private AgentWorkflowExecution read(String traceId) {
        try {
            String raw = stringRedisTemplate.opsForValue().get(EXECUTION_KEY_PREFIX + traceId);
            return raw == null ? null : objectMapper.readValue(raw, AgentWorkflowExecution.class);
        } catch (JsonProcessingException e) {
            log.warn("Agent 工作流 JSON 无法解析, traceId={}", traceId, e);
            return null;
        } catch (Exception e) {
            log.warn("读取 Agent 工作流失败, traceId={}", traceId, e);
            return null;
        }
    }

    private String safeText(String value, int maxLength) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        return StrUtil.subWithLength(normalized, 0, maxLength);
    }
}
