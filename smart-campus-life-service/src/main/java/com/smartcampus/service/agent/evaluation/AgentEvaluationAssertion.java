package com.smartcampus.service.agent.evaluation;

import lombok.Data;

/** 单条可解释评分断言；失败报告会明确指出预期与实际。 */
@Data
public class AgentEvaluationAssertion {
    private String rule;
    private boolean passed;
    private String detail;
    private AgentEvaluationSeverity severity;

    public AgentEvaluationAssertion(String rule, boolean passed, String detail) {
        this(rule, passed, detail, AgentEvaluationSeverity.ERROR);
    }

    public AgentEvaluationAssertion(String rule, boolean passed, String detail, AgentEvaluationSeverity severity) {
        this.rule = rule;
        this.passed = passed;
        this.detail = detail;
        this.severity = severity == null ? AgentEvaluationSeverity.ERROR : severity;
    }
}
