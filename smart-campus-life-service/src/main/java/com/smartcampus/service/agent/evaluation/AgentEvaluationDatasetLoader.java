package com.smartcampus.service.agent.evaluation;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 从 classpath 或文件路径读取 Golden Dataset。
 *
 * <p>数据集与评分代码分离，后续增加真实失败样本时只需追加 JSON，不必修改 Java 编排逻辑。</p>
 */
@Component
public class AgentEvaluationDatasetLoader {
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final String datasetLocation;

    public AgentEvaluationDatasetLoader(ResourceLoader resourceLoader, ObjectMapper objectMapper,
            @Value("${agent.evaluation.dataset:classpath:agent-evaluation/golden-dataset.json}") String datasetLocation) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.datasetLocation = datasetLocation;
    }

    /** 每次运行重新读取，开发环境修改数据集后无需重启应用。 */
    public List<AgentEvaluationCase> load() {
        Resource resource = resourceLoader.getResource(datasetLocation);
        if (!resource.exists()) {
            throw new IllegalStateException("Agent 评测数据集不存在：" + datasetLocation);
        }
        try (InputStream input = resource.getInputStream()) {
            List<AgentEvaluationCase> cases = objectMapper.readValue(input, new TypeReference<List<AgentEvaluationCase>>() {
            });
            if (cases == null || cases.isEmpty()) {
                throw new IllegalStateException("Agent 评测数据集不能为空：" + datasetLocation);
            }
            return cases;
        } catch (IOException e) {
            throw new IllegalStateException("读取 Agent 评测数据集失败：" + datasetLocation, e);
        }
    }
}
