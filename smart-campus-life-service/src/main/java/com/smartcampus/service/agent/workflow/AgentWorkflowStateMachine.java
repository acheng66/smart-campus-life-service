package com.smartcampus.service.agent.workflow;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Agent 显式状态机。
 *
 * <p>集中维护允许的转换关系，防止业务代码跳过意图识别、上下文准备或响应校验，
 * 也防止一个已经完成的请求被再次改写。失败可以从任意非终态进入。</p>
 */
public final class AgentWorkflowStateMachine {
    private static final Map<AgentWorkflowState, Set<AgentWorkflowState>> ALLOWED =
            new EnumMap<>(AgentWorkflowState.class);

    static {
        allow(AgentWorkflowState.CREATED,
                AgentWorkflowState.INTENT_RESOLVED, AgentWorkflowState.DETERMINISTIC_RUNNING);
        allow(AgentWorkflowState.INTENT_RESOLVED,
                AgentWorkflowState.CONTEXT_LOADING, AgentWorkflowState.DETERMINISTIC_RUNNING);
        allow(AgentWorkflowState.CONTEXT_LOADING,
                AgentWorkflowState.CONTEXT_READY, AgentWorkflowState.DETERMINISTIC_RUNNING);
        allow(AgentWorkflowState.CONTEXT_READY,
                AgentWorkflowState.MODEL_PLANNING, AgentWorkflowState.DETERMINISTIC_RUNNING);
        allow(AgentWorkflowState.MODEL_PLANNING,
                AgentWorkflowState.TOOLS_EXECUTED, AgentWorkflowState.DETERMINISTIC_RUNNING);
        allow(AgentWorkflowState.TOOLS_EXECUTED,
                AgentWorkflowState.RESPONSE_VALIDATED, AgentWorkflowState.DETERMINISTIC_RUNNING);
        allow(AgentWorkflowState.DETERMINISTIC_RUNNING, AgentWorkflowState.RESPONSE_VALIDATED);
        allow(AgentWorkflowState.RESPONSE_VALIDATED, AgentWorkflowState.COMPLETED);
    }

    private AgentWorkflowStateMachine() {
    }

    public static boolean canTransition(AgentWorkflowState from, AgentWorkflowState to) {
        if (from == null || to == null || from.isTerminal()) {
            return false;
        }
        if (to == AgentWorkflowState.FAILED) {
            return true;
        }
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(AgentWorkflowState.class)).contains(to);
    }

    public static void requireTransition(AgentWorkflowState from, AgentWorkflowState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("非法 Agent 工作流状态转换: " + from + " -> " + to);
        }
    }

    private static void allow(AgentWorkflowState from, AgentWorkflowState... targets) {
        EnumSet<AgentWorkflowState> states = EnumSet.noneOf(AgentWorkflowState.class);
        for (AgentWorkflowState target : targets) {
            states.add(target);
        }
        ALLOWED.put(from, states);
    }
}
