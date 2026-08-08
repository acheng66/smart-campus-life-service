package com.smartcampus.service.agent;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.Resource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.smartcampus.service.agent.memory.AgentConversationSummary;
import com.smartcampus.service.agent.memory.AgentMemoryRepository;
import com.smartcampus.service.agent.memory.AgentStoredMessage;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Agent 会话记忆入口：PostgreSQL 保存完整记录，Redis 只缓存最近消息。
 *
 * <p>读取优先走 Redis，缓存缺失时从 PostgreSQL 恢复；PostgreSQL Bean 不存在或暂时异常时，
 * 自动保留原有 Redis-only 能力，记忆故障不会阻断实时业务查询。</p>
 */
@Slf4j
@Service
public class AgentConversationMemoryService {
    private static final String KEY_PREFIX = "agent:conversation:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired(required = false)
    private AgentMemoryRepository memoryRepository;
    @Autowired(required = false)
    private AgentConversationSummaryService summaryService;

    @Value("${agent.memory.short-ttl-hours:24}")
    private long shortTtlHours;
    /** 最近上下文按消息条数控制；推荐 16，即约 8 轮对话。 */
    @Value("${agent.memory.max-messages:16}")
    private int maxMessages;
    /** 二次限制实际注入 Prompt 的字符预算，避免少量超长消息撑爆上下文。 */
    @Value("${agent.memory.max-context-chars:6000}")
    private int maxContextChars;

    /** 校验会话 ID；userId 参与存储 Key 和数据库条件，阻止不同用户串话。 */
    public String resolveConversationId(Long userId, String requestedId) {
        if (StrUtil.isNotBlank(requestedId) && requestedId.matches("[A-Za-z0-9_-]{16,64}")) {
            return requestedId;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    public String recentContext(Long userId, String conversationId) {
        return recentContext(userId, conversationId, null);
    }

    /**
     * 获取最近消息并按时间正序组装。excludedTraceId 用于排除本轮已预写入的用户问题，
     * 防止同一问题既出现在“当前问题”又出现在“最近对话”中。
     */
    public String recentContext(Long userId, String conversationId, String excludedTraceId) {
        List<AgentStoredMessage> messages = readRedis(userId, conversationId, excludedTraceId);
        if (messages.isEmpty() && memoryRepository != null) {
            try {
                messages = memoryRepository.recentMessages(userId, conversationId, maxMessages, excludedTraceId);
                warmRedis(userId, conversationId, messages);
            } catch (Exception e) {
                log.warn("从 PostgreSQL 恢复 Agent 最近会话失败，继续使用空上下文, conversationId={}",
                        conversationId, e);
            }
        }
        if (messages.isEmpty()) {
            return "无";
        }
        List<String> lines = new ArrayList<>();
        int used = 0;
        // 从最新消息向前装填预算，保证发生截断时优先保留最近上下文。
        for (int i = messages.size() - 1; i >= 0; i--) {
            AgentStoredMessage message = messages.get(i);
            if (StrUtil.isNotBlank(message.getContent())) {
                String line = "[" + message.getRole() + "] " + message.getContent();
                int remaining = Math.max(maxContextChars, 200) - used;
                if (remaining <= 0) {
                    break;
                }
                lines.add(StrUtil.subWithLength(line, Math.max(line.length() - remaining, 0), remaining));
                used += Math.min(line.length(), remaining) + 1;
            }
        }
        Collections.reverse(lines);
        return lines.isEmpty() ? "无" : String.join("\n", lines);
    }

    /** 最新增量摘要；摘要版本永久保存在 PostgreSQL，不放入 Redis 消息列表。 */
    public String summaryContext(Long userId, String conversationId) {
        if (memoryRepository == null) {
            return "无";
        }
        try {
            AgentConversationSummary summary = memoryRepository.latestSummary(userId, conversationId);
            return summary == null || StrUtil.isBlank(summary.getSummaryText()) ? "无" : summary.getSummaryText();
        } catch (Exception e) {
            log.warn("读取 Agent 会话摘要失败, conversationId={}", conversationId, e);
            return "无";
        }
    }

    /** 在调用模型前持久化用户消息；traceId + role 唯一约束保证重试不会重复写入。 */
    public void appendUserMessage(Long userId, String conversationId, String traceId, String content) {
        append(userId, conversationId, traceId, "user", content);
    }

    /** 回答完成后持久化助手消息，并异步检查是否达到摘要阈值。 */
    public void appendAssistantMessage(Long userId, String conversationId, String traceId, String content) {
        append(userId, conversationId, traceId, "assistant", content);
        if (summaryService != null) {
            summaryService.summarizeIfNeeded(userId, conversationId);
        }
    }

    /** 兼容旧调用；新主链路使用分阶段写入，以便失败请求也能留下用户问题。 */
    public void appendTurn(Long userId, String conversationId, String userMessage, String assistantMessage) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        appendUserMessage(userId, conversationId, traceId, userMessage);
        appendAssistantMessage(userId, conversationId, traceId, assistantMessage);
    }

    private void append(Long userId, String conversationId, String traceId, String role, String content) {
        AgentStoredMessage message = new AgentStoredMessage(UUID.randomUUID().toString().replace("-", ""),
                traceId, role, StrUtil.subWithLength(StrUtil.blankToDefault(content, ""), 0, 4000),
                LocalDateTime.now());
        pushRedis(userId, conversationId, message);
        if (memoryRepository != null) {
            memoryRepository.appendMessage(userId, conversationId, traceId, role, message.getContent());
        }
    }

    private List<AgentStoredMessage> readRedis(Long userId, String conversationId, String excludedTraceId) {
        List<String> raw = stringRedisTemplate.opsForList().range(key(userId, conversationId), 0,
                Math.max(maxMessages * 2L - 1L, 0L));
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<AgentStoredMessage> result = new ArrayList<>();
        for (String item : raw) {
            AgentMemoryMessage cached = JSONUtil.toBean(item, AgentMemoryMessage.class);
            if (cached == null || StrUtil.isBlank(cached.getContent())
                    || (StrUtil.isNotBlank(excludedTraceId) && excludedTraceId.equals(cached.getTraceId()))) {
                continue;
            }
            LocalDateTime at;
            try {
                at = LocalDateTime.parse(cached.getAt());
            } catch (Exception ignored) {
                at = LocalDateTime.now();
            }
            result.add(new AgentStoredMessage(cached.getId(), cached.getTraceId(), cached.getRole(),
                    cached.getContent(), at));
            if (result.size() >= maxMessages) {
                break;
            }
        }
        Collections.reverse(result);
        return result;
    }

    private void pushRedis(Long userId, String conversationId, AgentStoredMessage message) {
        String key = key(userId, conversationId);
        AgentMemoryMessage cached = new AgentMemoryMessage();
        cached.setId(message.getId());
        cached.setTraceId(message.getTraceId());
        cached.setRole(message.getRole());
        cached.setContent(StrUtil.subWithLength(message.getContent(), 0, 800));
        cached.setAt(message.getCreatedAt().toString());
        stringRedisTemplate.opsForList().leftPush(key, JSONUtil.toJsonStr(cached));
        stringRedisTemplate.opsForList().trim(key, 0, Math.max(maxMessages - 1L, 0L));
        stringRedisTemplate.expire(key, Math.max(shortTtlHours, 1), TimeUnit.HOURS);
    }

    private void warmRedis(Long userId, String conversationId, List<AgentStoredMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        String key = key(userId, conversationId);
        // 查询结果为时间正序，逐条 leftPush 后 Redis 自然变为“最新在左”。
        for (AgentStoredMessage message : messages) {
            AgentMemoryMessage cached = new AgentMemoryMessage();
            cached.setId(message.getId());
            cached.setTraceId(message.getTraceId());
            cached.setRole(message.getRole());
            cached.setContent(StrUtil.subWithLength(message.getContent(), 0, 800));
            cached.setAt(message.getCreatedAt().toString());
            stringRedisTemplate.opsForList().leftPush(key, JSONUtil.toJsonStr(cached));
        }
        stringRedisTemplate.opsForList().trim(key, 0, Math.max(maxMessages - 1L, 0L));
        stringRedisTemplate.expire(key, Math.max(shortTtlHours, 1), TimeUnit.HOURS);
    }

    private String key(Long userId, String conversationId) {
        return KEY_PREFIX + userId + ":" + conversationId;
    }

    /** Redis 热缓存结构；traceId 用于在本轮上下文中排除当前问题。 */
    @Data
    public static class AgentMemoryMessage {
        private String id;
        private String traceId;
        private String role;
        private String content;
        private String at;
    }
}
