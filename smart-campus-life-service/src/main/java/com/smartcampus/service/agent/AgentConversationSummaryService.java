package com.smartcampus.service.agent;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.smartcampus.service.agent.memory.AgentConversationSummary;
import com.smartcampus.service.agent.memory.AgentMemoryRepository;
import com.smartcampus.service.agent.memory.AgentStoredMessage;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 异步增量摘要：只压缩早于最近窗口的历史消息，并保留少量重叠语义。
 * 摘要失败时不移动覆盖游标，下次仍可重试，因此不会静默丢失历史。
 */
@Slf4j
@Service
@ConditionalOnBean(AgentMemoryRepository.class)
public class AgentConversationSummaryService {
    private static final String LOCK_PREFIX = "agent:memory:summary:lock:";

    private final AgentMemoryRepository memoryRepository;
    private final RedissonClient redissonClient;

    @Autowired(required = false)
    @Qualifier("campusAgentChatClient")
    private ChatClient chatClient;

    @Value("${agent.memory.summary.recent-messages:16}")
    private int recentMessages;
    @Value("${agent.memory.summary.min-new-messages:4}")
    private int minNewMessages;
    @Value("${agent.memory.summary.overlap-messages:2}")
    private int overlapMessages;
    @Value("${agent.memory.summary.max-source-messages:120}")
    private int maxSourceMessages;

    public AgentConversationSummaryService(AgentMemoryRepository memoryRepository, RedissonClient redissonClient) {
        this.memoryRepository = memoryRepository;
        this.redissonClient = redissonClient;
    }

    /** 回答已返回后后台执行；同一用户同一会话通过分布式锁串行压缩。 */
    @Async("agentMemoryTaskExecutor")
    public void summarizeIfNeeded(Long userId, String conversationId) {
        if (chatClient == null) {
            return;
        }
        RLock lock = redissonClient.getLock(LOCK_PREFIX + userId + ":" + conversationId);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, 60, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            AgentConversationSummary previous = memoryRepository.latestSummary(userId, conversationId);
            LocalDateTime coveredUntil = previous == null ? null : previous.getCoveredUntil();
            List<AgentStoredMessage> pending = memoryRepository.messagesAfter(userId, conversationId,
                    coveredUntil, Math.max(maxSourceMessages, recentMessages + minNewMessages));
            int compressCount = pending.size() - Math.max(recentMessages, 2);
            if (compressCount < Math.max(minNewMessages, 2)) {
                return;
            }
            List<AgentStoredMessage> delta = pending.subList(0, compressCount);
            List<AgentStoredMessage> overlap = pending.subList(compressCount,
                    Math.min(pending.size(), compressCount + Math.max(overlapMessages, 0)));
            String summary = generateSummary(previous == null ? "无" : previous.getSummaryText(), delta, overlap);
            if (StrUtil.isBlank(summary)) {
                return;
            }
            memoryRepository.saveSummary(userId, conversationId, summary,
                    delta.get(delta.size() - 1).getCreatedAt());
        } catch (Exception e) {
            log.warn("Agent 增量摘要失败，保留旧摘要并等待下次重试, conversationId={}", conversationId, e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String generateSummary(String previousSummary, List<AgentStoredMessage> delta,
            List<AgentStoredMessage> overlap) {
        StringBuilder source = new StringBuilder();
        for (AgentStoredMessage message : delta) {
            source.append('[').append(message.getRole()).append("] ")
                    .append(StrUtil.subWithLength(message.getContent(), 0, 500)).append('\n');
        }
        StringBuilder overlapContext = new StringBuilder();
        for (AgentStoredMessage message : overlap) {
            overlapContext.append('[').append(message.getRole()).append("] ")
                    .append(StrUtil.subWithLength(message.getContent(), 0, 300)).append('\n');
        }
        String result = chatClient.prompt()
                .system("你负责压缩校园助手的历史会话。只保留后续对话仍有用且已明确出现的事实：用户目标、"
                        + "明确约束、已经比较过的对象、尚未完成的问题。不得推断隐私，不得保存库存、券资格、"
                        + "订单状态等会变化的数据，不得添加原文没有的信息。输出一段不超过600字的纯文本摘要。")
                .user("旧摘要（可能为无）：\n" + previousSummary + "\n本次需要并入摘要的历史：\n" + source
                        + "\n仍保留在最近窗口的重叠上下文（只用于消解指代，不要重复罗列）：\n" + overlapContext)
                .call().content();
        return StrUtil.isBlank(result) ? null : StrUtil.subWithLength(result.trim(), 0, 600);
    }
}
