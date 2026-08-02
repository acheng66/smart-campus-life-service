package com.smartcampus.service.agent.stream;

import java.util.function.Consumer;

import com.smartcampus.dto.AgentStreamEvent;
import com.smartcampus.service.agent.workflow.AgentWorkflowExecution;
import com.smartcampus.service.agent.workflow.AgentWorkflowState;

/**
 * 将同步 Agent 工作流的状态变更桥接到当前 SSE 请求。
 *
 * <p>现有 ChatClient 工具链依赖 ThreadLocal 且必须在一个工作线程内顺序执行，
 * 因而这里同样使用 ThreadLocal 绑定单次 SSE 发布器。普通 {@code /agent/chat}
 * 没有绑定发布器时所有调用都是空操作，不影响兼容接口。</p>
 */
public final class AgentStreamEventContext {
    private static final ThreadLocal<Consumer<AgentStreamEvent>> PUBLISHER = new ThreadLocal<>();

    private AgentStreamEventContext() {
    }

    public static void bind(Consumer<AgentStreamEvent> publisher) {
        PUBLISHER.set(publisher);
    }

    public static void clear() {
        PUBLISHER.remove();
    }

    /** 发布经过白名单映射的阶段消息，不向浏览器暴露内部 transition detail。 */
    public static void publishWorkflow(AgentWorkflowExecution execution) {
        Consumer<AgentStreamEvent> publisher = PUBLISHER.get();
        if (publisher == null || execution == null || execution.getState() == null) {
            return;
        }
        AgentStreamEvent event = AgentStreamEvent.of("status", execution.getState().name(),
                userMessage(execution.getState()));
        event.setTraceId(execution.getTraceId());
        event.setConversationId(execution.getConversationId());
        event.setState(execution.getState().name());
        publisher.accept(event);
    }

    /** 工具名只在服务端映射成用户可理解的阶段文案，绝不原样发送到浏览器。 */
    public static void publishTool(String toolName) {
        Consumer<AgentStreamEvent> publisher = PUBLISHER.get();
        if (publisher == null || toolName == null) {
            return;
        }
        String message = switch (toolName) {
            case "searchShops" -> "正在检索匹配店铺";
            case "queryShopVouchers" -> "正在查询店铺优惠券";
            case "queryMyVouchers" -> "正在查询你已领取的优惠券";
            case "checkVoucherEligibility" -> "正在校验优惠券领取资格";
            case "selectShopRecommendations" -> "正在整理店铺推荐";
            case "presentVoucherResults" -> "正在整理可领取优惠券";
            case "presentMyVouchers" -> "正在整理你的优惠券";
            default -> "正在核对真实业务数据";
        };
        publisher.accept(AgentStreamEvent.of("status", "BUSINESS_TOOL_RUNNING", message));
    }

    static String userMessage(AgentWorkflowState state) {
        return switch (state) {
            case CREATED -> "请求已接收";
            case INTENT_RESOLVED -> "正在理解你的需求";
            case CONTEXT_LOADING -> "正在检索会话与知识库";
            case CONTEXT_READY -> "已找到相关资料";
            case MODEL_PLANNING -> "正在查询真实业务数据";
            case TOOLS_EXECUTED -> "业务数据查询完成";
            case DETERMINISTIC_RUNNING -> "正在使用基础查询能力";
            case RESPONSE_VALIDATED -> "正在校验回答与业务卡片";
            case COMPLETED -> "回答已生成";
            case FAILED -> "处理失败";
        };
    }
}
