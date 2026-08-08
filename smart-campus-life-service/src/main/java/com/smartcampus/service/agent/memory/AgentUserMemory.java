package com.smartcampus.service.agent.memory;

import java.time.LocalDateTime;

import lombok.Data;

/** 经过明确规则验证后才激活的结构化长期偏好，不保存库存、订单等实时业务状态。 */
@Data
public class AgentUserMemory {
    private String category;
    private String memoryKey;
    private String value;
    private String scope;
    private LocalDateTime expiresAt;
    private LocalDateTime updatedAt;
}
