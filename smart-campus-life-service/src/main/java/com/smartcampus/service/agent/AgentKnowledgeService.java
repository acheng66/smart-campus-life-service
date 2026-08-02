package com.smartcampus.service.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.annotation.Resource;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcampus.entity.SeckillVoucher;
import com.smartcampus.entity.Shop;
import com.smartcampus.entity.Voucher;
import com.smartcampus.service.agent.rag.AgentKnowledgeChangedEvent;
import com.smartcampus.service.agent.rag.AgentQueryRewriteService;
import com.smartcampus.service.agent.rag.RagDocumentCandidate;
import com.smartcampus.service.agent.rag.RagMetadataFilter;
import com.smartcampus.service.agent.rag.SiliconFlowReranker;
import com.smartcampus.service.shop.IShopService;
import com.smartcampus.service.voucher.ISeckillVoucherService;
import com.smartcampus.service.voucher.IVoucherService;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 面向校园商户与优惠券文本的 Hybrid RAG 服务。
 *
 * <p>检索链路为：Query Rewrite → Metadata Filter → pgvector 语义召回 + PostgreSQL 关键词召回
 * → RRF 融合 → 可选 SiliconFlow Reranker → Context Compression。库存、领取资格和订单状态
 * 仍必须通过 {@link CampusAgentTools} 查询实时业务数据。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "agent.rag", name = "enabled", havingValue = "true")
public class AgentKnowledgeService {
    private static final double RRF_K = 60D;
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    @Resource(name = "campusAgentVectorStore")
    private VectorStore vectorStore;
    @Resource(name = "agentRagJdbcTemplate")
    private JdbcTemplate jdbcTemplate;
    @Resource
    private IShopService shopService;
    @Resource
    private IVoucherService voucherService;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private ObjectMapper objectMapper;
    @Autowired(required = false)
    private AgentQueryRewriteService queryRewriteService;
    @Autowired(required = false)
    private SiliconFlowReranker reranker;

    @Value("${agent.rag.rebuild-on-startup:true}")
    private boolean rebuildOnStartup;
    @Value("${agent.rag.schema-name:public}")
    private String schemaName;
    @Value("${agent.rag.table-name:agent_knowledge_vector}")
    private String tableName;
    @Value("${agent.rag.vector-top-k:12}")
    private int vectorTopK;
    @Value("${agent.rag.keyword-top-k:12}")
    private int keywordTopK;
    @Value("${agent.rag.final-top-k:4}")
    private int finalTopK;
    @Value("${agent.rag.similarity-threshold:0.45}")
    private double similarityThreshold;
    @Value("${agent.rag.max-context-chars:1400}")
    private int maxContextChars;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeKnowledgeOnStartup() {
        try {
            initializeHybridSchema();
            if (rebuildOnStartup) {
                rebuildKnowledge();
            }
        } catch (Exception e) {
            log.error("Agent Hybrid RAG 初始化失败，本次运行降级为实时业务工具", e);
        }
    }

    /**
     * 版本化全量重建。
     *
     * <p>新版本使用独立物理文档 ID 写入；写入数量校验通过后，才用单条 UPSERT 原子切换活动版本。
     * 旧版本延迟清理，因此 Embedding 失败或进程中断不会清空线上知识。</p>
     */
    @Scheduled(cron = "${agent.rag.rebuild-cron:0 30 3 * * ?}")
    public void rebuildKnowledge() {
        initializeHybridSchema();
        String version = UUID.randomUUID().toString().replace("-", "");
        List<Document> documents = buildAllDocuments(version);
        if (documents.isEmpty()) {
            log.warn("Agent RAG 本次重建没有业务文档，拒绝切换活动版本");
            return;
        }
        vectorStore.add(documents);
        Integer written = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + qualifiedTable() + " WHERE metadata->>'indexVersion' = ?",
                Integer.class, version);
        if (written == null || written != documents.size()) {
            throw new IllegalStateException("RAG 新版本文档校验失败，期望=" + documents.size() + "，实际=" + written);
        }
        jdbcTemplate.update("INSERT INTO " + qualifiedStateTable()
                        + " (index_name, active_version, document_count, updated_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP) "
                        + "ON CONFLICT (index_name) DO UPDATE SET active_version=EXCLUDED.active_version, "
                        + "document_count=EXCLUDED.document_count, updated_at=CURRENT_TIMESTAMP",
                tableName, version, written);
        log.info("Agent Hybrid RAG 活动版本已原子切换，version={}, documents={}", version, written);
    }

    /** 延迟清理非活动版本，给切换前已开始的查询保留安全窗口。 */
    @Scheduled(cron = "${agent.rag.cleanup-cron:0 10 4 * * ?}")
    public void cleanupOldVersions() {
        String activeVersion = activeVersion();
        if (StrUtil.isBlank(activeVersion)) {
            return;
        }
        int deleted = jdbcTemplate.update("DELETE FROM " + qualifiedTable()
                + " WHERE metadata->>'indexVersion' IS NOT NULL AND metadata->>'indexVersion' <> ? "
                + "AND (metadata->>'indexedAt')::timestamptz < CURRENT_TIMESTAMP - INTERVAL '30 minutes'",
                activeVersion);
        if (deleted > 0) {
            log.info("Agent RAG 已清理旧版本文档，count={}", deleted);
        }
    }

    public String retrieve(String question) {
        return retrieveWithMetadata(question, "无").getContent();
    }

    public RetrievalResult retrieveWithMetadata(String question) {
        return retrieveWithMetadata(question, "无");
    }

    /** 执行完整 Hybrid RAG，并返回自动评测所需的召回元数据。 */
    public RetrievalResult retrieveWithMetadata(String question, String recentContext) {
        try {
            String rewritten = queryRewriteService == null ? question
                    : queryRewriteService.rewrite(question, recentContext);
            RagMetadataFilter filter = inferFilter(rewritten);
            String version = activeVersion();
            List<String> keywords = extractKeywords(rewritten, filter);
            List<RagDocumentCandidate> vectorCandidates = vectorRecall(rewritten, version, filter);
            List<RagDocumentCandidate> keywordCandidates = keywordRecall(rewritten, keywords, version, filter);
            List<RagDocumentCandidate> fused = reciprocalRankFusion(vectorCandidates, keywordCandidates);
            boolean reranked = reranker != null && reranker.isAvailable() && fused.size() > 1;
            List<RagDocumentCandidate> selected = reranker == null
                    ? limit(fused, finalTopK) : reranker.rerank(rewritten, fused, finalTopK);
            String content = compressContext(selected, keywords);
            List<String> documentIds = selected.stream().map(RagDocumentCandidate::getBusinessId)
                    .filter(StrUtil::isNotBlank).distinct().toList();
            log.debug("Hybrid RAG 完成, vector={}, keyword={}, final={}, filter={}",
                    vectorCandidates.size(), keywordCandidates.size(), selected.size(), filter);
            return new RetrievalResult(StrUtil.isBlank(content) ? "无" : content, selected.size(),
                    vectorCandidates.size(), keywordCandidates.size(), rewritten, documentIds, reranked);
        } catch (Exception e) {
            log.warn("Agent Hybrid RAG 检索失败，将忽略知识上下文", e);
            return new RetrievalResult("无", 0, 0, 0, question, Collections.emptyList(), false);
        }
    }

    /** 事务提交后的增量事件异步更新当前活动版本，不阻塞店铺或券管理接口。 */
    @Async("agentRagTaskExecutor")
    @EventListener
    public void handleKnowledgeChanged(AgentKnowledgeChangedEvent event) {
        if (event == null || event.businessId() == null || StrUtil.isBlank(event.kind())) {
            return;
        }
        try {
            String version = activeVersion();
            if (StrUtil.isBlank(version)) {
                log.debug("RAG 尚无活动版本，忽略增量事件 {}", event);
                return;
            }
            if ("shop".equals(event.kind())) {
                updateShopKnowledge(version, event.businessId(), event.deleted());
            } else if ("voucher".equals(event.kind())) {
                updateVoucherKnowledge(version, event.businessId(), event.deleted());
            }
        } catch (Exception e) {
            // 每日版本化全量重建会兜底修复本次增量失败。
            log.error("Agent RAG 增量更新失败，event={}", event, e);
        }
    }

    private List<Document> buildAllDocuments(String version) {
        Map<Long, Shop> shops = shopService.list().stream()
                .collect(Collectors.toMap(Shop::getId, shop -> shop));
        Map<Long, SeckillVoucher> seckill = seckillVoucherService.list().stream()
                .collect(Collectors.toMap(SeckillVoucher::getVoucherId, item -> item));
        List<Document> documents = new ArrayList<>();
        shops.values().forEach(shop -> documents.add(buildShopDocument(version, shop)));
        voucherService.list().forEach(voucher -> documents.add(
                buildVoucherDocument(version, voucher, shops.get(voucher.getShopId()), seckill.get(voucher.getId()))));
        return documents;
    }

    private Document buildShopDocument(String version, Shop shop) {
        String businessId = "shop-" + shop.getId();
        Map<String, Object> metadata = baseMetadata(version, businessId, "shop", shop.getUpdateTime());
        metadata.put("shopId", String.valueOf(shop.getId()));
        metadata.put("shopTypeId", String.valueOf(shop.getTypeId()));
        String text = "商户：" + defaultText(shop.getName()) + "。地址：" + defaultText(shop.getAddress())
                + "。区域：" + defaultText(shop.getArea()) + "。营业时间：" + defaultText(shop.getOpenHours());
        return new Document(physicalId(version, businessId), text, metadata);
    }

    private Document buildVoucherDocument(String version, Voucher voucher, Shop shop, SeckillVoucher activity) {
        String businessId = "voucher-" + voucher.getId();
        Map<String, Object> metadata = baseMetadata(version, businessId, "voucher", voucher.getUpdateTime());
        metadata.put("voucherId", String.valueOf(voucher.getId()));
        metadata.put("shopId", String.valueOf(voucher.getShopId()));
        metadata.put("voucherType", Integer.valueOf(1).equals(voucher.getType()) ? "SECKILL" : "NORMAL");
        metadata.put("status", voucherStatus(voucher.getStatus()));
        String text = "优惠券：" + defaultText(voucher.getTitle()) + "。商户："
                + (shop == null ? "校园商户" : shop.getName()) + "。使用规则：" + defaultText(voucher.getRules())
                + "。说明：" + defaultText(voucher.getSubTitle());
        if (activity != null) {
            text += "。秒杀活动时间：" + activity.getBeginTime() + " 至 " + activity.getEndTime();
        }
        return new Document(physicalId(version, businessId), text, metadata);
    }

    private Map<String, Object> baseMetadata(String version, String businessId, String kind, Object updatedAt) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("indexVersion", version);
        metadata.put("businessId", businessId);
        metadata.put("kind", kind);
        metadata.put("updatedAt", updatedAt == null ? "" : updatedAt.toString());
        metadata.put("indexedAt", Instant.now().toString());
        return metadata;
    }

    private List<RagDocumentCandidate> vectorRecall(String query, String version, RagMetadataFilter filter) {
        SearchRequest.Builder builder = SearchRequest.builder().query(query)
                .topK(Math.max(vectorTopK, finalTopK)).similarityThreshold(similarityThreshold);
        String expression = filterExpression(version, filter);
        if (StrUtil.isNotBlank(expression)) {
            builder.filterExpression(expression);
        }
        List<Document> documents = vectorStore.similaritySearch(builder.build());
        if (documents == null) {
            return Collections.emptyList();
        }
        List<RagDocumentCandidate> result = new ArrayList<>();
        for (Document document : documents) {
            RagDocumentCandidate candidate = fromDocument(document);
            candidate.setVectorScore(document.getScore() == null ? 0D : document.getScore());
            result.add(candidate);
        }
        return result;
    }

    private List<RagDocumentCandidate> keywordRecall(String query, List<String> keywords,
            String version, RagMetadataFilter filter) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT id, content, metadata::text, "
                + "ts_rank_cd(to_tsvector('simple', content), plainto_tsquery('simple', ?)) AS text_score "
                + "FROM ").append(qualifiedTable()).append(" WHERE 1=1");
        args.add(query);
        appendMetadataSql(sql, args, "indexVersion", version);
        appendMetadataSql(sql, args, "kind", filter.getKind());
        appendMetadataSql(sql, args, "shopId", filter.getShopId() == null ? null : String.valueOf(filter.getShopId()));
        appendMetadataSql(sql, args, "voucherType", filter.getVoucherType());
        appendMetadataSql(sql, args, "status", filter.getStatus());
        if (!keywords.isEmpty()) {
            sql.append(" AND (to_tsvector('simple', content) @@ plainto_tsquery('simple', ?) ");
            args.add(query);
            for (String keyword : keywords) {
                sql.append(" OR content ILIKE ?");
                args.add("%" + keyword + "%");
            }
            sql.append(")");
        }
        sql.append(" ORDER BY text_score DESC, id LIMIT ?");
        args.add(Math.max(keywordTopK, finalTopK));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            RagDocumentCandidate candidate = new RagDocumentCandidate();
            candidate.setPhysicalId(rs.getString("id"));
            candidate.setContent(rs.getString("content"));
            Map<String, Object> metadata = parseMetadata(rs.getString("metadata"));
            candidate.setMetadata(metadata);
            candidate.setBusinessId(String.valueOf(metadata.getOrDefault("businessId", rs.getString("id"))));
            double textScore = rs.getDouble("text_score");
            candidate.setKeywordScore(textScore > 0 ? textScore : keywordOverlap(candidate.getContent(), keywords));
            return candidate;
        }, args.toArray());
    }

    private List<RagDocumentCandidate> reciprocalRankFusion(List<RagDocumentCandidate> vector,
            List<RagDocumentCandidate> keyword) {
        Map<String, RagDocumentCandidate> merged = new LinkedHashMap<>();
        addRanked(merged, vector, true);
        addRanked(merged, keyword, false);
        List<RagDocumentCandidate> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparingDouble(RagDocumentCandidate::getFusionScore).reversed());
        return result;
    }

    private void addRanked(Map<String, RagDocumentCandidate> merged, List<RagDocumentCandidate> ranked, boolean vector) {
        for (int i = 0; i < ranked.size(); i++) {
            RagDocumentCandidate incoming = ranked.get(i);
            String key = StrUtil.blankToDefault(incoming.getBusinessId(), incoming.getPhysicalId());
            RagDocumentCandidate target = merged.computeIfAbsent(key, ignored -> incoming);
            target.setFusionScore(target.getFusionScore() + 1D / (RRF_K + i + 1));
            if (vector) {
                target.setVectorScore(incoming.getVectorScore());
            } else {
                target.setKeywordScore(incoming.getKeywordScore());
            }
        }
    }

    private String compressContext(List<RagDocumentCandidate> documents, List<String> keywords) {
        StringBuilder context = new StringBuilder();
        for (RagDocumentCandidate document : documents) {
            List<String> sentences = List.of(document.getContent().split("(?<=[。；;\\n])"));
            // 首句通常包含店名或券名，必须保留；再追加与问题关键词相关的规则、时间或地址句。
            Set<String> selectedSet = new LinkedHashSet<>();
            if (!sentences.isEmpty()) {
                selectedSet.add(sentences.get(0));
            }
            sentences.stream().filter(sentence -> keywords.stream().anyMatch(sentence::contains))
                    .limit(3).forEach(selectedSet::add);
            if (selectedSet.size() == 1 && sentences.size() > 1) {
                selectedSet.add(sentences.get(1));
            }
            List<String> selected = new ArrayList<>(selectedSet);
            String compressed = String.join("", selected).trim();
            if (compressed.isEmpty()) {
                continue;
            }
            if (context.length() > 0) {
                context.append("\n---\n");
            }
            int remaining = Math.max(maxContextChars - context.length(), 0);
            if (remaining == 0) {
                break;
            }
            context.append(StrUtil.subWithLength(compressed, 0, remaining));
        }
        return context.toString();
    }

    private RagMetadataFilter inferFilter(String question) {
        RagMetadataFilter filter = new RagMetadataFilter();
        String text = StrUtil.blankToDefault(question, "").toLowerCase();
        for (Shop shop : shopService.list()) {
            if (StrUtil.isNotBlank(shop.getName()) && text.contains(shop.getName().toLowerCase())) {
                filter.setShopId(shop.getId());
                break;
            }
        }
        if (containsAny(text, "优惠券", "券", "满减", "代金", "秒杀")) {
            filter.setKind("voucher");
        } else if (containsAny(text, "营业", "地址", "在哪", "商户", "店铺", "商铺")) {
            filter.setKind("shop");
        }
        if (text.contains("秒杀")) {
            filter.setVoucherType("SECKILL");
        } else if (containsAny(text, "普通券", "代金券")) {
            filter.setVoucherType("NORMAL");
        }
        if (containsAny(text, "可领", "上架", "现在有什么券", "当前有什么券")) {
            filter.setStatus("ACTIVE");
        }
        return filter;
    }

    private List<String> extractKeywords(String question, RagMetadataFilter filter) {
        Set<String> keywords = new LinkedHashSet<>();
        if (filter.getShopId() != null) {
            Shop shop = shopService.getById(filter.getShopId());
            if (shop != null && StrUtil.isNotBlank(shop.getName())) {
                keywords.add(shop.getName());
            }
        }
        String text = StrUtil.blankToDefault(question, "");
        for (String keyword : List.of("优惠券", "使用规则", "规则", "营业时间", "营业", "地址",
                "区域", "秒杀", "代金券", "满减")) {
            if (text.contains(keyword)) {
                keywords.add(keyword);
            }
        }
        if (keywords.isEmpty()) {
            String normalized = text.replaceAll("[，。！？、,.!?\\s]", "");
            if (normalized.length() >= 2) {
                keywords.add(StrUtil.subWithLength(normalized, 0, 24));
            }
        }
        return keywords.stream().limit(6).toList();
    }

    private String filterExpression(String version, RagMetadataFilter filter) {
        List<String> parts = new ArrayList<>();
        addFilter(parts, "indexVersion", version);
        addFilter(parts, "kind", filter.getKind());
        addFilter(parts, "shopId", filter.getShopId() == null ? null : String.valueOf(filter.getShopId()));
        addFilter(parts, "voucherType", filter.getVoucherType());
        addFilter(parts, "status", filter.getStatus());
        return String.join(" && ", parts);
    }

    private void addFilter(List<String> parts, String key, String value) {
        if (StrUtil.isNotBlank(value)) {
            parts.add(key + " == '" + value.replace("'", "''") + "'");
        }
    }

    private void appendMetadataSql(StringBuilder sql, List<Object> args, String key, String value) {
        if (StrUtil.isNotBlank(value)) {
            sql.append(" AND metadata->>'").append(key).append("' = ?");
            args.add(value);
        }
    }

    private void initializeHybridSchema() {
        validateIdentifier(schemaName);
        validateIdentifier(tableName);
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + qualifiedStateTable()
                + " (index_name VARCHAR(128) PRIMARY KEY, active_version VARCHAR(64) NOT NULL, "
                + "document_count INTEGER NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + tableName + "_fts_idx ON " + qualifiedTable()
                + " USING GIN (to_tsvector('simple', content))");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + tableName + "_version_idx ON " + qualifiedTable()
                + " ((metadata->>'indexVersion'))");
    }

    private String activeVersion() {
        List<String> versions = jdbcTemplate.query("SELECT active_version FROM " + qualifiedStateTable()
                + " WHERE index_name = ?", (rs, rowNum) -> rs.getString(1), tableName);
        return versions.isEmpty() ? null : versions.get(0);
    }

    private void updateShopKnowledge(String version, Long shopId, boolean deleted) {
        String businessId = "shop-" + shopId;
        deletePhysical(version, businessId);
        Shop shop = deleted ? null : shopService.getById(shopId);
        if (shop != null) {
            vectorStore.add(List.of(buildShopDocument(version, shop)));
        }
        // 券文档包含店名，店铺资料变化时同步刷新该店全部券文档。
        for (Voucher voucher : voucherService.query().eq("shop_id", shopId).list()) {
            updateVoucherKnowledge(version, voucher.getId(), deleted);
        }
    }

    private void updateVoucherKnowledge(String version, Long voucherId, boolean deleted) {
        String businessId = "voucher-" + voucherId;
        deletePhysical(version, businessId);
        Voucher voucher = deleted ? null : voucherService.getById(voucherId);
        if (voucher != null) {
            vectorStore.add(List.of(buildVoucherDocument(version, voucher,
                    shopService.getById(voucher.getShopId()), seckillVoucherService.getById(voucherId))));
        }
    }

    private void deletePhysical(String version, String businessId) {
        jdbcTemplate.update("DELETE FROM " + qualifiedTable() + " WHERE id = ?",
                physicalId(version, businessId));
    }

    private RagDocumentCandidate fromDocument(Document document) {
        RagDocumentCandidate candidate = new RagDocumentCandidate();
        candidate.setPhysicalId(document.getId());
        candidate.setContent(document.getText());
        candidate.setMetadata(new LinkedHashMap<>(document.getMetadata()));
        candidate.setBusinessId(String.valueOf(document.getMetadata()
                .getOrDefault("businessId", document.getId())));
        return candidate;
    }

    private Map<String, Object> parseMetadata(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private double keywordOverlap(String content, List<String> keywords) {
        if (StrUtil.isBlank(content) || keywords.isEmpty()) {
            return 0D;
        }
        long hits = keywords.stream().filter(content::contains).count();
        return hits / (double) keywords.size();
    }

    private List<RagDocumentCandidate> limit(List<RagDocumentCandidate> candidates, int size) {
        return candidates.isEmpty() ? new ArrayList<>()
                : new ArrayList<>(candidates.subList(0, Math.min(Math.max(size, 1), candidates.size())));
    }

    private String physicalId(String version, String businessId) {
        return version + "-" + businessId;
    }

    private String voucherStatus(Integer status) {
        if (Integer.valueOf(1).equals(status)) {
            return "ACTIVE";
        }
        if (Integer.valueOf(3).equals(status)) {
            return "EXPIRED";
        }
        return "OFFLINE";
    }

    private String qualifiedTable() {
        return schemaName + "." + tableName;
    }

    private String qualifiedStateTable() {
        return schemaName + ".agent_rag_index_state";
    }

    private void validateIdentifier(String value) {
        if (value == null || !SQL_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("RAG SQL 标识符不合法: " + value);
        }
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private String defaultText(String value) {
        return StrUtil.isBlank(value) ? "暂无" : value;
    }

    /** RAG 上下文及多阶段召回元数据，供工作流追踪与 Recall@K 评测使用。 */
    public static final class RetrievalResult {
        private final String content;
        private final int hitCount;
        private final int vectorHitCount;
        private final int keywordHitCount;
        private final String rewrittenQuery;
        private final List<String> documentIds;
        private final boolean reranked;

        public RetrievalResult(String content, int hitCount) {
            this(content, hitCount, hitCount, 0, null, Collections.emptyList(), false);
        }

        public RetrievalResult(String content, int hitCount, int vectorHitCount, int keywordHitCount,
                String rewrittenQuery, List<String> documentIds, boolean reranked) {
            this.content = content;
            this.hitCount = hitCount;
            this.vectorHitCount = vectorHitCount;
            this.keywordHitCount = keywordHitCount;
            this.rewrittenQuery = rewrittenQuery;
            this.documentIds = documentIds == null ? Collections.emptyList() : new ArrayList<>(documentIds);
            this.reranked = reranked;
        }

        public String getContent() { return content; }
        public int getHitCount() { return hitCount; }
        public int getVectorHitCount() { return vectorHitCount; }
        public int getKeywordHitCount() { return keywordHitCount; }
        public String getRewrittenQuery() { return rewrittenQuery; }
        public List<String> getDocumentIds() { return new ArrayList<>(documentIds); }
        public boolean isReranked() { return reranked; }
    }
}
