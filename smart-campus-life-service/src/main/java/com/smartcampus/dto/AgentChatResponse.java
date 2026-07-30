package com.smartcampus.dto;

import java.util.List;

import lombok.Data;

/** 受控业务 Agent 的回复：自然语言说明 + 可验证的业务卡片。 */
@Data
public class AgentChatResponse {
    /** 单次请求追踪 ID，可与 Redis 审计记录对应，用于定位模型或工具调用问题。 */
    private String traceId;
    /** 服务端确认归属当前用户的会话标识，用于短期上下文。 */
    private String conversationId;
    /** 模型或确定性编排生成的自然语言说明，不作为业务写操作依据。 */
    private String answer;
    /** 服务端工具生成的可信业务卡片；可为空但不应由模型文本替代。 */
    private List<AgentCard> cards;
}
