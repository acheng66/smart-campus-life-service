package com.smartcampus.service.agent.memory;

import org.springframework.stereotype.Service;

import com.smartcampus.service.agent.AgentConversationMemoryService;
import com.smartcampus.service.agent.AgentLongTermMemoryService;

/** 统一加载“增量摘要 + 最近消息 + 长期偏好”，避免不同 Agent 链路采用不一致的记忆规则。 */
@Service
public class AgentContextAssembler {
    private final AgentConversationMemoryService conversationMemoryService;
    private final AgentLongTermMemoryService longTermMemoryService;

    public AgentContextAssembler(AgentConversationMemoryService conversationMemoryService,
            AgentLongTermMemoryService longTermMemoryService) {
        this.conversationMemoryService = conversationMemoryService;
        this.longTermMemoryService = longTermMemoryService;
    }

    public AgentMemoryContext assemble(Long userId, String conversationId, String excludedTraceId) {
        return new AgentMemoryContext(
                conversationMemoryService.summaryContext(userId, conversationId),
                conversationMemoryService.recentContext(userId, conversationId, excludedTraceId),
                longTermMemoryService.profilePrompt(userId));
    }
}
