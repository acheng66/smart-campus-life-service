package com.smartcampus.service.agent.memory;

import java.time.LocalDateTime;

import lombok.Data;

/** 不可变的增量会话摘要版本；新版本写入成功后自然成为最新有效版本。 */
@Data
public class AgentConversationSummary {
    private String id;
    private String conversationId;
    private String summaryText;
    private LocalDateTime coveredUntil;
    private LocalDateTime createdAt;
}
