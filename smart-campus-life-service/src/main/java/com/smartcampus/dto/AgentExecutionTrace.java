package com.smartcampus.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 单轮 Agent 的内部执行轨迹。
 *
 * <p>该对象只供自动评测和服务端排障使用，不会序列化给普通前端。它记录模型是否真正参与、
 * 实际调用过哪些工具、RAG 命中文档数、是否发生降级以及端到端耗时。</p>
 */
@Data
public class AgentExecutionTrace {
    /** AI、DETERMINISTIC、FALLBACK 或 FINAL_FALLBACK。 */
    private String mode;
    /** 服务端在模型调用前确定的主要业务意图。 */
    private String intent;
    /** 最终展示类型：NONE、SHOP、VOUCHER 或 MY_VOUCHER。 */
    private String presentationType;
    /** 按实际执行顺序记录的工具方法名，允许同一工具被多次调用。 */
    private List<String> toolCalls = new ArrayList<>();
    /** searchShops 本轮返回的真实候选店名，用于评测回答与最终卡片的双向一致性。 */
    private List<String> candidateShopTitles = new ArrayList<>();
    /** 本轮向量检索通过相似度阈值的文档数量；RAG 未启用或未命中时为 0。 */
    private int ragHitCount;
    /** Hybrid RAG 的 pgvector 语义召回数量。 */
    private int ragVectorHitCount;
    /** Hybrid RAG 的 PostgreSQL 关键词召回数量。 */
    private int ragKeywordHitCount;
    /** 最终进入上下文的稳定业务文档 ID，用于 Recall@K 评测。 */
    private List<String> ragDocumentIds = new ArrayList<>();
    /** 是否实际经过外部 Reranker 精排。 */
    private boolean ragReranked;
    /** 用户原始问题与 Query Rewrite 结果，用于判断改写是否发生语义漂移。 */
    private String ragOriginalQuery;
    private String ragRewrittenQuery;
    /** 最终实际应用的 Metadata Filter，以及是否发生过 kind 放宽。 */
    private String ragMetadataFilter;
    private boolean ragFilterRelaxed;
    /** 模型、工具或增强链路异常后是否进入过确定性降级。 */
    private boolean fallback;
    /** 从 Agent Service 收到请求到构造响应的耗时，单位毫秒。 */
    private long durationMs;
}
