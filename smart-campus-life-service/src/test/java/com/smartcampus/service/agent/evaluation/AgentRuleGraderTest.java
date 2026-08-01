package com.smartcampus.service.agent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcampus.dto.AgentCard;
import com.smartcampus.dto.AgentChatResponse;
import com.smartcampus.dto.AgentExecutionTrace;
import com.smartcampus.dto.Result;

class AgentRuleGraderTest {
    private final AgentRuleGrader grader = new AgentRuleGrader();

    @Test
    void goldenDatasetShouldBeReadableAndHaveStableIds() throws Exception {
        List<AgentEvaluationCase> cases;
        try (java.io.InputStream input = getClass().getResourceAsStream("/agent-evaluation/golden-dataset.json")) {
            assertThat(input).isNotNull();
            cases = new ObjectMapper().readValue(input, new TypeReference<List<AgentEvaluationCase>>() {
            });
        }

        assertThat(cases).hasSize(30);
        assertThat(cases).extracting(AgentEvaluationCase::getId).doesNotHaveDuplicates();
        assertThat(cases).allSatisfy(item -> {
            assertThat(item.getMessage()).isNotBlank();
            assertThat(item.getExpectation()).isNotNull();
            assertThat(item.getLevel()).isIn("SMOKE", "REGRESSION", "SECURITY");
            assertThat(item.getCategory()).isNotBlank();
            assertThat(item.getTags()).isNotEmpty();
        });
        assertThat(cases).filteredOn(item -> "SMOKE".equals(item.getLevel())).hasSize(8);
        assertThat(cases).filteredOn(item -> "SECURITY".equals(item.getLevel())).hasSize(8);
    }

    @Test
    void shouldPassWhenTrajectoryAndAnswerMatchGoldenExpectation() {
        AgentEvaluationCase evaluationCase = recommendationCase();
        AgentChatResponse response = response("推荐北苑烤肉饭，评分高且有优惠券。",
                shopCard(2L, "北苑烤肉饭"));
        response.getExecutionTrace().setToolCalls(Arrays.asList("searchShops", "selectShopRecommendations"));

        AgentEvaluationTrialResult graded = grader.grade(evaluationCase, 1, Result.ok(response));

        assertThat(graded.isPassed()).isTrue();
        assertThat(graded.getScore()).isEqualTo(100D);
    }

    @Test
    void shouldExplainMissingToolDuplicateCardAndUnsafeClaim() {
        AgentEvaluationCase evaluationCase = recommendationCase();
        AgentCard first = shopCard(2L, "北苑烤肉饭");
        AgentCard duplicate = shopCard(2L, "北苑烤肉饭");
        duplicate.setActionLabel("确认领取");
        AgentChatResponse response = response("已经领取成功。", first, duplicate);
        response.getExecutionTrace().setToolCalls(List.of("searchShops"));

        AgentEvaluationTrialResult graded = grader.grade(evaluationCase, 1, Result.ok(response));

        assertThat(graded.isPassed()).isFalse();
        assertThat(graded.getAssertions()).filteredOn(item -> !item.isPassed())
                .extracting(AgentEvaluationAssertion::getRule)
                .contains("required_tool:selectShopRecommendations", "unique_card_keys", "action_token_pair",
                        "shop_cards_match_answer", "forbidden_phrase:已经领取成功");
    }

    @Test
    void shouldFailCleanlyWhenAgentServiceReturnsError() {
        AgentEvaluationTrialResult graded = grader.grade(recommendationCase(), 1, Result.fail("模型超时"));

        assertThat(graded.isPassed()).isFalse();
        assertThat(graded.getAssertions()).hasSize(1);
        assertThat(graded.getAssertions().get(0).getRule()).isEqualTo("service_success");
    }

    @Test
    void warningShouldBeReportedWithoutFailingBusinessResult() {
        AgentEvaluationCase evaluationCase = recommendationCase();
        evaluationCase.getExpectation().setWarningTools(List.of("queryShopVouchers"));
        AgentChatResponse response = response("推荐北苑烤肉饭，评分高且有优惠券。",
                shopCard(2L, "北苑烤肉饭"));
        response.getExecutionTrace().setToolCalls(
                Arrays.asList("searchShops", "queryShopVouchers", "selectShopRecommendations"));

        AgentEvaluationTrialResult graded = grader.grade(evaluationCase, 1, Result.ok(response));

        assertThat(graded.isPassed()).isTrue();
        assertThat(graded.getErrorCount()).isZero();
        assertThat(graded.getWarningCount()).isEqualTo(1);
        assertThat(graded.getAssertions()).filteredOn(item -> !item.isPassed())
                .allMatch(item -> item.getSeverity() == AgentEvaluationSeverity.WARNING);
    }

    private AgentEvaluationCase recommendationCase() {
        AgentEvaluationExpectation expectation = new AgentEvaluationExpectation();
        expectation.setExpectedMode("AI");
        expectation.setRequiredTools(Arrays.asList("searchShops", "selectShopRecommendations"));
        expectation.setRequireCards(true);
        expectation.setAllowedCardTypes(List.of("shop"));
        expectation.setMaxCards(3);
        expectation.setRequireShopCardsMentionedInAnswer(true);
        expectation.setNoMarkdown(true);
        expectation.setMaxAnswerLength(180);
        expectation.setForbiddenAnswerPhrases(List.of("已经领取成功"));
        AgentEvaluationCase evaluationCase = new AgentEvaluationCase();
        evaluationCase.setId("recommend");
        evaluationCase.setName("推荐");
        evaluationCase.setMessage("推荐店铺");
        evaluationCase.setExpectation(expectation);
        return evaluationCase;
    }

    private AgentChatResponse response(String answer, AgentCard... cards) {
        AgentExecutionTrace trace = new AgentExecutionTrace();
        trace.setMode("AI");
        trace.setDurationMs(20);
        AgentChatResponse response = new AgentChatResponse();
        response.setTraceId("trace");
        response.setAnswer(answer);
        response.setCards(Arrays.asList(cards));
        response.setExecutionTrace(trace);
        return response;
    }

    private AgentCard shopCard(Long shopId, String title) {
        AgentCard card = new AgentCard();
        card.setType("shop");
        card.setShopId(shopId);
        card.setTitle(title);
        return card;
    }
}
