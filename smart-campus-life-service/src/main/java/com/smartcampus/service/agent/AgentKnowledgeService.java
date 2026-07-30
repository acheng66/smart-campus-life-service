package com.smartcampus.service.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.annotation.Resource;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.smartcampus.entity.SeckillVoucher;
import com.smartcampus.entity.Shop;
import com.smartcampus.entity.Voucher;
import com.smartcampus.service.shop.IShopService;
import com.smartcampus.service.voucher.ISeckillVoucherService;
import com.smartcampus.service.voucher.IVoucherService;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 商户介绍、券规则和活动文案的 pgvector 检索服务。
 *
 * <p>该服务只处理适合语义检索的静态或低频文本；库存、领取资格、订单状态等实时事实必须通过
 * {@link CampusAgentTools} 查询 MySQL/Redis，不能使用向量检索结果做业务判断。</p>
 */
@Slf4j
@Service
@ConditionalOnBean(name = "campusAgentVectorStore")
public class AgentKnowledgeService {
    @Resource(name = "campusAgentVectorStore")
    private VectorStore vectorStore;
    @Resource(name = "agentRagJdbcTemplate")
    private JdbcTemplate agentRagJdbcTemplate;
    @Resource
    private IShopService shopService;
    @Resource
    private IVoucherService voucherService;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Value("${agent.rag.rebuild-on-startup:true}")
    private boolean rebuildOnStartup;
    @Value("${agent.rag.schema-name:public}")
    private String schemaName;
    @Value("${agent.rag.table-name:agent_knowledge_vector}")
    private String tableName;

    /**
     * pgvector 首次启用时建立索引；可通过 rebuild-on-startup 配置关闭，避免每次重启重新产生 Embedding 调用。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeKnowledgeOnStartup() {
        if (rebuildOnStartup) {
            rebuildKnowledge();
        }
    }

    /**
     * 定时把 MySQL 中的商户、优惠券和秒杀活动文本同步到 pgvector。
     * 默认每天凌晨执行，保证下架或更新后的规则不会长期停留在知识库中。
     */
    @Scheduled(cron = "${agent.rag.rebuild-cron:0 30 3 * * ?}")
    public void rebuildKnowledge() {
        List<Document> documents = new ArrayList<>();
        Map<Long, Shop> shops = shopService.list().stream().collect(Collectors.toMap(Shop::getId, shop -> shop));
        for (Shop shop : shops.values()) {
            String id = "shop-" + shop.getId();
            documents.add(new Document(id, "商户：" + shop.getName() + "。地址：" + defaultText(shop.getAddress())
                    + "。区域：" + defaultText(shop.getArea()) + "。营业时间：" + defaultText(shop.getOpenHours()),
                    Map.of("kind", "shop", "shopId", shop.getId())));
        }
        Map<Long, SeckillVoucher> seckill = seckillVoucherService.list().stream()
                .collect(Collectors.toMap(SeckillVoucher::getVoucherId, item -> item));
        for (Voucher voucher : voucherService.list()) {
            String id = "voucher-" + voucher.getId();
            Shop shop = shops.get(voucher.getShopId());
            String text = "优惠券：" + defaultText(voucher.getTitle()) + "。商户：" + (shop == null ? "校园商户" : shop.getName())
                    + "。使用规则：" + defaultText(voucher.getRules()) + "。说明：" + defaultText(voucher.getSubTitle());
            SeckillVoucher activity = seckill.get(voucher.getId());
            if (activity != null) {
                text += "。秒杀活动时间：" + activity.getBeginTime() + " 至 " + activity.getEndTime();
            }
            documents.add(new Document(id, text, Map.of("kind", "voucher", "voucherId", voucher.getId(), "shopId", voucher.getShopId())));
        }
        if (!documents.isEmpty()) {
            // RAG 表只保存本服务生成的知识文本。完整替换可删除已下架店铺或券，且不影响 MySQL 业务数据。
            agentRagJdbcTemplate.execute("DELETE FROM " + schemaName + "." + tableName);
            vectorStore.add(documents);
        }
        log.info("Agent RAG 知识库已重建，文档数={}", documents.size());
    }

    /**
     * 检索与用户问题最相近的三段知识文本，并限制每段长度后放入模型 Prompt。
     * 检索失败时返回“无”，让对话继续使用真实工具查询，而不是因为 RAG 故障中断 Agent。
     */
    public String retrieve(String question) {
        try {
            List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder().query(question).topK(3)
                    .similarityThreshold(0.55D).build());
            if (documents == null || documents.isEmpty()) {
                return "无";
            }
            return documents.stream().map(document -> StrUtil.subWithLength(document.getText(), 0, 360))
                    .collect(Collectors.joining("\n---\n"));
        } catch (Exception e) {
            log.warn("Agent RAG 检索失败，将忽略知识库上下文", e);
            return "无";
        }
    }

    /** 统一替换空字段，避免把 null 直接写入 Embedding 文本。 */
    private String defaultText(String value) {
        return StrUtil.isBlank(value) ? "暂无" : value;
    }
}
