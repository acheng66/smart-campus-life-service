package com.smartcampus.service.agent.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentWorkflowStateMachineTest {

    @Test
    void shouldAllowCompleteAiWorkflow() {
        assertTrue(AgentWorkflowStateMachine.canTransition(
                AgentWorkflowState.CREATED, AgentWorkflowState.INTENT_RESOLVED));
        assertTrue(AgentWorkflowStateMachine.canTransition(
                AgentWorkflowState.INTENT_RESOLVED, AgentWorkflowState.CONTEXT_LOADING));
        assertTrue(AgentWorkflowStateMachine.canTransition(
                AgentWorkflowState.CONTEXT_LOADING, AgentWorkflowState.CONTEXT_READY));
        assertTrue(AgentWorkflowStateMachine.canTransition(
                AgentWorkflowState.CONTEXT_READY, AgentWorkflowState.MODEL_PLANNING));
        assertTrue(AgentWorkflowStateMachine.canTransition(
                AgentWorkflowState.MODEL_PLANNING, AgentWorkflowState.TOOLS_EXECUTED));
        assertTrue(AgentWorkflowStateMachine.canTransition(
                AgentWorkflowState.TOOLS_EXECUTED, AgentWorkflowState.RESPONSE_VALIDATED));
        assertTrue(AgentWorkflowStateMachine.canTransition(
                AgentWorkflowState.RESPONSE_VALIDATED, AgentWorkflowState.COMPLETED));
    }

    @Test
    void shouldAllowDeterministicAndModelFallbackPaths() {
        assertTrue(AgentWorkflowStateMachine.canTransition(
                AgentWorkflowState.INTENT_RESOLVED, AgentWorkflowState.DETERMINISTIC_RUNNING));
        assertTrue(AgentWorkflowStateMachine.canTransition(
                AgentWorkflowState.MODEL_PLANNING, AgentWorkflowState.DETERMINISTIC_RUNNING));
        assertTrue(AgentWorkflowStateMachine.canTransition(
                AgentWorkflowState.TOOLS_EXECUTED, AgentWorkflowState.DETERMINISTIC_RUNNING));
        assertTrue(AgentWorkflowStateMachine.canTransition(
                AgentWorkflowState.DETERMINISTIC_RUNNING, AgentWorkflowState.RESPONSE_VALIDATED));
    }

    @Test
    void shouldRejectSkippedAndTerminalTransitions() {
        assertFalse(AgentWorkflowStateMachine.canTransition(
                AgentWorkflowState.CREATED, AgentWorkflowState.COMPLETED));
        assertFalse(AgentWorkflowStateMachine.canTransition(
                AgentWorkflowState.MODEL_PLANNING, AgentWorkflowState.COMPLETED));
        assertFalse(AgentWorkflowStateMachine.canTransition(
                AgentWorkflowState.COMPLETED, AgentWorkflowState.FAILED));
        assertFalse(AgentWorkflowStateMachine.canTransition(
                AgentWorkflowState.FAILED, AgentWorkflowState.CREATED));
        assertThrows(IllegalStateException.class, () -> AgentWorkflowStateMachine.requireTransition(
                AgentWorkflowState.CREATED, AgentWorkflowState.TOOLS_EXECUTED));
    }

    @Test
    void shouldAllowFailureFromEveryNonTerminalState() {
        for (AgentWorkflowState state : AgentWorkflowState.values()) {
            if (!state.isTerminal()) {
                assertTrue(AgentWorkflowStateMachine.canTransition(state, AgentWorkflowState.FAILED),
                        () -> state + " 应允许进入失败终态");
            }
        }
    }
}
