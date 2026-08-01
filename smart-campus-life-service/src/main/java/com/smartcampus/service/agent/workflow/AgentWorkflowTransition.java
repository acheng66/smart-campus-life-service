package com.smartcampus.service.agent.workflow;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** 一次合法状态转换，构成可追溯的工作流时间线。 */
@Data
@NoArgsConstructor
public class AgentWorkflowTransition {
    /** 首条记录没有来源状态，后续记录均有 fromState。 */
    private AgentWorkflowState fromState;
    private AgentWorkflowState toState;
    /** 稳定的机器事件名，例如 INTENT_RESOLVED、MODEL_RETURNED。 */
    private String event;
    /** 不包含 Prompt、密钥和完整工具结果的简短说明。 */
    private String detail;
    private LocalDateTime occurredAt;

    public AgentWorkflowTransition(AgentWorkflowState fromState, AgentWorkflowState toState,
            String event, String detail, LocalDateTime occurredAt) {
        this.fromState = fromState;
        this.toState = toState;
        this.event = event;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }
}
