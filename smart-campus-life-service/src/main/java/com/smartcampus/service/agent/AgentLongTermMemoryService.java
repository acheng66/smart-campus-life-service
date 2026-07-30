package com.smartcampus.service.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import cn.hutool.core.util.StrUtil;

/**
 * 长期偏好记忆：仅保存用户明确表达的饮食偏好和预算，不从模型推断个人信息。
 * Redis Hash 保留 180 天；业务实时状态不进入长期记忆。
 */
@Service
public class AgentLongTermMemoryService {
    private static final String KEY_PREFIX = "agent:profile:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 将当前用户已保存的偏好转换为 Prompt 文本。
     * 这里只提供理解上下文的参考，不能替代实时库存、资格或权限判断。
     */
    public String profilePrompt(Long userId) {
        Map<Object, Object> profile = stringRedisTemplate.opsForHash().entries(KEY_PREFIX + userId);
        if (profile.isEmpty()) {
            return "无";
        }
        Map<String, Object> readable = new LinkedHashMap<>();
        profile.forEach((key, value) -> readable.put(String.valueOf(key), value));
        return readable.toString();
    }

    /**
     * 从用户明确表达的原句中提取少量偏好。
     * 当前采用简单规则（不吃、喜欢、预算/人均），刻意不调用模型推断隐私信息。
     */
    public void captureExplicitPreference(Long userId, String message) {
        String text = message == null ? "" : message.trim();
        String key = KEY_PREFIX + userId;
        if (text.contains("不吃")) {
            save(key, "忌口", StrUtil.subAfter(text, "不吃", false));
        }
        if (text.contains("喜欢")) {
            save(key, "偏好", StrUtil.subAfter(text, "喜欢", false));
        }
        if (text.matches(".*(预算|人均).*[0-9０-９]+.*")) {
            save(key, "预算描述", text);
        }
    }

    /** 保存单项偏好并刷新 180 天 TTL；每项最多保留 80 个字符。 */
    private void save(String key, String field, String value) {
        if (StrUtil.isBlank(value)) {
            return;
        }
        stringRedisTemplate.opsForHash().put(key, field, StrUtil.subWithLength(value.trim(), 0, 80));
        stringRedisTemplate.expire(key, 180, TimeUnit.DAYS);
    }
}
