package com.smartcampus.service.agent.evaluation;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

/** 一次评测运行的汇总报告，可直接保存为回归基线或由 Postman 查看。 */
@Data
public class AgentEvaluationReport {
    private String runId;
    private LocalDateTime startedAt;
    private int totalTrials;
    private int passedTrials;
    private int failedTrials;
    private double passRate;
    private long averageLatencyMs;
    /** 全部 trial 中发现的性能或质量警告总数。 */
    private int totalWarnings;
    /** 所有试验中各工具的真实执行次数。 */
    private Map<String, Integer> toolCallCounts = new LinkedHashMap<>();
    private List<AgentEvaluationTrialResult> results = new ArrayList<>();
}
