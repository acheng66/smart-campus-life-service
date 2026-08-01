package com.smartcampus.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcampus.dto.AgentCard;
import com.smartcampus.service.agent.evaluation.AgentEvaluationCase;

class AgentIntentAndPresentationTest {
    private final AgentIntentResolver resolver = new AgentIntentResolver();

    @AfterEach
    void clearContext() {
        AgentToolCallContext.clear();
    }

    @Test
    void shouldResolveFourBusinessIntentsAndGeneralQuestion() {
        assertThat(resolver.resolve("帮我推荐附近评分高、有券的晚餐店"))
                .isEqualTo(AgentIntent.SHOP_RECOMMENDATION);
        assertThat(resolver.resolve("一食堂风味窗口现在有什么券可以领"))
                .isEqualTo(AgentIntent.SHOP_VOUCHER_QUERY);
        assertThat(resolver.resolve("我有哪些已经领取的优惠券"))
                .isEqualTo(AgentIntent.MY_VOUCHER_QUERY);
        assertThat(resolver.resolve("优惠券 10 我是否领取，现在还能不能领"))
                .isEqualTo(AgentIntent.ELIGIBILITY_CHECK);
        assertThat(resolver.resolve("我是不是领过优惠券 10"))
                .isEqualTo(AgentIntent.ELIGIBILITY_CHECK);
        assertThat(resolver.resolve("校园餐饮代金券可以兑换现金吗"))
                .isEqualTo(AgentIntent.GENERAL);
    }

    @Test
    void shopVoucherIntentShouldRejectShopCardsAndAllowVoucherCardsOnly() {
        AgentToolCallContext.begin(1L, null, null, AgentIntent.SHOP_VOUCHER_QUERY);
        AgentToolCallContext.Context context = AgentToolCallContext.current();

        assertThat(context.beginPresentation(AgentPresentationType.SHOP)).isFalse();
        assertThat(context.beginPresentation(AgentPresentationType.VOUCHER)).isTrue();

        AgentCard shop = new AgentCard();
        shop.setType("shop");
        shop.setShopId(1L);
        AgentCard voucher = new AgentCard();
        voucher.setType("voucher");
        voucher.setVoucherId(10L);

        assertThat(context.addCard(shop)).isFalse();
        assertThat(context.addCard(voucher)).isTrue();
        assertThat(AgentToolCallContext.cards()).containsExactly(voucher);
    }

    @Test
    void presentationTypeCanOnlyBeFinalizedOnce() {
        AgentToolCallContext.begin(1L, null, null, AgentIntent.SHOP_RECOMMENDATION);
        AgentToolCallContext.Context context = AgentToolCallContext.current();

        assertThat(context.beginPresentation(AgentPresentationType.SHOP)).isTrue();
        assertThat(context.beginPresentation(AgentPresentationType.SHOP)).isFalse();
        assertThat(context.beginPresentation(AgentPresentationType.VOUCHER)).isFalse();
    }

    @Test
    void everyGoldenCaseShouldMatchItsExpectedServerIntent() throws Exception {
        List<AgentEvaluationCase> cases;
        try (java.io.InputStream input = getClass().getResourceAsStream("/agent-evaluation/golden-dataset.json")) {
            assertThat(input).isNotNull();
            cases = new ObjectMapper().readValue(input, new TypeReference<List<AgentEvaluationCase>>() {
            });
        }

        assertThat(cases).allSatisfy(item -> assertThat(resolver.resolve(item.getMessage()).name())
                .as(item.getId() + " 的服务端意图")
                .isEqualTo(item.getExpectation().getExpectedIntent()));
    }
}
