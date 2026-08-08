package com.smartcampus.service.agent.memory;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import cn.hutool.core.util.StrUtil;

/**
 * 使用 RAG PostgreSQL 数据源持久化 Agent 会话、摘要和长期记忆。
 *
 * <p>消息采用 append-only 写入；摘要采用不可变版本；长期偏好使用唯一业务键覆盖旧值。
 * 所有写入都使用参数化 SQL，schema 名则在启动时进行白名单校验。</p>
 */
@Repository
@ConditionalOnBean(name = "agentRagJdbcTemplate")
@ConditionalOnProperty(prefix = "agent.memory", name = "persistence-enabled", havingValue = "true",
        matchIfMissing = true)
public class JdbcAgentMemoryRepository implements AgentMemoryRepository {
    private final JdbcTemplate jdbcTemplate;
    private final String schema;
    private final boolean initializeSchema;

    public JdbcAgentMemoryRepository(
            @Qualifier("agentRagJdbcTemplate") JdbcTemplate jdbcTemplate,
            @Value("${agent.memory.schema-name:${agent.rag.schema-name:public}}") String schema,
            @Value("${agent.memory.initialize-schema:true}") boolean initializeSchema) {
        if (schema == null || !schema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("agent.memory.schema-name 只能包含字母、数字和下划线，且不能以数字开头");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.schema = schema;
        this.initializeSchema = initializeSchema;
    }

    /** 本地开发自动建表；生产可由 DBA 执行同等 DDL 后关闭该开关。 */
    @PostConstruct
    public void initialize() {
        if (!initializeSchema) {
            return;
        }
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + table("agent_conversation") + " ("
                + "conversation_id VARCHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL, title VARCHAR(160), "
                + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_agent_conversation_user_updated ON "
                + table("agent_conversation") + " (user_id, updated_at DESC)");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + table("agent_message") + " ("
                + "message_id VARCHAR(32) PRIMARY KEY, conversation_id VARCHAR(64) NOT NULL, user_id BIGINT NOT NULL, "
                + "trace_id VARCHAR(32) NOT NULL, role VARCHAR(16) NOT NULL, content TEXT NOT NULL, "
                + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "CONSTRAINT uk_agent_message_trace_role UNIQUE (conversation_id, trace_id, role))");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_agent_message_conversation_time ON "
                + table("agent_message") + " (conversation_id, created_at DESC)");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + table("agent_conversation_summary") + " ("
                + "summary_id VARCHAR(32) PRIMARY KEY, conversation_id VARCHAR(64) NOT NULL, user_id BIGINT NOT NULL, "
                + "summary_text TEXT NOT NULL, covered_until TIMESTAMP NOT NULL, "
                + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_agent_summary_conversation_time ON "
                + table("agent_conversation_summary") + " (conversation_id, created_at DESC)");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + table("agent_memory_candidate") + " ("
                + "candidate_id VARCHAR(32) PRIMARY KEY, user_id BIGINT NOT NULL, conversation_id VARCHAR(64), "
                + "category VARCHAR(40) NOT NULL, memory_key VARCHAR(80) NOT NULL, memory_value VARCHAR(240) NOT NULL, "
                + "source_excerpt VARCHAR(500), decision VARCHAR(16) NOT NULL, "
                + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + table("agent_user_memory") + " ("
                + "memory_id VARCHAR(32) PRIMARY KEY, user_id BIGINT NOT NULL, category VARCHAR(40) NOT NULL, "
                + "memory_key VARCHAR(80) NOT NULL, memory_value VARCHAR(240) NOT NULL, scope VARCHAR(16) NOT NULL, "
                + "status VARCHAR(16) NOT NULL, expires_at TIMESTAMP NULL, "
                + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "CONSTRAINT uk_agent_user_memory UNIQUE (user_id, category, memory_key, scope))");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_agent_user_memory_active ON "
                + table("agent_user_memory") + " (user_id, status, expires_at)");
    }

    @Override
    public void touchConversation(Long userId, String conversationId, String firstMessage) {
        String title = StrUtil.subWithLength(StrUtil.blankToDefault(firstMessage, "新对话"), 0, 160);
        jdbcTemplate.update("INSERT INTO " + table("agent_conversation")
                + " (conversation_id, user_id, title) VALUES (?, ?, ?) "
                + "ON CONFLICT (conversation_id) DO UPDATE SET updated_at=CURRENT_TIMESTAMP",
                conversationId, userId, title);
    }

    @Override
    public void appendMessage(Long userId, String conversationId, String traceId, String role, String content) {
        touchConversation(userId, conversationId, content);
        jdbcTemplate.update("INSERT INTO " + table("agent_message")
                + " (message_id, conversation_id, user_id, trace_id, role, content) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (conversation_id, trace_id, role) DO NOTHING",
                id(), conversationId, userId, traceId, role,
                StrUtil.subWithLength(StrUtil.blankToDefault(content, ""), 0, 4000));
    }

    @Override
    public List<AgentStoredMessage> recentMessages(Long userId, String conversationId, int limit,
            String excludedTraceId) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        String exclusion = StrUtil.isBlank(excludedTraceId) ? "" : " AND trace_id <> ?";
        String sql = "SELECT message_id, trace_id, role, content, created_at FROM " + table("agent_message")
                + " WHERE user_id=? AND conversation_id=?" + exclusion
                + " ORDER BY created_at DESC, message_id DESC LIMIT ?";
        Object[] args = StrUtil.isBlank(excludedTraceId)
                ? new Object[] {userId, conversationId, limit}
                : new Object[] {userId, conversationId, excludedTraceId, limit};
        List<AgentStoredMessage> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new AgentStoredMessage(
                rs.getString("message_id"), rs.getString("trace_id"), rs.getString("role"),
                rs.getString("content"), rs.getTimestamp("created_at").toLocalDateTime()), args);
        Collections.reverse(rows);
        return rows;
    }

    @Override
    public AgentConversationSummary latestSummary(Long userId, String conversationId) {
        List<AgentConversationSummary> rows = jdbcTemplate.query(
                "SELECT summary_id, conversation_id, summary_text, covered_until, created_at FROM "
                        + table("agent_conversation_summary")
                        + " WHERE user_id=? AND conversation_id=? ORDER BY created_at DESC LIMIT 1",
                (rs, rowNum) -> {
                    AgentConversationSummary summary = new AgentConversationSummary();
                    summary.setId(rs.getString("summary_id"));
                    summary.setConversationId(rs.getString("conversation_id"));
                    summary.setSummaryText(rs.getString("summary_text"));
                    summary.setCoveredUntil(rs.getTimestamp("covered_until").toLocalDateTime());
                    summary.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                    return summary;
                }, userId, conversationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<AgentStoredMessage> messagesAfter(Long userId, String conversationId, LocalDateTime after, int limit) {
        String afterClause = after == null ? "" : " AND created_at > ?";
        String sql = "SELECT message_id, trace_id, role, content, created_at FROM " + table("agent_message")
                + " WHERE user_id=? AND conversation_id=?" + afterClause
                + " ORDER BY created_at ASC, message_id ASC LIMIT ?";
        Object[] args = after == null
                ? new Object[] {userId, conversationId, limit}
                : new Object[] {userId, conversationId, Timestamp.valueOf(after), limit};
        return jdbcTemplate.query(sql, (rs, rowNum) -> new AgentStoredMessage(
                rs.getString("message_id"), rs.getString("trace_id"), rs.getString("role"),
                rs.getString("content"), rs.getTimestamp("created_at").toLocalDateTime()), args);
    }

    @Override
    public void saveSummary(Long userId, String conversationId, String summaryText, LocalDateTime coveredUntil) {
        jdbcTemplate.update("INSERT INTO " + table("agent_conversation_summary")
                + " (summary_id, conversation_id, user_id, summary_text, covered_until) VALUES (?, ?, ?, ?, ?)",
                id(), conversationId, userId, StrUtil.subWithLength(summaryText, 0, 1200),
                Timestamp.valueOf(coveredUntil));
    }

    @Override
    public List<AgentUserMemory> activeMemories(Long userId) {
        return jdbcTemplate.query("SELECT category, memory_key, memory_value, scope, expires_at, updated_at FROM "
                        + table("agent_user_memory")
                        + " WHERE user_id=? AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)"
                        + " ORDER BY updated_at DESC LIMIT 30",
                (rs, rowNum) -> {
                    AgentUserMemory memory = new AgentUserMemory();
                    memory.setCategory(rs.getString("category"));
                    memory.setMemoryKey(rs.getString("memory_key"));
                    memory.setValue(rs.getString("memory_value"));
                    memory.setScope(rs.getString("scope"));
                    Timestamp expires = rs.getTimestamp("expires_at");
                    memory.setExpiresAt(expires == null ? null : expires.toLocalDateTime());
                    memory.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
                    return memory;
                }, userId);
    }

    @Override
    public void acceptMemory(Long userId, String conversationId, String sourceMessage, String category,
            String memoryKey, String value, String scope, LocalDateTime expiresAt) {
        String safeValue = StrUtil.subWithLength(value, 0, 240);
        jdbcTemplate.update("INSERT INTO " + table("agent_memory_candidate")
                        + " (candidate_id, user_id, conversation_id, category, memory_key, memory_value, "
                        + "source_excerpt, decision) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACCEPTED')",
                id(), userId, conversationId, category, memoryKey, safeValue,
                StrUtil.subWithLength(sourceMessage, 0, 500));
        jdbcTemplate.update("INSERT INTO " + table("agent_user_memory")
                        + " (memory_id, user_id, category, memory_key, memory_value, scope, status, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?) "
                        + "ON CONFLICT (user_id, category, memory_key, scope) DO UPDATE SET "
                        + "memory_value=EXCLUDED.memory_value, status='ACTIVE', expires_at=EXCLUDED.expires_at, "
                        + "updated_at=CURRENT_TIMESTAMP",
                id(), userId, category, memoryKey, safeValue, scope,
                expiresAt == null ? null : Timestamp.valueOf(expiresAt));
    }

    @Override
    public void deleteMemories(Long userId, String category) {
        if (StrUtil.isBlank(category)) {
            jdbcTemplate.update("UPDATE " + table("agent_user_memory")
                    + " SET status='DELETED', updated_at=CURRENT_TIMESTAMP WHERE user_id=?", userId);
        } else {
            jdbcTemplate.update("UPDATE " + table("agent_user_memory")
                    + " SET status='DELETED', updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND category=?",
                    userId, category);
        }
    }

    private String table(String name) {
        return schema + "." + name;
    }

    private String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
