package com.smartcampus.service.agent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ChineseSearchAnalyzerTest {
    private final ChineseSearchAnalyzer analyzer = new ChineseSearchAnalyzer();

    @Test
    void shouldKeepTitleAndGenerateChineseNgrams() {
        String tokens = analyzer.analyzeTitle("北苑烤肉饭");

        assertThat(tokens).contains("北苑烤肉饭");
        assertThat(tokens).contains("北苑");
        assertThat(tokens).contains("烤肉");
        assertThat(tokens).contains("烤肉饭");
    }

    @Test
    void shouldExtractDiscountAndExpandVoucherSynonym() {
        String query = analyzer.toTsQuery("有没有满100减50的代金券", List.of());

        assertThat(query).contains("'满100减50'");
        assertThat(query).contains("'满减'");
        assertThat(query).contains("'代金券'");
        assertThat(query).contains("'优惠券'");
        assertThat(query).contains(" | ");
    }

    @Test
    void shouldExpandCampusBusinessAliasesWithoutAddingFacts() {
        String query = analyzer.toTsQuery("这家餐厅几点关门", List.of());

        assertThat(query).contains("'营业时间'");
        assertThat(query).contains("'店铺'");
        assertThat(query).doesNotContain("库存");
    }

    @Test
    void fuzzyTermsShouldPreferLongerDiscriminativeTerms() {
        List<String> terms = analyzer.fuzzyTerms("北苑烤肉饭有什么优惠券", List.of());

        assertThat(terms).hasSizeLessThanOrEqualTo(6);
        assertThat(terms).contains("优惠券");
        assertThat(terms.get(0).codePointCount(0, terms.get(0).length()))
                .isGreaterThanOrEqualTo(terms.get(terms.size() - 1)
                        .codePointCount(0, terms.get(terms.size() - 1).length()));
    }
}
