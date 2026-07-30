package com.smartcampus.service.agent;

import com.smartcampus.dto.AgentActionConfirmRequest;
import com.smartcampus.dto.AgentChatRequest;
import com.smartcampus.dto.AgentChatResponse;
import com.smartcampus.dto.Result;

/**
 * 受控校园业务 Agent。
 *
 * <p>模型层只能请求业务工具；所有真实读写和鉴权均在此服务完成。
 * {@link #chat(AgentChatRequest)} 只查询和生成建议，{@link #confirmAction(AgentActionConfirmRequest)}
 * 仅在用户点击确认后才进入既有领券业务链路。</p>
 */
public interface ICampusAgentService {
    Result chat(AgentChatRequest request);

    Result confirmAction(AgentActionConfirmRequest request);
}
