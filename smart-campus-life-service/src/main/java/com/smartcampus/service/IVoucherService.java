package com.smartcampus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartcampus.dto.Result;
import com.smartcampus.entity.Voucher;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    void addSeckillVoucher(Voucher voucher);
}
