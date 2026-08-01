package com.smartcampus.service.agent.evaluation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/** 根据 ID、级别、分类和标签筛选评测用例，不依赖模型或 Spring 上下文，便于稳定单测。 */
@Component
public class AgentEvaluationCaseSelector {

    public List<AgentEvaluationCase> select(List<AgentEvaluationCase> allCases, AgentEvaluationRunRequest request) {
        List<AgentEvaluationCase> enabledCases = allCases.stream()
                .filter(item -> !Boolean.FALSE.equals(item.getEnabled())).collect(Collectors.toList());
        if (request != null && request.getCaseIds() != null && !request.getCaseIds().isEmpty()) {
            Set<String> requestedIds = new HashSet<>(request.getCaseIds());
            List<AgentEvaluationCase> selected = enabledCases.stream()
                    .filter(item -> requestedIds.contains(item.getId())).collect(Collectors.toList());
            Set<String> foundIds = selected.stream().map(AgentEvaluationCase::getId).collect(Collectors.toSet());
            List<String> missing = new ArrayList<>(requestedIds);
            missing.removeAll(foundIds);
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("不存在或已禁用的评测用例：" + missing);
            }
            return selected;
        }

        boolean hasLevelFilter = request != null && request.getLevels() != null && !request.getLevels().isEmpty();
        boolean hasCategoryFilter = request != null && request.getCategories() != null
                && !request.getCategories().isEmpty();
        boolean hasTagFilter = request != null && request.getTags() != null && !request.getTags().isEmpty();
        Set<String> levels = normalize(hasLevelFilter ? request.getLevels() : List.of("SMOKE"));
        Set<String> categories = normalize(hasCategoryFilter ? request.getCategories() : List.of());
        Set<String> tags = normalize(hasTagFilter ? request.getTags() : List.of());

        List<AgentEvaluationCase> selected = enabledCases.stream()
                .filter(item -> levels.isEmpty() || levels.contains(normalize(item.getLevel())))
                .filter(item -> categories.isEmpty() || categories.contains(normalize(item.getCategory())))
                .filter(item -> tags.isEmpty() || item.getTags().stream().map(this::normalize).anyMatch(tags::contains))
                .collect(Collectors.toList());
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("没有匹配当前 level、category 或 tag 的评测用例");
        }
        return selected;
    }

    private Set<String> normalize(List<String> values) {
        return values.stream().filter(java.util.Objects::nonNull).map(this::normalize)
                .filter(value -> !value.isEmpty()).collect(Collectors.toSet());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
