package com.smartcampus.service.agent.rag;

import com.smartcampus.service.agent.AgentIntent;

import cn.hutool.core.util.StrUtil;

/**
 * RAG 文档类型过滤策略。
 *
 * <p>用户问题中的明确语义优先于服务端意图；同时出现店铺资料和优惠券目标时不设置 kind 硬过滤，
 * 让两类文档共同参与召回。其他结构化条件仍由后端校验，不能由模型直接指定业务 ID。</p>
 */
public final class RagFilterPolicy {
    private RagFilterPolicy() {
    }

    public static String resolveKind(AgentIntent intent, String question) {
        String text = StrUtil.blankToDefault(question, "").toLowerCase();
        boolean voucherTarget = containsAny(text, "优惠券", "券", "满减", "代金", "秒杀");
        boolean shopTarget = containsAny(text, "营业", "地址", "在哪", "商户", "店铺", "商铺");
        if (voucherTarget && shopTarget) {
            return null;
        }
        if (voucherTarget) {
            return "voucher";
        }
        if (shopTarget) {
            return "shop";
        }
        if (intent == null) {
            return null;
        }
        return switch (intent) {
            case SHOP_RECOMMENDATION -> "shop";
            case SHOP_VOUCHER_QUERY, MY_VOUCHER_QUERY, ELIGIBILITY_CHECK -> "voucher";
            case GENERAL -> null;
        };
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }
}
