package com.smartcampus.controller;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartcampus.dto.Result;
import com.smartcampus.entity.Shop;
import com.smartcampus.service.IShopService;

/** 平台管理员的店铺创建、编辑和商家归属管理接口。 */
@RestController
@RequestMapping("/admin/shops")
public class AdminShopController {
    @Resource
    private IShopService shopService;

    /** 新增店铺；请求体可选 ownerId，填写后会同时把该用户设为商家。 */
    @PostMapping
    public Result create(@RequestBody Shop shop) {
        return shopService.createManagedShop(shop);
    }

    /** 编辑店铺基础资料，店铺归属请使用独立的 owner 接口。 */
    @PutMapping("/{id}")
    public Result update(@PathVariable("id") Long shopId, @RequestBody Shop shop) {
        shop.setId(shopId);
        return shopService.update(shop);
    }

    /** 将店铺分配给用户；系统会同步授予该用户商家角色。 */
    @PutMapping("/{id}/owner/{userId}")
    public Result assignOwner(@PathVariable("id") Long shopId, @PathVariable Long userId) {
        return shopService.assignShopOwner(shopId, userId);
    }

    /** 解除店铺商家归属，不删除店铺与用户。 */
    @DeleteMapping("/{id}/owner")
    public Result clearOwner(@PathVariable("id") Long shopId) {
        return shopService.clearShopOwner(shopId);
    }
}
