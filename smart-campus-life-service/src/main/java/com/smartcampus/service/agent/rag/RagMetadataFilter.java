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

    /** 创建副本，避免检索降级时修改最初推断出的严格条件。 */
    public RagMetadataFilter copy() {
        RagMetadataFilter copy = new RagMetadataFilter();
        copy.setKind(kind);
        copy.setShopId(shopId);
        copy.setVoucherType(voucherType);
        copy.setStatus(status);
        return copy;
    }

    /**
     * 只放宽文档类型，不放宽店铺、券类型和状态等用户明确约束。
     * indexVersion 不在本对象中，由检索服务始终强制附加。
     */
    public RagMetadataFilter withoutKind() {
        RagMetadataFilter relaxed = copy();
        relaxed.setKind(null);
        return relaxed;
    }
}
