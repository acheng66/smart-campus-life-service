package com.smartcampus.service.agent.workflow;

/**
 * 单次 Agent 请求的持久化工作流状态。
 *
 * <p>状态只允许按照 {@link AgentWorkflowStateMachine} 定义的方向推进。与日志不同，
 * 该状态会在每个关键阶段写入 Redis，因此服务异常退出后仍能知道请求最后停在哪一步。</p>
 */
public enum AgentWorkflowState {
    /** 已创建 traceId、conversationId 和持久化执行记录。 */
    CREATED,
    /** 服务端已识别主要业务意图，并确定本轮允许的卡片类型边界。 */
    INTENT_RESOLVED,
    /** 正在加载短期记忆、长期偏好和 RAG 知识。 */
    CONTEXT_LOADING,
    /** 上下文加载完成，可以交给模型规划。 */
    CONTEXT_READY,
    /** ChatClient 正在规划和调用受控工具。 */
    MODEL_PLANNING,
    /** 模型调用已经返回，实际工具调用轨迹已收集。 */
    TOOLS_EXECUTED,
    /** 无模型或模型异常后，正在执行确定性业务查询。 */
    DETERMINISTIC_RUNNING,
    /** 回答、卡片类型和可信业务数据已完成服务端整理。 */
    RESPONSE_VALIDATED,
    /** 请求正常结束。 */
    COMPLETED,
    /** 模型路径与确定性兜底都失败，或进程中断后被恢复任务判定超时。 */
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
