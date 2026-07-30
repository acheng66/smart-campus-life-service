package com.smartcampus.dto;

import lombok.Data;

/** 用户向校园助手发送的消息；坐标由前端在获得授权后可选传入。 */
@Data
public class AgentChatRequest {
    /** 会话标识；前端首次为空，由服务端签发并在后续轮次带回。 */
    private String conversationId;
    /** 用户自然语言问题，Service 层会校验非空和最大长度。 */
    private String message;
    /** 浏览器在用户授权定位后传入的经度；为空时不参与距离排序。 */
    private Double x;
    /** 浏览器在用户授权定位后传入的纬度；为空时不参与距离排序。 */
    private Double y;
}
