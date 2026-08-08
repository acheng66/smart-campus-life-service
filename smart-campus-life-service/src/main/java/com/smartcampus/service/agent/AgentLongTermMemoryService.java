package com.smartcampus.service.agent;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.Resource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.smartcampus.service.agent.memory.AgentMemoryRepository;
import com.smartcampus.service.agent.memory.AgentPreferenceExtractor;
import com.smartcampus.service.agent.memory.AgentPreferenceExtractor.ExtractionResult;
import com.smartcampus.service.agent.memory.AgentPreferenceExtractor.PreferenceCandidate;
import com.smartcampus.service.agent.memory.AgentUserMemory;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 结构化长期记忆。
 *
 * <p>只接受用户原句中明确出现的忌口、食物偏好和预算，不让模型猜测用户画像；
 * “今天/这次”等临时约束只留在会话记忆，不提升为跨会话偏好。PostgreSQL 是事实源，
 * Redis Hash 是 180 天读取缓存。</p>
 */
@Slf4j
@Service
public class AgentLongTermMemoryService {
    private static final String KEY_PREFIX = "agent:profile:";
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private AgentPreferenceExtractor preferenceExtractor;
    @Autowired(required = false)
    private AgentMemoryRepository memoryRepository;

    /** 读取结构化偏好；数据库可用时缓存未命中会自动回填 Redis。 */
    public String profilePrompt(Long userId) {
        String key = KEY_PREFIX + userId;
        Map<Object, Object> cached = stringRedisTemplate.opsForHash().entries(key);
        if (!cached.isEmpty()) {
            return readable(cached);
        }
        if (memoryRepository == null) {
            return "无";
        }
        try {
            List<AgentUserMemory> memories = memoryRepository.activeMemories(userId);
            if (memories.isEmpty()) {
                return "无";
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (AgentUserMemory memory : memories) {
                values.put(memory.getCategory() + ":" + memory.getMemoryKey(), memory.getValue());
            }
            stringRedisTemplate.opsForHash().putAll(key, values);
            stringRedisTemplate.expire(key, 180, TimeUnit.DAYS);
            return values.toString();
        } catch (Exception e) {
            log.warn("读取 Agent 长期偏好失败，继续使用空偏好, userId={}", userId, e);
            return "无";
        }
    }

    public void captureExplicitPreference(Long userId, String message) {
        captureExplicitPreference(userId, null, message);
    }

    /**
     * 将显式偏好先记录为候选审计，再激活为结构化记忆；冲突值按相同业务键覆盖。
     * 用户说“忘掉/清除……”时支持删除对应类别或全部偏好。
     */
    public void captureExplicitPreference(Long userId, String conversationId, String message) {
        String text = StrUtil.trim(message);
        if (StrUtil.isBlank(text)) {
            return;
        }
        ExtractionResult extraction = preferenceExtractor.extract(text);
        if (extraction.forget()) {
            forget(userId, extraction.forgetCategory());
            return;
        }
        for (PreferenceCandidate candidate : extraction.candidates()) {
            accept(userId, conversationId, text, candidate.category(), candidate.memoryKey(), candidate.value());
        }
    }

    private void accept(Long userId, String conversationId, String source, String category,
            String memoryKey, String value) {
        String safeValue = StrUtil.subWithLength(value, 0, 80);
        if (memoryRepository != null) {
            // 用户偏好保留 180 天；每次明确表达会刷新过期时间。
            memoryRepository.acceptMemory(userId, conversationId, source, category, memoryKey,
                    safeValue, "GLOBAL", LocalDateTime.now().plusDays(180));
        }
        String key = KEY_PREFIX + userId;
        stringRedisTemplate.opsForHash().put(key, category + ":" + memoryKey, safeValue);
        stringRedisTemplate.expire(key, 180, TimeUnit.DAYS);
    }

    private void forget(Long userId, String category) {
        if (memoryRepository != null) {
            memoryRepository.deleteMemories(userId, category);
        }
        // 删除整个缓存，下一次读取会从 PostgreSQL 重建剩余的有效偏好。
        stringRedisTemplate.delete(KEY_PREFIX + userId);
    }

    private String readable(Map<Object, Object> profile) {
        Map<String, Object> values = new LinkedHashMap<>();
        profile.forEach((key, value) -> values.put(String.valueOf(key), value));
        return values.isEmpty() ? "无" : values.toString();
    }
}
