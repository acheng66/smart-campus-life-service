package com.smartcampus.service.agent.memory;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** PostgreSQL/Redis 中统一使用的会话消息结构。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentStoredMessage {
    private String id;
    private String traceId;
    private String role;
    private String content;
    private LocalDateTime createdAt;
}
