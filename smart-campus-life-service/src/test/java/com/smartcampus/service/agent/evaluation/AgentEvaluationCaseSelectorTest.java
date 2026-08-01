package com.smartcampus.service.agent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class AgentEvaluationCaseSelectorTest {
    private final AgentEvaluationCaseSelector selector = new AgentEvaluationCaseSelector();
    private final List<AgentEvaluationCase> cases = List.of(
            evaluationCase("smoke", "SMOKE", "RAG", List.of("rag", "voucher"), null),
            evaluationCase("regression", "REGRESSION", "INTENT", List.of("shop"), null),
            evaluationCase("security", "SECURITY", "SECURITY", List.of("prompt-injection"), null),
            evaluationCase("disabled", "SMOKE", "EDGE", List.of("disabled"), false));

    @Test
    void emptyRequestShouldSelectEnabledSmokeCasesOnly() {
        List<AgentEvaluationCase> selected = selector.select(cases, new AgentEvaluationRunRequest());

        assertThat(selected).extracting(AgentEvaluationCase::getId).containsExactly("smoke");
    }

    @Test
    void shouldFilterByLevelCategoryAndTagCaseInsensitively() {
        AgentEvaluationRunRequest request = new AgentEvaluationRunRequest();
        request.setLevels(List.of("smoke"));
        request.setCategories(List.of("rag"));
        request.setTags(List.of("VOUCHER"));

        List<AgentEvaluationCase> selected = selector.select(cases, request);

        assertThat(selected).extracting(AgentEvaluationCase::getId).containsExactly("smoke");
    }

    @Test
    void explicitIdsShouldTakePriorityAndRejectDisabledOrMissingCases() {
        AgentEvaluationRunRequest request = new AgentEvaluationRunRequest();
        request.setCaseIds(List.of("security"));
        request.setLevels(List.of("SMOKE"));
        assertThat(selector.select(cases, request)).extracting(AgentEvaluationCase::getId)
                .containsExactly("security");

        request.setCaseIds(List.of("disabled", "missing"));
        assertThatThrownBy(() -> selector.select(cases, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或已禁用");
    }

    private AgentEvaluationCase evaluationCase(String id, String level, String category, List<String> tags,
            Boolean enabled) {
        AgentEvaluationCase item = new AgentEvaluationCase();
        item.setId(id);
        item.setName(id);
        item.setMessage(id);
        item.setLevel(level);
        item.setCategory(category);
        item.setTags(tags);
        item.setEnabled(enabled);
        item.setExpectation(new AgentEvaluationExpectation());
        return item;
    }
}
