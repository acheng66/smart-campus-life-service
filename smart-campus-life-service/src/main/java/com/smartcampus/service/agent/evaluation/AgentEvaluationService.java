package com.smartcampus.service.agent.evaluation;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.smartcampus.dto.AgentChatRequest;
import com.smartcampus.dto.Result;
import com.smartcampus.dto.UserDTO;
import com.smartcampus.service.agent.ICampusAgentService;
import com.smartcampus.utils.auth.UserHolder;

/**
 * Agent 离线回归评测编排器。
 *
 * <p>它以隔离的虚拟学生身份把 Golden Case 送入真实 {@code ICampusAgentService.chat}，
 * 因而会覆盖 ChatClient 规划、工具、RAG、卡片、降级和安全约束。它绝不调用确认领券接口；
 * 默认关闭，避免普通启动或 CI 在未授权时产生模型与 Embedding 费用。</p>
 */
@Service
@ConditionalOnProperty(prefix = "agent.evaluation", name = "enabled", havingValue = "true")
public class AgentEvaluationService {
    private final ICampusAgentService campusAgentService;
    private final AgentEvaluationDatasetLoader datasetLoader;
    private final AgentEvaluationCaseSelector caseSelector;
    private final AgentRuleGrader grader;
    private final long evaluationUserId;
    private final int maxTrialsPerRun;

    public AgentEvaluationService(ICampusAgentService campusAgentService,
            AgentEvaluationDatasetLoader datasetLoader,
            AgentEvaluationCaseSelector caseSelector,
            AgentRuleGrader grader,
            @Value("${agent.evaluation.user-id:-9000001}") long evaluationUserId,
            @Value("${agent.evaluation.max-trials-per-run:20}") int maxTrialsPerRun) {
        this.campusAgentService = campusAgentService;
        this.datasetLoader = datasetLoader;
        this.caseSelector = caseSelector;
        this.grader = grader;
        this.evaluationUserId = evaluationUserId;
        this.maxTrialsPerRun = maxTrialsPerRun;
    }

    /** 返回当前 Golden Dataset，便于管理员先核对即将执行的用例和断言。 */
    public List<AgentEvaluationCase> cases() {
        return datasetLoader.load();
    }

    /**
     * 执行选中的 Golden Case 并生成汇总报告。
     *
     * <p>每次运行使用不同的负数虚拟 userId，避免与真实用户数据混合，也避免同一分钟重复运行
     * 被聊天限流的历史计数干扰。原管理员 UserHolder 会在 finally 中恢复。</p>
     */
    public AgentEvaluationReport run(AgentEvaluationRunRequest request) {
        List<AgentEvaluationCase> selectedCases = selectCases(request);
        int repeat = request == null || request.getRepeat() == null ? 1 : request.getRepeat();
        if (repeat < 1) {
            throw new IllegalArgumentException("repeat 必须大于等于 1");
        }
        long totalTrials = (long) selectedCases.size() * repeat;
        if (totalTrials > maxTrialsPerRun) {
            throw new IllegalArgumentException("单次最多运行 " + maxTrialsPerRun + " 个 trial，当前请求为 " + totalTrials);
        }

        String runId = UUID.randomUUID().toString().replace("-", "");
        AgentEvaluationReport report = new AgentEvaluationReport();
        report.setRunId(runId);
        report.setStartedAt(LocalDateTime.now());
        UserDTO originalUser = UserHolder.getUser();
        UserDTO evaluationUser = new UserDTO();
        evaluationUser.setId(evaluationUserId - Integer.toUnsignedLong(runId.hashCode()) - 1L);
        evaluationUser.setNickName("agent-evaluation");
        evaluationUser.setRole(0);

        try {
            UserHolder.saveUser(evaluationUser);
            for (AgentEvaluationCase evaluationCase : selectedCases) {
                for (int trial = 1; trial <= repeat; trial++) {
                    AgentChatRequest chatRequest = new AgentChatRequest();
                    chatRequest.setMessage(evaluationCase.getMessage());
                    chatRequest.setX(evaluationCase.getX());
                    chatRequest.setY(evaluationCase.getY());
                    Result serviceResult = campusAgentService.chat(chatRequest);
                    report.getResults().add(grader.grade(evaluationCase, trial, serviceResult));
                }
            }
        } finally {
            if (originalUser == null) {
                UserHolder.removeUser();
            } else {
                UserHolder.saveUser(originalUser);
            }
        }
        summarize(report);
        return report;
    }

    private List<AgentEvaluationCase> selectCases(AgentEvaluationRunRequest request) {
        return caseSelector.select(datasetLoader.load(), request);
    }

    private void summarize(AgentEvaluationReport report) {
        int total = report.getResults().size();
        int passed = (int) report.getResults().stream().filter(AgentEvaluationTrialResult::isPassed).count();
        report.setTotalTrials(total);
        report.setPassedTrials(passed);
        report.setFailedTrials(total - passed);
        report.setPassRate(total == 0 ? 0D : Math.round(passed * 10000D / total) / 100D);
        report.setTotalWarnings(report.getResults().stream()
                .mapToInt(AgentEvaluationTrialResult::getWarningCount).sum());
        report.setAverageLatencyMs(total == 0 ? 0L
                : Math.round(report.getResults().stream().mapToLong(AgentEvaluationTrialResult::getLatencyMs)
                        .average().orElse(0D)));
        Map<String, Integer> toolCounts = new LinkedHashMap<>();
        report.getResults().forEach(item -> item.getToolCalls()
                .forEach(tool -> toolCounts.merge(tool, 1, Integer::sum)));
        report.setToolCallCounts(toolCounts);
    }
}
