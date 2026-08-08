package com.smartcampus.service.agent.memory;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 一次模型调用所需的三层记忆，由统一组装器生成，业务服务不再分别读取各存储。 */
@Getter
@AllArgsConstructor
public class AgentMemoryContext {
    private final String conversationSummary;
    private final String recentConversation;
    private final String userPreferences;

    /** Query Rewrite 需要摘要和最近对话，但不需要长期画像。 */
    public String conversationPrompt() {
        return "历史摘要：\n" + conversationSummary + "\n最近对话：\n" + recentConversation;
    }
}
