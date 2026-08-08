package com.smartcampus.service.agent.memory;

import java.time.LocalDateTime;
import java.util.List;

/** Agent PostgreSQL 持久化边界；上层记忆服务在该 Bean 不存在时自动退回 Redis。 */
public interface AgentMemoryRepository {
    void touchConversation(Long userId, String conversationId, String firstMessage);

    void appendMessage(Long userId, String conversationId, String traceId, String role, String content);

    List<AgentStoredMessage> recentMessages(Long userId, String conversationId, int limit, String excludedTraceId);

    AgentConversationSummary latestSummary(Long userId, String conversationId);

    List<AgentStoredMessage> messagesAfter(Long userId, String conversationId, LocalDateTime after, int limit);

    void saveSummary(Long userId, String conversationId, String summaryText, LocalDateTime coveredUntil);

    List<AgentUserMemory> activeMemories(Long userId);

    void acceptMemory(Long userId, String conversationId, String sourceMessage, String category,
            String memoryKey, String value, String scope, LocalDateTime expiresAt);

    void deleteMemories(Long userId, String category);
}
