package com.smartcampus.service.agent.evaluation;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/** Golden Dataset 中的一条固定评测问题及预期行为。 */
@Data
public class AgentEvaluationCase {
    /** 稳定用例 ID，运行接口可按 ID 选择子集。 */
    private String id;
    /** 报告中展示的中文名称。 */
    private String name;
    /** SMOKE、REGRESSION 或 SECURITY，用于控制运行成本。 */
    private String level;
    /** INTENT、TOOL_PLANNING、CARD、RAG、SECURITY 或 EDGE。 */
    private String category;
    /** 更细粒度的筛选标签，运行请求命中任一标签即可选择。 */
    private List<String> tags = new ArrayList<>();
    /** 显式为 false 时跳过；缺省视为启用。 */
    private Boolean enabled;
    /** 发送给真实 Agent 主链路的用户问题。 */
    private String message;
    /** 可选经度，用于验证位置推荐。 */
    private Double x;
    /** 可选纬度，用于验证位置推荐。 */
    private Double y;
    /** 规则评分器使用的期望。 */
    private AgentEvaluationExpectation expectation;
}
