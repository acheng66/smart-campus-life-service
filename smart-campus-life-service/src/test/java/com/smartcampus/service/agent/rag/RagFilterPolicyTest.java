package com.smartcampus.service.agent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.smartcampus.service.agent.AgentIntent;

class RagFilterPolicyTest {

    @Test
    void explicitQuestionShouldOverrideServerIntent() {
        assertThat(RagFilterPolicy.resolveKind(AgentIntent.GENERAL, "一食堂有什么秒杀券"))
                .isEqualTo("voucher");
        assertThat(RagFilterPolicy.resolveKind(AgentIntent.GENERAL, "一食堂营业地址在哪里"))
                .isEqualTo("shop");
    }

    @Test
    void compoundQuestionShouldAllowShopAndVoucherDocuments() {
        assertThat(RagFilterPolicy.resolveKind(AgentIntent.SHOP_VOUCHER_QUERY,
                "一食堂在哪里，有什么优惠券"))
                .isNull();
    }

    @Test
    void serverIntentShouldProvideDefaultKindWithoutExplicitTarget() {
        assertThat(RagFilterPolicy.resolveKind(AgentIntent.SHOP_RECOMMENDATION, "帮我推荐一下"))
                .isEqualTo("shop");
        assertThat(RagFilterPolicy.resolveKind(AgentIntent.ELIGIBILITY_CHECK, "这个还能领吗"))
                .isEqualTo("voucher");
        assertThat(RagFilterPolicy.resolveKind(AgentIntent.GENERAL, "校园活动规则"))
                .isNull();
    }

    @Test
    void relaxationShouldOnlyRemoveKind() {
        RagMetadataFilter strict = new RagMetadataFilter();
        strict.setKind("voucher");
        strict.setShopId(1L);
        strict.setVoucherType("SECKILL");
        strict.setStatus("ACTIVE");

        RagMetadataFilter relaxed = strict.withoutKind();

        assertThat(relaxed.getKind()).isNull();
        assertThat(relaxed.getShopId()).isEqualTo(1L);
        assertThat(relaxed.getVoucherType()).isEqualTo("SECKILL");
        assertThat(relaxed.getStatus()).isEqualTo("ACTIVE");
        assertThat(strict.getKind()).isEqualTo("voucher");
    }
}
