package com.smartcampus.service.agent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.smartcampus.service.agent.memory.AgentPreferenceExtractor.ExtractionResult;

class AgentPreferenceExtractorTest {
    private final AgentPreferenceExtractor extractor = new AgentPreferenceExtractor();

    @Test
    void extractsExplicitGlobalPreferences() {
        ExtractionResult result = extractor.extract("我不吃香菜，人均预算50元");

        assertFalse(result.forget());
        assertEquals(2, result.candidates().size());
        assertEquals("香菜", result.candidates().get(0).value());
        assertEquals("50元", result.candidates().get(1).value());
    }

    @Test
    void ignoresTemporaryConstraints() {
        assertTrue(extractor.extract("今晚不吃辣，人均预算30元").candidates().isEmpty());
    }

    @Test
    void recognizesCategoryForgetCommand() {
        ExtractionResult result = extractor.extract("请忘掉我的预算偏好");

        assertTrue(result.forget());
        assertEquals("BUDGET", result.forgetCategory());
    }
}
