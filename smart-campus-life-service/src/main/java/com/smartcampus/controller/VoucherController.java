package com.smartcampus.controller;


import javax.annotation.Resource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartcampus.dto.Result;
import com.smartcampus.entity.Voucher;
import com.smartcampus.service.IVoucherService;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    /**
     * 新增普通券
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    public Result addVoucher(@RequestBody Voucher voucher) {
        return voucherService.addVoucher(voucher);
    }

    /**
     * 新增秒杀券
     * @param voucher 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("seckill")
    public Result addSeckillVoucher(@RequestBody Voucher voucher) {
        return voucherService.addSeckillVoucher(voucher);
    }

    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }

    /** 更新券的基础信息；券类型不可通过该接口变更。 */
    @PutMapping("/{id}")
    public Result updateVoucher(@PathVariable("id") Long voucherId, @RequestBody Voucher voucher) {
        return voucherService.updateVoucher(voucherId, voucher);
    }

    /**
     * 更新上下架状态：1 上架、2 下架、3 过期。
     * 秒杀券下架或过期后会同步移除 Redis 库存，阻止绕过前端的抢购请求。
     */
    @PutMapping("/{id}/status/{status}")
    public Result changeVoucherStatus(@PathVariable("id") Long voucherId,
            @PathVariable Integer status) {
        return voucherService.changeVoucherStatus(voucherId, status);
    }

    /** 物理删除；已存在领取记录的券不可删除，只能下架。 */
    @DeleteMapping("/{id}")
    public Result deleteVoucher(@PathVariable("id") Long voucherId) {
        return voucherService.deleteVoucher(voucherId);
    }
}
