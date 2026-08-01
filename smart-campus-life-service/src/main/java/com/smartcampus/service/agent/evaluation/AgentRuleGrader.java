package com.smartcampus.service.agent.evaluation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.smartcampus.dto.AgentCard;
import com.smartcampus.dto.AgentChatResponse;
import com.smartcampus.dto.AgentExecutionTrace;
import com.smartcampus.dto.Result;

/**
 * 确定性 Agent 评分器。
 *
 * <p>它同时评估最终答案和执行过程：工具是否选对、RAG 是否命中、卡片是否唯一且与回答一致、
 * 是否出现越权写操作承诺。相比只比较字符串，更能发现 Agent 编排退化。</p>
 */
@Component
public class AgentRuleGrader {

    public AgentEvaluationTrialResult grade(AgentEvaluationCase evaluationCase, int trial, Result serviceResult) {
        AgentEvaluationTrialResult result = new AgentEvaluationTrialResult();
        result.setCaseId(evaluationCase.getId());
        result.setCaseName(evaluationCase.getName());
        result.setLevel(evaluationCase.getLevel());
        result.setCategory(evaluationCase.getCategory());
        result.setTags(new ArrayList<>(evaluationCase.getTags()));
        result.setTrial(trial);

        List<AgentEvaluationAssertion> assertions = result.getAssertions();
        boolean serviceSuccess = serviceResult != null && Boolean.TRUE.equals(serviceResult.getSuccess())
                && serviceResult.getData() instanceof AgentChatResponse;
        add(assertions, "service_success", serviceSuccess,
                serviceSuccess ? "Agent 返回成功" : "Agent 调用失败或响应结构异常："
                        + (serviceResult == null ? "无响应" : serviceResult.getErrorMsg()));
        if (!serviceSuccess) {
            finish(result);
            return result;
        }

        AgentChatResponse response = (AgentChatResponse) serviceResult.getData();
        AgentExecutionTrace trace = response.getExecutionTrace();
        List<AgentCard> cards = response.getCards() == null ? new ArrayList<>() : response.getCards();
        String answer = response.getAnswer() == null ? "" : response.getAnswer().trim();
        result.setTraceId(response.getTraceId());
        result.setAnswer(answer);
        result.setCardCount(cards.size());
        if (trace != null) {
            result.setMode(trace.getMode());
            result.setIntent(trace.getIntent());
            result.setPresentationType(trace.getPresentationType());
            result.setToolCalls(new ArrayList<>(trace.getToolCalls()));
            result.setRagHitCount(trace.getRagHitCount());
            result.setLatencyMs(trace.getDurationMs());
        }

        add(assertions, "answer_not_blank", !answer.isEmpty(), answer.isEmpty() ? "回答为空" : "回答非空");
        add(assertions, "execution_trace_present", trace != null,
                trace == null ? "缺少内部执行轨迹" : "已记录执行轨迹");

        AgentEvaluationExpectation expectation = evaluationCase.getExpectation() == null
                ? new AgentEvaluationExpectation()
                : evaluationCase.getExpectation();
        if (expectation.getExpectedMode() != null) {
            add(assertions, "execution_mode", trace != null && expectation.getExpectedMode().equals(trace.getMode()),
                    "期望 " + expectation.getExpectedMode() + "，实际 " + (trace == null ? "无" : trace.getMode()));
        }
        if (expectation.getExpectedIntent() != null) {
            add(assertions, "intent", trace != null && expectation.getExpectedIntent().equals(trace.getIntent()),
                    "期望 " + expectation.getExpectedIntent() + "，实际 " + (trace == null ? "无" : trace.getIntent()));
        }
        if (expectation.getExpectedPresentationType() != null) {
            add(assertions, "presentation_type",
                    trace != null && expectation.getExpectedPresentationType().equals(trace.getPresentationType()),
                    "期望 " + expectation.getExpectedPresentationType() + "，实际 "
                            + (trace == null ? "无" : trace.getPresentationType()));
        }

        List<String> toolCalls = trace == null ? new ArrayList<>() : trace.getToolCalls();
        for (String required : expectation.getRequiredTools()) {
            add(assertions, "required_tool:" + required, toolCalls.contains(required),
                    toolCalls.contains(required) ? "已调用 " + required : "未调用 " + required + "，实际 " + toolCalls);
        }
        for (String forbidden : expectation.getForbiddenTools()) {
            add(assertions, "forbidden_tool:" + forbidden, !toolCalls.contains(forbidden),
                    !toolCalls.contains(forbidden) ? "未调用 " + forbidden : "错误调用了 " + forbidden);
        }
        for (String warningTool : expectation.getWarningTools()) {
            long count = toolCalls.stream().filter(warningTool::equals).count();
            addWarning(assertions, "redundant_tool:" + warningTool, count == 0,
                    count == 0 ? "未发生冗余调用 " + warningTool : "发现 " + count + " 次可避免的调用 " + warningTool);
        }
        if (Boolean.TRUE.equals(expectation.getRagRequired())) {
            add(assertions, "rag_hit", trace != null && trace.getRagHitCount() > 0,
                    "RAG 命中文档数=" + (trace == null ? 0 : trace.getRagHitCount()));
        }
        if (Boolean.TRUE.equals(expectation.getRequireCards())) {
            add(assertions, "cards_required", !cards.isEmpty(),
                    cards.isEmpty() ? "未返回可信卡片" : "返回卡片数=" + cards.size());
        }
        if (expectation.getMaxCards() != null) {
            add(assertions, "max_cards", cards.size() <= expectation.getMaxCards(),
                    "允许最多 " + expectation.getMaxCards() + " 张，实际 " + cards.size() + " 张");
        }
        if (!expectation.getAllowedCardTypes().isEmpty()) {
            List<String> invalidTypes = new ArrayList<>();
            for (AgentCard card : cards) {
                if (card == null || !expectation.getAllowedCardTypes().contains(card.getType())) {
                    invalidTypes.add(card == null ? "null" : card.getType());
                }
            }
            add(assertions, "allowed_card_types", invalidTypes.isEmpty(),
                    invalidTypes.isEmpty() ? "卡片类型符合预期" : "出现未允许类型 " + invalidTypes);
        }

        gradeCardIntegrity(cards, answer, trace, expectation, assertions);
        if (Boolean.TRUE.equals(expectation.getNoMarkdown())) {
            boolean plainText = !containsMarkdown(answer);
            add(assertions, "plain_text", plainText, plainText ? "回答为纯文本" : "回答仍包含 Markdown 标记");
        }
        if (expectation.getMaxAnswerLength() != null) {
            add(assertions, "answer_length", answer.length() <= expectation.getMaxAnswerLength(),
                    "允许最多 " + expectation.getMaxAnswerLength() + " 字，实际 " + answer.length() + " 字");
        }
        for (String phrase : expectation.getForbiddenAnswerPhrases()) {
            add(assertions, "forbidden_phrase:" + phrase, !answer.contains(phrase),
                    answer.contains(phrase) ? "出现禁止表述：" + phrase : "未出现禁止表述：" + phrase);
        }

        finish(result);
        return result;
    }

    private void gradeCardIntegrity(List<AgentCard> cards, String answer, AgentExecutionTrace trace,
            AgentEvaluationExpectation expectation, List<AgentEvaluationAssertion> assertions) {
        Set<String> keys = new HashSet<>();
        List<String> duplicateKeys = new ArrayList<>();
        boolean actionTokensSafe = true;
        for (AgentCard card : cards) {
            if (card == null) {
                actionTokensSafe = false;
                continue;
            }
            String businessId = card.getVoucherId() == null ? String.valueOf(card.getShopId())
                    : String.valueOf(card.getVoucherId());
            String key = card.getType() + ":" + businessId;
            if (!keys.add(key)) {
                duplicateKeys.add(key);
            }
            boolean hasLabel = card.getActionLabel() != null && !card.getActionLabel().isBlank();
            boolean hasToken = card.getActionToken() != null && !card.getActionToken().isBlank();
            if (hasLabel != hasToken) {
                actionTokensSafe = false;
            }
        }
        add(assertions, "unique_card_keys", duplicateKeys.isEmpty(),
                duplicateKeys.isEmpty() ? "卡片业务 Key 唯一" : "重复卡片 Key：" + duplicateKeys);
        add(assertions, "action_token_pair", actionTokensSafe,
                actionTokensSafe ? "操作按钮与服务端 Token 成对出现" : "存在无 Token 按钮或无按钮 Token");

        if (Boolean.TRUE.equals(expectation.getRequireShopCardsMentionedInAnswer())) {
            List<String> missingTitles = new ArrayList<>();
            Set<String> cardTitles = new HashSet<>();
            for (AgentCard card : cards) {
                if ("shop".equals(card.getType()) && card.getTitle() != null) {
                    cardTitles.add(card.getTitle());
                    if (!answer.contains(card.getTitle())) {
                        missingTitles.add(card.getTitle());
                    }
                }
            }
            List<String> extraTitles = trace == null ? new ArrayList<>() : trace.getCandidateShopTitles().stream()
                    .filter(title -> answer.contains(title) && !cardTitles.contains(title)).collect(java.util.stream.Collectors.toList());
            boolean matched = missingTitles.isEmpty() && extraTitles.isEmpty();
            add(assertions, "shop_cards_match_answer", matched,
                    matched ? "回答与店铺卡片双向一致"
                            : "回答未提及卡片店铺=" + missingTitles + "，回答提及但无卡片店铺=" + extraTitles);
        }
    }

    private boolean containsMarkdown(String answer) {
        return answer.contains("**") || answer.contains("```") || answer.contains("|") || answer.contains("#")
                || answer.matches("(?s).*\\n\\s*---+\\s*\\n.*");
    }

    private void add(List<AgentEvaluationAssertion> assertions, String rule, boolean passed, String detail) {
        assertions.add(new AgentEvaluationAssertion(rule, passed, detail));
    }

    private void addWarning(List<AgentEvaluationAssertion> assertions, String rule, boolean passed, String detail) {
        assertions.add(new AgentEvaluationAssertion(rule, passed, detail, AgentEvaluationSeverity.WARNING));
    }

    private void finish(AgentEvaluationTrialResult result) {
        long errorTotal = result.getAssertions().stream()
                .filter(item -> item.getSeverity() == AgentEvaluationSeverity.ERROR).count();
        long passedErrors = result.getAssertions().stream()
                .filter(item -> item.getSeverity() == AgentEvaluationSeverity.ERROR && item.isPassed()).count();
        int failedErrors = (int) (errorTotal - passedErrors);
        int warnings = (int) result.getAssertions().stream()
                .filter(item -> item.getSeverity() == AgentEvaluationSeverity.WARNING && !item.isPassed()).count();
        result.setErrorCount(failedErrors);
        result.setWarningCount(warnings);
        result.setPassed(errorTotal > 0 && failedErrors == 0);
        result.setScore(errorTotal == 0 ? 0D : Math.round(passedErrors * 10000D / errorTotal) / 100D);
    }
}
