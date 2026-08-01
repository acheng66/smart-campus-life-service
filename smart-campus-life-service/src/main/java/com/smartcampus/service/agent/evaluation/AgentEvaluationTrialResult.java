package com.smartcampus.service.agent.evaluation;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/** 一条 Golden Case 的一次真实运行结果。 */
@Data
public class AgentEvaluationTrialResult {
    private String caseId;
    private String caseName;
    private String level;
    private String category;
    private List<String> tags = new ArrayList<>();
    private int trial;
    private boolean passed;
    /** 通过断言数占总断言数的百分比。 */
    private double score;
    private long latencyMs;
    private String traceId;
    private String answer;
    private String mode;
    private String intent;
    private String presentationType;
    private List<String> toolCalls = new ArrayList<>();
    private int ragHitCount;
    private int cardCount;
    /** 未通过的 ERROR 断言数量。 */
    private int errorCount;
    /** 未通过的 WARNING 断言数量；不影响 passed。 */
    private int warningCount;
    private List<AgentEvaluationAssertion> assertions = new ArrayList<>();
}
