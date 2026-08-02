package com.smartcampus.service.agent.workflow;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Redis 中持久化的一次 Agent 工作流快照。
 *
 * <p>它只保存排障和恢复所需的元数据，不保存系统提示词、模型密钥、RAG 原文或工具完整返回值。
 * 用户问题本身已在入口限制为 300 字，并且查询接口还会校验记录归属。</p>
 */
@Data
@NoArgsConstructor
public class AgentWorkflowExecution {
    private String traceId;
    private Long userId;
    private String conversationId;
    private String question;
    private AgentWorkflowState state;
    /** SHOP_RECOMMENDATION、SHOP_VOUCHER_QUERY 等服务端意图。 */
    private String intent;
    /** AI、DETERMINISTIC、FALLBACK 或 FINAL_FALLBACK。 */
    private String mode;
    /** NONE、SHOP、VOUCHER 或 MY_VOUCHER。 */
    private String presentationType;
    private List<String> toolCalls = new ArrayList<>();
    private int ragHitCount;
    private int ragVectorHitCount;
    private int ragKeywordHitCount;
    private List<String> ragDocumentIds = new ArrayList<>();
    private boolean ragReranked;
    private boolean fallback;
    private long durationMs;
    private String failureType;
    private String failureMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private List<AgentWorkflowTransition> transitions = new ArrayList<>();
}
