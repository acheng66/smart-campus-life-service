package com.smartcampus.controller.agent;

import jakarta.annotation.Resource;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.smartcampus.dto.AgentActionConfirmRequest;
import com.smartcampus.dto.AgentChatRequest;
import com.smartcampus.dto.Result;
import com.smartcampus.service.agent.ICampusAgentService;
import com.smartcampus.service.agent.stream.AgentStreamingService;

/**
 * 校园助手 HTTP 入口。
 *
 * <p>该路径未在登录白名单中，所有调用均要求已登录。Controller 只转发请求；
 * 限流、模型编排、权限校验和最终领券都在 Service 层执行。</p>
 */
@RestController
@RequestMapping("/agent")
public class AgentController {
    @Resource
    private ICampusAgentService campusAgentService;
    @Resource
    private AgentStreamingService agentStreamingService;

    @PostMapping("/chat")
    public Result chat(@RequestBody AgentChatRequest request) {
        return campusAgentService.chat(request);
    }

    /**
     * SSE 流式对话入口。
     *
     * <p>POST 允许前端继续通过 authorization 请求头携带登录 Token。关闭 Nginx 缓冲后，
     * 客户端会依次收到 connected/status/metadata/delta/cards/complete 事件。</p>
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamChat(@RequestBody AgentChatRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noCache());
        headers.set("X-Accel-Buffering", "no");
        headers.set("Connection", "keep-alive");
        return ResponseEntity.ok().headers(headers).body(agentStreamingService.stream(request));
    }

    /**
     * 用户显式确认后才执行领券。
     *
     * <p>请求中只接受服务端签发的短期 Token，不接受前端传入的券 ID 或动作类型，
     * 防止模型或客户端伪造写操作参数。</p>
     */
    @PostMapping("/actions/confirm")
    public Result confirmAction(@RequestBody AgentActionConfirmRequest request) {
        return campusAgentService.confirmAction(request);
    }

    /** 查询当前用户某次请求的完整状态时间线；不能读取其他用户的 traceId。 */
    @GetMapping("/workflows/{traceId}")
    public Result queryWorkflow(@PathVariable String traceId) {
        return campusAgentService.queryWorkflow(traceId);
    }

    /** 查询当前用户最近的工作流，默认 10 条，最大值由服务端限制。 */
    @GetMapping("/workflows")
    public Result queryRecentWorkflows(@RequestParam(defaultValue = "10") Integer limit) {
        return campusAgentService.queryRecentWorkflows(limit);
    }
}
