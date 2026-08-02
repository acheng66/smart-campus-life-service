package com.smartcampus.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * Agent SSE 流中的统一事件结构。
 *
 * <p>{@code type} 决定前端如何消费事件：status 展示当前阶段，delta 追加回答文本，
 * cards 渲染服务端校验后的业务卡片，complete/error 结束本轮请求。事件中不包含
 * Prompt、模型密钥、工具原始返回或异常堆栈。</p>
 */
@Data
public class AgentStreamEvent {
    /** 单连接内单调递增的事件序号，同时作为 SSE id。 */
    private long sequence;
    /** connected、status、metadata、delta、cards、complete 或 error。 */
    private String type;
    private String traceId;
    private String conversationId;
    /** 对应持久化工作流状态；非阶段事件可为空。 */
    private String state;
    /** 稳定机器码，前端不应依赖中文文案判断流程。 */
    private String code;
    /** 面向用户的安全阶段说明或错误提示。 */
    private String message;
    /** 回答增量；只在 delta 事件中存在。 */
    private String delta;
    /** 可信业务卡片；只在 cards 事件中存在。 */
    private List<AgentCard> cards;

    public static AgentStreamEvent of(String type, String code, String message) {
        AgentStreamEvent event = new AgentStreamEvent();
        event.setType(type);
        event.setCode(code);
        event.setMessage(message);
        return event;
    }

    public static AgentStreamEvent delta(String traceId, String conversationId, String text) {
        AgentStreamEvent event = of("delta", "ANSWER_DELTA", null);
        event.setTraceId(traceId);
        event.setConversationId(conversationId);
        event.setDelta(text);
        return event;
    }

    public static AgentStreamEvent cards(String traceId, String conversationId, List<AgentCard> cards) {
        AgentStreamEvent event = of("cards", "CARDS_READY", "业务卡片已校验");
        event.setTraceId(traceId);
        event.setConversationId(conversationId);
        event.setCards(cards == null ? new ArrayList<>() : new ArrayList<>(cards));
        return event;
    }
}
