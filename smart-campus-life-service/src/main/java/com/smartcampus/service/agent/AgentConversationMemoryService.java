package com.smartcampus.service.agent;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.Resource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.Data;

/**
 * Redis 短期会话记忆。
 *
 * <p>按“用户 + 会话”隔离，保存最近有限轮用户消息和助手回答，并设置 TTL；避免无限历史造成模型
 * 上下文膨胀或不同用户、不同会话之间串话。</p>
 */
@Service
public class AgentConversationMemoryService {
    private static final String KEY_PREFIX = "agent:conversation:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Value("${agent.memory.short-ttl-hours:24}")
    private long shortTtlHours;
    @Value("${agent.memory.max-messages:12}")
    private int maxMessages;

    /**
     * 校验前端带回的会话 ID；格式异常时生成新 ID。
     * userId 不拼入返回值，但会参与 Redis Key，确保相同 conversationId 也不会跨用户共享记录。
     */
    public String resolveConversationId(Long userId, String requestedId) {
        if (StrUtil.isNotBlank(requestedId) && requestedId.matches("[A-Za-z0-9_-]{16,64}")) {
            return requestedId;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 读取最近消息并按时间正序拼成 Prompt 上下文。
     * Redis 使用 leftPush，所以读取后必须 reverse 才能让模型先看到较早消息。
     */
    public String recentContext(Long userId, String conversationId) {
        List<String> raw = stringRedisTemplate.opsForList().range(key(userId, conversationId), 0, Math.max(maxMessages - 1, 0));
        if (raw == null || raw.isEmpty()) {
            return "无";
        }
        Collections.reverse(raw);
        List<String> lines = new ArrayList<>();
        for (String item : raw) {
            AgentMemoryMessage message = JSONUtil.toBean(item, AgentMemoryMessage.class);
            if (message != null && StrUtil.isNotBlank(message.getContent())) {
                lines.add("[" + message.getRole() + "] " + message.getContent());
            }
        }
        return lines.isEmpty() ? "无" : String.join("\n", lines);
    }

    /**
     * 追加一轮完整对话并刷新 TTL。
     * 单条内容截断至 500 字，列表裁剪至 maxMessages，避免用户输入无限占用 Redis 和模型上下文。
     */
    public void appendTurn(Long userId, String conversationId, String userMessage, String assistantMessage) {
        String key = key(userId, conversationId);
        push(key, "user", userMessage);
        push(key, "assistant", assistantMessage);
        stringRedisTemplate.opsForList().trim(key, 0, Math.max(maxMessages - 1, 0));
        stringRedisTemplate.expire(key, Math.max(shortTtlHours, 1), TimeUnit.HOURS);
    }

    /** 将单条消息序列化为 JSON，便于后续保留角色和时间信息。 */
    private void push(String key, String role, String content) {
        AgentMemoryMessage message = new AgentMemoryMessage();
        message.setRole(role);
        message.setContent(StrUtil.subWithLength(content, 0, 500));
        message.setAt(LocalDateTime.now().toString());
        stringRedisTemplate.opsForList().leftPush(key, JSONUtil.toJsonStr(message));
    }

    /** 短期记忆 Key：agent:conversation:{userId}:{conversationId}。 */
    private String key(Long userId, String conversationId) {
        return KEY_PREFIX + userId + ":" + conversationId;
    }

    @Data
    /** Redis List 中保存的单条会话消息结构。 */
    public static class AgentMemoryMessage {
        private String role;
        private String content;
        private String at;
    }
}
