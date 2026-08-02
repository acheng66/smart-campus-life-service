package com.smartcampus.service.agent.stream;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.smartcampus.dto.AgentCard;
import com.smartcampus.dto.AgentChatRequest;
import com.smartcampus.dto.AgentChatResponse;
import com.smartcampus.dto.AgentStreamEvent;
import com.smartcampus.dto.Result;
import com.smartcampus.dto.UserDTO;
import com.smartcampus.service.agent.ICampusAgentService;
import com.smartcampus.utils.auth.UserHolder;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 把现有受控 Agent 执行包装为 SSE 事件流。
 *
 * <p>模型规划、工具调用、权限校验仍按原有同步顺序执行，避免异步切线程破坏工具上下文；
 * 浏览器会实时收到工作流阶段，最终文本通过 delta 事件分块发送，可信卡片在响应校验后单独发送。
 * 连接中断只停止向客户端写事件，不会绕过或重放任何业务写操作。</p>
 */
@Slf4j
@Service
public class AgentStreamingService {
    private final ICampusAgentService campusAgentService;
    private final Executor taskExecutor;
    private final TaskScheduler heartbeatScheduler;
    private final long timeoutMs;
    private final int chunkCodePoints;

    public AgentStreamingService(ICampusAgentService campusAgentService,
            @Qualifier("agentStreamTaskExecutor") Executor taskExecutor,
            @Qualifier("agentStreamHeartbeatScheduler") TaskScheduler heartbeatScheduler,
            @Value("${agent.stream.timeout-ms:120000}") long timeoutMs,
            @Value("${agent.stream.chunk-code-points:12}") int chunkCodePoints) {
        this.campusAgentService = campusAgentService;
        this.taskExecutor = taskExecutor;
        this.heartbeatScheduler = heartbeatScheduler;
        this.timeoutMs = Math.max(timeoutMs, 30000L);
        this.chunkCodePoints = Math.max(1, Math.min(chunkCodePoints, 40));
    }

    public SseEmitter stream(AgentChatRequest request) {
        UserDTO currentUser = UserHolder.getUser();
        SseEmitter emitter = new SseEmitter(timeoutMs);
        Object sendLock = new Object();
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicLong sequence = new AtomicLong();
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();

        Consumer<AgentStreamEvent> publisher = event -> {
            if (event == null || closed.get()) {
                return;
            }
            event.setSequence(sequence.incrementAndGet());
            send(emitter, sendLock, closed, event);
        };
        Runnable cleanup = () -> {
            closed.set(true);
            ScheduledFuture<?> future = heartbeat.getAndSet(null);
            if (future != null) {
                future.cancel(false);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        publisher.accept(AgentStreamEvent.of("connected", "STREAM_CONNECTED", "已建立实时连接"));
        heartbeat.set(heartbeatScheduler.scheduleAtFixedRate(
                () -> sendHeartbeat(emitter, sendLock, closed), Duration.ofSeconds(15)));

        if (currentUser == null) {
            publisher.accept(AgentStreamEvent.of("error", "UNAUTHORIZED", "请先登录后再使用校园助手"));
            complete(emitter, cleanup);
            return emitter;
        }

        try {
            taskExecutor.execute(() -> execute(request, currentUser, emitter, publisher, cleanup));
        } catch (RuntimeException e) {
            log.warn("Agent SSE 任务提交失败", e);
            publisher.accept(AgentStreamEvent.of("error", "STREAM_BUSY", "当前请求较多，请稍后再试"));
            complete(emitter, cleanup);
        }
        return emitter;
    }

    private void execute(AgentChatRequest request, UserDTO user, SseEmitter emitter,
            Consumer<AgentStreamEvent> publisher, Runnable cleanup) {
        UserHolder.saveUser(user);
        AgentStreamEventContext.bind(publisher);
        try {
            Result result = campusAgentService.chat(request);
            if (!Boolean.TRUE.equals(result.getSuccess()) || !(result.getData() instanceof AgentChatResponse)) {
                String message = StrUtil.blankToDefault(result.getErrorMsg(), "暂时无法查询，请稍后再试");
                publisher.accept(AgentStreamEvent.of("error", "AGENT_REQUEST_FAILED", message));
                return;
            }
            AgentChatResponse response = (AgentChatResponse) result.getData();
            AgentStreamEvent metadata = AgentStreamEvent.of("metadata", "RESPONSE_METADATA", null);
            metadata.setTraceId(response.getTraceId());
            metadata.setConversationId(response.getConversationId());
            publisher.accept(metadata);

            for (String chunk : chunks(response.getAnswer())) {
                publisher.accept(AgentStreamEvent.delta(response.getTraceId(), response.getConversationId(), chunk));
            }
            List<AgentCard> cards = response.getCards() == null ? new ArrayList<>() : response.getCards();
            publisher.accept(AgentStreamEvent.cards(response.getTraceId(), response.getConversationId(), cards));

            AgentStreamEvent completed = AgentStreamEvent.of("complete", "STREAM_COMPLETED", "本轮回答完成");
            completed.setTraceId(response.getTraceId());
            completed.setConversationId(response.getConversationId());
            publisher.accept(completed);
        } catch (Exception e) {
            log.error("Agent SSE 执行失败", e);
            publisher.accept(AgentStreamEvent.of("error", "STREAM_EXECUTION_FAILED", "暂时无法查询，请稍后再试"));
        } finally {
            AgentStreamEventContext.clear();
            UserHolder.removeUser();
            complete(emitter, cleanup);
        }
    }

    /** 按 Unicode code point 切分，避免把 emoji 的代理对拆成两个无效字符。 */
    private List<String> chunks(String text) {
        String value = StrUtil.blankToDefault(text, "已完成查询，请查看下方真实业务卡片。");
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < value.length()) {
            int remaining = value.codePointCount(start, value.length());
            int end = value.offsetByCodePoints(start, Math.min(chunkCodePoints, remaining));
            result.add(value.substring(start, end));
            start = end;
        }
        return result;
    }

    private void send(SseEmitter emitter, Object sendLock, AtomicBoolean closed, AgentStreamEvent event) {
        try {
            synchronized (sendLock) {
                if (closed.get()) {
                    return;
                }
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(event.getSequence()))
                        .name(event.getType())
                        .data(event, MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException e) {
            closed.set(true);
            log.debug("Agent SSE 客户端已断开, event={}", event.getType());
        }
    }

    private void sendHeartbeat(SseEmitter emitter, Object sendLock, AtomicBoolean closed) {
        try {
            synchronized (sendLock) {
                if (!closed.get()) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                }
            }
        } catch (IOException | IllegalStateException e) {
            closed.set(true);
        }
    }

    private void complete(SseEmitter emitter, Runnable cleanup) {
        cleanup.run();
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // 客户端断开或容器已完成响应时无需再次处理。
        }
    }
}
