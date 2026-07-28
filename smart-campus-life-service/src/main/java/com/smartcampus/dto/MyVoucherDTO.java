package com.smartcampus.dto;

import java.time.LocalDateTime;

import lombok.Data;

/** 当前用户优惠券列表的展示数据，聚合领取记录、券和店铺信息。 */
@Data
public class MyVoucherDTO {
    private Long orderId;
    private Long voucherId;
    private Long shopId;
    private String shopName;
    private String title;
    private String subTitle;
    private Long payValue;
    private Long actualValue;
    /** 0 普通券，1 秒杀券。 */
    private Integer type;
    /** 优惠券状态：1 上架，2 下架，3 已结束。 */
    private Integer voucherStatus;
    /** 领取订单状态：1 未支付，2 已支付，3 已核销等。 */
    private Integer orderStatus;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private LocalDateTime receivedAt;
}
