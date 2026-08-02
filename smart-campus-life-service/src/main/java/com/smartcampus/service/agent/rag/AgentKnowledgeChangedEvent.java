package com.smartcampus.service.agent.rag;

/**
 * MySQL 业务事务提交后发布的知识增量更新事件。
 * kind 仅支持 shop、voucher；deleted=true 时删除对应的活动版本文档。
 */
public record AgentKnowledgeChangedEvent(String kind, Long businessId, boolean deleted) {
}
