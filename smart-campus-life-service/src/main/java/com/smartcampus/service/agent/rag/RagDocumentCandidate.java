package com.smartcampus.service.agent.rag;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;

/** Hybrid RAG 多路召回后的统一候选文档。 */
@Data
public class RagDocumentCandidate {
    private String physicalId;
    /** shop-1、voucher-10 等稳定业务文档 ID，不随索引版本变化。 */
    private String businessId;
    private String content;
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private double vectorScore;
    private double keywordScore;
    private double fusionScore;
    private Double rerankScore;
}
