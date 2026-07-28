package com.smartcampus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartcampus.dto.Result;
import com.smartcampus.entity.VoucherOrder;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {
    /**
     * 秒杀优惠券
     * @param voucherId
     * @return
     */
    Result seckillVoucher(Long voucherId);

    void createVoucherOrder(VoucherOrder voucherOrder);
}
