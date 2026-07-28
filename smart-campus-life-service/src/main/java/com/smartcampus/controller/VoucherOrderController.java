package com.smartcampus.controller;


import javax.annotation.Resource;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartcampus.dto.Result;
import com.smartcampus.service.IVoucherOrderService;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /** 领取普通券，需登录。 */
    @PostMapping("receive/{id}")
    public Result receiveVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.receiveVoucher(voucherId);
    }

    /** 查询当前登录用户已领取的普通券、秒杀券记录，需登录。 */
    @GetMapping("my")
    public Result queryMyVouchers() {
        return voucherOrderService.queryMyVouchers();
    }
}
