package com.smartcampus.service.agent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/** SiliconFlow Rerank API 客户端；超时或配额异常时保留 RRF 排序结果。 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "agent.rag.reranker", name = "enabled", havingValue = "true")
public class SiliconFlowReranker {
    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public SiliconFlowReranker(RestClient.Builder builder,
            @Value("${agent.rag.reranker.base-url:https://api.siliconflow.cn}") String baseUrl,
            @Value("${agent.rag.reranker.api-key:}") String apiKey,
            @Value("${agent.rag.reranker.model:BAAI/bge-reranker-v2-m3}") String model) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.model = model;
    }

    public List<RagDocumentCandidate> rerank(String query, List<RagDocumentCandidate> candidates, int topN) {
        if (StrUtil.isBlank(apiKey) || candidates == null || candidates.size() < 2) {
            return limit(candidates, topN);
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("query", query);
            body.put("documents", candidates.stream().map(RagDocumentCandidate::getContent).toList());
            body.put("top_n", Math.min(topN, candidates.size()));
            body.put("return_documents", false);
            JsonNode response = restClient.post().uri("/v1/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body).retrieve().body(JsonNode.class);
            JsonNode results = response == null ? null : response.get("results");
            if (results == null || !results.isArray()) {
                return limit(candidates, topN);
            }
            List<RagDocumentCandidate> reranked = new ArrayList<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index >= 0 && index < candidates.size()) {
                    RagDocumentCandidate candidate = candidates.get(index);
                    candidate.setRerankScore(item.path("relevance_score").asDouble(0D));
                    reranked.add(candidate);
                }
            }
            reranked.sort(Comparator.comparing(RagDocumentCandidate::getRerankScore,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            return reranked.isEmpty() ? limit(candidates, topN) : limit(reranked, topN);
        } catch (Exception e) {
            log.warn("SiliconFlow Reranker 调用失败，保留 RRF 融合顺序", e);
            return limit(candidates, topN);
        }
    }

    public boolean isAvailable() {
        return StrUtil.isNotBlank(apiKey);
    }

    private List<RagDocumentCandidate> limit(List<RagDocumentCandidate> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(candidates.subList(0, Math.min(Math.max(topN, 1), candidates.size())));
    }
}
