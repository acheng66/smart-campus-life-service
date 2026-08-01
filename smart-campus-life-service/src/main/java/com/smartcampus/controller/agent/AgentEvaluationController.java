package com.smartcampus.controller.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartcampus.dto.Result;
import com.smartcampus.service.agent.evaluation.AgentEvaluationRunRequest;
import com.smartcampus.service.agent.evaluation.AgentEvaluationService;

/**
 * 管理员专用的 Agent 自动评测入口。
 *
 * <p>路径位于 /admin/**，会经过管理员拦截器；同时只有
 * {@code agent.evaluation.enabled=true} 时才注册，生产环境默认不存在该接口。</p>
 */
@RestController
@RequestMapping("/admin/agent/evaluations")
@ConditionalOnProperty(prefix = "agent.evaluation", name = "enabled", havingValue = "true")
public class AgentEvaluationController {
    private final AgentEvaluationService evaluationService;

    public AgentEvaluationController(AgentEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    /** 查看当前 Golden Dataset 和每条用例的预期断言。 */
    @GetMapping("/cases")
    public Result cases() {
        return Result.ok(evaluationService.cases());
    }

    /** 运行全部或指定用例；空请求体等价于全部用例各运行一次。 */
    @PostMapping("/run")
    public Result run(@RequestBody(required = false) AgentEvaluationRunRequest request) {
        try {
            return Result.ok(evaluationService.run(request == null ? new AgentEvaluationRunRequest() : request));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.fail(e.getMessage());
        }
    }
}
