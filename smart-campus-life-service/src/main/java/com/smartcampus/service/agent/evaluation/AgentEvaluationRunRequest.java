package com.smartcampus.service.agent.evaluation;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/** 管理员发起评测时可选择用例子集和重复次数。 */
@Data
public class AgentEvaluationRunRequest {
    /** 指定稳定用例 ID 时优先按 ID 运行。 */
    private List<String> caseIds = new ArrayList<>();
    /** 按级别筛选；caseIds 为空且未提供任何筛选条件时默认只运行 SMOKE。 */
    private List<String> levels = new ArrayList<>();
    /** 按分类筛选。 */
    private List<String> categories = new ArrayList<>();
    /** 按标签筛选，命中任一标签即可。 */
    private List<String> tags = new ArrayList<>();
    /** 每条用例重复次数，用于发现模型输出波动；默认 1。 */
    private Integer repeat = 1;
}
