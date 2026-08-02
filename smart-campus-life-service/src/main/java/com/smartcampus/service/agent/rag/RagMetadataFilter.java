package com.smartcampus.service.agent.rag;

import lombok.Data;

/** 从用户问题中识别出的结构化检索边界。 */
@Data
public class RagMetadataFilter {
    /** shop 或 voucher。 */
    private String kind;
    private Long shopId;
    /** NORMAL 或 SECKILL。 */
    private String voucherType;
    /** ACTIVE、OFFLINE 或 EXPIRED。 */
    private String status;
}
