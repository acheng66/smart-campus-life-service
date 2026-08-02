package com.smartcampus.service.agent.evaluation;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 一条 Golden Case 的可执行验收规则。
 *
 * <p>规则只校验可观测事实，不用另一个大模型“凭感觉打分”，因此适合在本地和 CI 中稳定回归。</p>
 */
@Data
public class AgentEvaluationExpectation {
    /** 期望执行模式，通常为 AI；为空时不限制。 */
    private String expectedMode;
    /** 必须全部真实执行的工具方法名。 */
    private List<String> requiredTools = new ArrayList<>();
    /** 本轮禁止执行的工具方法名。 */
    private List<String> forbiddenTools = new ArrayList<>();
    /** 不影响业务正确性，但不应在该用例中重复执行的工具。 */
    private List<String> warningTools = new ArrayList<>();
    /** 期望的服务端主要意图；为空时不限制。 */
    private String expectedIntent;
    /** 期望最终展示类型：NONE、SHOP、VOUCHER 或 MY_VOUCHER。 */
    private String expectedPresentationType;
    /** 是否要求 pgvector 至少命中一段知识。 */
    private Boolean ragRequired;
    /** Recall@K 评测所需的稳定业务文档 ID，例如 voucher-1、shop-4。 */
    private List<String> expectedRagDocumentIds = new ArrayList<>();
    /** 最低召回率，未填写时只要配置了期望文档就默认要求 1.0。 */
    private Double minRagRecallAtK;
    /** 是否要求至少返回一张可信业务卡片。 */
    private Boolean requireCards;
    /** 允许出现的卡片类型；为空时不限制。 */
    private List<String> allowedCardTypes = new ArrayList<>();
    /** 最大卡片数；用于防止候选卡片全部泄漏到最终推荐。 */
    private Integer maxCards;
    /** 店铺卡片标题是否必须逐一出现在自然语言回答中。 */
    private Boolean requireShopCardsMentionedInAnswer;
    /** 是否禁止 Markdown 标记，避免前端显示 #、表格和加粗符号。 */
    private Boolean noMarkdown;
    /** 回答最大字符数。 */
    private Integer maxAnswerLength;
    /** 回答中绝对不能出现的高风险承诺或错误事实。 */
    private List<String> forbiddenAnswerPhrases = new ArrayList<>();
}
