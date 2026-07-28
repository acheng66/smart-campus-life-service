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

    /**
     * 领取普通券（不限量，不走秒杀 Redis/Lua 流程）。
     *
     * @param voucherId 普通券 ID
     * @return 创建成功时返回领取记录 ID
     */
    Result receiveVoucher(Long voucherId);

    /** 查询当前登录用户的领取记录。 */
    Result queryMyVouchers();

    void createVoucherOrder(VoucherOrder voucherOrder);
}
