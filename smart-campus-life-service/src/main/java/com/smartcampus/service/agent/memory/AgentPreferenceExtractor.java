package com.smartcampus.service.agent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;

/**
 * 从用户原句提取可验证的显式偏好。该组件不调用模型，因此结果确定、可测试，也不会推断敏感画像。
 */
@Component
public class AgentPreferenceExtractor {
    private static final Pattern AVOID_PATTERN = Pattern.compile("(?:不吃|不要吃)([^，。,.！？!]{1,30})");
    private static final Pattern ALLERGY_PATTERN = Pattern.compile("对([^，。,.！？!]{1,30})过敏");
    private static final Pattern LIKE_PATTERN = Pattern.compile("(?:喜欢吃|喜欢|偏好)([^，。,.！？!]{1,30})");
    private static final Pattern BUDGET_PATTERN = Pattern.compile("(?:预算|人均)[^0-9０-９]{0,8}([0-9０-９]{1,5})");

    public ExtractionResult extract(String message) {
        String text = StrUtil.trim(message);
        if (StrUtil.isBlank(text)) {
            return ExtractionResult.empty();
        }
        String forgetCategory = forgetCategory(text);
        if (forgetCategory != null || isForgetAll(text)) {
            return new ExtractionResult(Collections.emptyList(), true, forgetCategory);
        }
        if (isTemporary(text)) {
            return ExtractionResult.empty();
        }
        List<PreferenceCandidate> candidates = new ArrayList<>();
        addMatch(candidates, text, AVOID_PATTERN, "DIETARY_RESTRICTION", "avoid_food");
        addMatch(candidates, text, ALLERGY_PATTERN, "DIETARY_RESTRICTION", "allergy");
        addMatch(candidates, text, LIKE_PATTERN, "FOOD_PREFERENCE", "favorite_food");
        Matcher budget = BUDGET_PATTERN.matcher(text);
        if (budget.find()) {
            candidates.add(new PreferenceCandidate("BUDGET", "per_person", budget.group(1) + "元"));
        }
        return new ExtractionResult(candidates, false, null);
    }

    private void addMatch(List<PreferenceCandidate> result, String text, Pattern pattern,
            String category, String memoryKey) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String value = sanitize(matcher.group(1));
            if (StrUtil.isNotBlank(value)) {
                result.add(new PreferenceCandidate(category, memoryKey, value));
            }
        }
    }

    private String forgetCategory(String text) {
        if (!hasForgetVerb(text)) {
            return null;
        }
        if (text.contains("忌口") || text.contains("过敏") || text.contains("不吃")) {
            return "DIETARY_RESTRICTION";
        }
        if (text.contains("预算") || text.contains("人均")) {
            return "BUDGET";
        }
        if (text.contains("喜欢") || text.contains("偏好")) {
            return "FOOD_PREFERENCE";
        }
        return null;
    }

    private boolean isForgetAll(String text) {
        return hasForgetVerb(text) && (text.contains("全部") || text.contains("所有") || text.contains("记忆"));
    }

    private boolean hasForgetVerb(String text) {
        return text.contains("忘掉") || text.contains("忘记") || text.contains("清除") || text.contains("删除");
    }

    private boolean isTemporary(String text) {
        return text.contains("今天") || text.contains("这次") || text.contains("本次") || text.contains("今晚");
    }

    private String sanitize(String value) {
        return StrUtil.subWithLength(StrUtil.trim(value)
                .replaceAll("(?:的店|的餐厅|就行|都可以)$", ""), 0, 80);
    }

    public record PreferenceCandidate(String category, String memoryKey, String value) {
    }

    public record ExtractionResult(List<PreferenceCandidate> candidates, boolean forget,
            String forgetCategory) {
        public static ExtractionResult empty() {
            return new ExtractionResult(Collections.emptyList(), false, null);
        }
    }
}
