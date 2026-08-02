package com.smartcampus.service.agent.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.smartcampus.dto.AgentStreamEvent;
import com.smartcampus.service.agent.workflow.AgentWorkflowExecution;
import com.smartcampus.service.agent.workflow.AgentWorkflowState;

class AgentStreamEventContextTest {

    @AfterEach
    void clearContext() {
        AgentStreamEventContext.clear();
    }

    @Test
    void shouldPublishSafeWorkflowMessageWithoutInternalDetail() {
        List<AgentStreamEvent> events = new ArrayList<>();
        AgentStreamEventContext.bind(events::add);
        AgentWorkflowExecution workflow = new AgentWorkflowExecution();
        workflow.setTraceId("trace");
        workflow.setConversationId("conversation");
        workflow.setState(AgentWorkflowState.MODEL_PLANNING);

        AgentStreamEventContext.publishWorkflow(workflow);

        assertEquals(1, events.size());
        assertEquals("status", events.get(0).getType());
        assertEquals("MODEL_PLANNING", events.get(0).getCode());
        assertEquals("正在查询真实业务数据", events.get(0).getMessage());
        assertFalse(events.get(0).getMessage().contains("ChatClient"));
    }

    @Test
    void shouldMapToolNameInsteadOfExposingIt() {
        List<AgentStreamEvent> events = new ArrayList<>();
        AgentStreamEventContext.bind(events::add);

        AgentStreamEventContext.publishTool("queryShopVouchers");

        assertEquals("BUSINESS_TOOL_RUNNING", events.get(0).getCode());
        assertEquals("正在查询店铺优惠券", events.get(0).getMessage());
        assertFalse(events.get(0).getMessage().contains("queryShopVouchers"));
    }

    @Test
    void shouldDoNothingWithoutBoundStream() {
        AgentWorkflowExecution workflow = new AgentWorkflowExecution();
        workflow.setState(AgentWorkflowState.CREATED);
        AgentStreamEventContext.publishWorkflow(workflow);
        AgentStreamEventContext.publishTool("searchShops");
    }
}
