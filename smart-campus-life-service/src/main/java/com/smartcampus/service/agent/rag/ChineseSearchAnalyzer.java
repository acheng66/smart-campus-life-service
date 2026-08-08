package com.smartcampus.service.agent.rag;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;

/**
 * 面向校园商户和优惠券的轻量中文检索分词器。
 *
 * <p>PostgreSQL 的 {@code simple} 文本搜索配置不会进行中文分词，因此在 Java 侧先把中文文本
 * 转换为空格分隔的检索词，再交给 {@code to_tsvector('simple', ...)} 建立 GIN 索引。实现同时保留
 * 短标题、领域词、数字金额以及中文二元/三元片段，兼顾精确名称和未知词召回。</p>
 *
 * <p>这不是通用自然语言分词器；领域词典只服务于校园店铺和优惠券知识。后续若文档规模和语言范围
 * 明显扩大，可替换为 Jieba/HanLP，调用方和 PostgreSQL 索引结构不需要改变。</p>
 */
@Component
public class ChineseSearchAnalyzer {
    private static final int MAX_INDEX_TOKENS = 160;
    private static final int MAX_QUERY_TOKENS = 32;
    private static final Pattern DISCOUNT_PATTERN = Pattern.compile("满\\d+(?:\\.\\d+)?减\\d+(?:\\.\\d+)?");

    /** 长词优先写在前面，避免只依赖二元切分丢失完整业务概念。 */
    private static final List<String> DOMAIN_TERMS = List.of(
            "校园轻食", "风味窗口", "使用规则", "营业时间", "秒杀活动",
            "优惠券", "代金券", "秒杀券", "满减券", "消费券", "领取资格",
            "店铺", "商户", "地址", "区域", "营业", "库存", "有效期", "上架", "下架", "过期", "满减", "秒杀");

    /** 查询扩展只增加等价业务概念，不增加库存、资格等事实。 */
    private static final Map<String, List<String>> QUERY_SYNONYMS = Map.ofEntries(
            Map.entry("代金券", List.of("优惠券")),
            Map.entry("消费券", List.of("优惠券")),
            Map.entry("券", List.of("优惠券")),
            Map.entry("几点关门", List.of("营业时间")),
            Map.entry("营业到几点", List.of("营业时间")),
            Map.entry("在哪", List.of("地址")),
            Map.entry("怎么走", List.of("地址")),
            Map.entry("餐厅", List.of("店铺", "商户")),
            Map.entry("饭店", List.of("店铺", "商户")),
            Map.entry("商家", List.of("店铺", "商户")));

    /** 生成写入文档 metadata 的标题检索词，完整名称拥有最高检索权重。 */
    public String analyzeTitle(String title) {
        Set<String> tokens = analyzeInternal(title, MAX_INDEX_TOKENS, false);
        String compact = compact(title);
        if (StrUtil.isNotBlank(compact) && compact.codePointCount(0, compact.length()) <= 32) {
            LinkedHashSet<String> withTitle = new LinkedHashSet<>();
            withTitle.add(compact);
            withTitle.addAll(tokens);
            tokens = withTitle;
        }
        return String.join(" ", tokens);
    }

    /** 生成写入文档 metadata 的正文检索词。 */
    public String analyzeDocument(String text) {
        return String.join(" ", analyzeInternal(text, MAX_INDEX_TOKENS, false));
    }

    /**
     * 将问题和规则关键词转换成安全的 OR tsquery。
     *
     * <p>粗召回阶段使用 OR 提升 Recall，精度由 Metadata Filter、RRF 和 Reranker 继续收敛。
     * 所有词都由本类只保留字母、数字和汉字，不接收用户提供的 tsquery 运算符。</p>
     */
    public String toTsQuery(String question, Collection<String> extraKeywords) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>(
                analyzeInternal(question, MAX_QUERY_TOKENS, true));
        if (extraKeywords != null) {
            for (String keyword : extraKeywords) {
                tokens.addAll(analyzeInternal(keyword, MAX_QUERY_TOKENS, true));
                if (tokens.size() >= MAX_QUERY_TOKENS) {
                    break;
                }
            }
        }
        return tokens.stream().limit(MAX_QUERY_TOKENS).map(this::quoteLexeme)
                .collect(java.util.stream.Collectors.joining(" | "));
    }

    /** 为 ILIKE/pg_trgm 兜底选择少量有区分度的词，避免为所有二元词生成 SQL 条件。 */
    public List<String> fuzzyTerms(String question, Collection<String> extraKeywords) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>(
                analyzeInternal(question, MAX_QUERY_TOKENS, true));
        if (extraKeywords != null) {
            for (String keyword : extraKeywords) {
                tokens.addAll(analyzeInternal(keyword, MAX_QUERY_TOKENS, true));
            }
        }
        List<String> ordered = new ArrayList<>(tokens);
        ordered.sort((left, right) -> Integer.compare(codePointLength(right), codePointLength(left)));
        return ordered.stream().filter(token -> codePointLength(token) >= 2).limit(6).toList();
    }

    private Set<String> analyzeInternal(String source, int maxTokens, boolean expandSynonyms) {
        String text = normalize(source);
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (StrUtil.isBlank(text)) {
            return tokens;
        }

        Matcher discountMatcher = DISCOUNT_PATTERN.matcher(text);
        while (discountMatcher.find() && tokens.size() < maxTokens) {
            tokens.add(discountMatcher.group());
            tokens.add("满减");
        }
        for (String term : DOMAIN_TERMS) {
            if (text.contains(term)) {
                tokens.add(term);
            }
        }
        if (expandSynonyms) {
            QUERY_SYNONYMS.forEach((alias, synonyms) -> {
                if (text.contains(alias)) {
                    tokens.addAll(synonyms);
                }
            });
        }

        StringBuilder run = new StringBuilder();
        Character.UnicodeScript currentScript = null;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            Character.UnicodeScript script = scriptOf(codePoint);
            if (script == null) {
                flushRun(tokens, run, currentScript, maxTokens);
                currentScript = null;
            } else {
                if (currentScript != null && currentScript != script) {
                    flushRun(tokens, run, currentScript, maxTokens);
                }
                currentScript = script;
                run.appendCodePoint(codePoint);
            }
            if (tokens.size() >= maxTokens) {
                break;
            }
            offset += Character.charCount(codePoint);
        }
        flushRun(tokens, run, currentScript, maxTokens);
        return tokens.stream().limit(maxTokens)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private void flushRun(Set<String> tokens, StringBuilder run, Character.UnicodeScript script, int maxTokens) {
        if (run.length() == 0 || tokens.size() >= maxTokens) {
            run.setLength(0);
            return;
        }
        String value = run.toString();
        run.setLength(0);
        if (script != Character.UnicodeScript.HAN) {
            if (value.length() >= 2) {
                tokens.add(value);
            }
            return;
        }

        int[] points = value.codePoints().toArray();
        if (points.length >= 2 && points.length <= 12) {
            tokens.add(value);
        }
        // 二元词保障未知店名的召回，三元词提升较长名称的区分度。
        addNgrams(tokens, points, 2, maxTokens);
        addNgrams(tokens, points, 3, maxTokens);
    }

    private void addNgrams(Set<String> tokens, int[] points, int size, int maxTokens) {
        for (int i = 0; i + size <= points.length && tokens.size() < maxTokens; i++) {
            tokens.add(new String(points, i, size));
        }
    }

    private Character.UnicodeScript scriptOf(int codePoint) {
        if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
            return Character.UnicodeScript.HAN;
        }
        if (Character.isLetterOrDigit(codePoint)) {
            return Character.UnicodeScript.LATIN;
        }
        return null;
    }

    private String normalize(String source) {
        return Normalizer.normalize(StrUtil.blankToDefault(source, ""), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private String compact(String source) {
        return normalize(source).replaceAll("[^\\p{IsHan}a-z0-9]", "");
    }

    private int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    /** PostgreSQL tsquery 的单引号词元；输入已经过字符白名单，仍使用标准单引号转义。 */
    private String quoteLexeme(String token) {
        return "'" + token.replace("'", "''") + "'";
    }
}
