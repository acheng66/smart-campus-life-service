package com.smartcampus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartcampus.dto.Result;
import com.smartcampus.entity.Shop;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IShopService extends IService<Shop> {
    /**
     * 根据id查询商铺信息
     * @param id
     * @return
     */
    Result queryShopById(Long id);

    /**
     * 更新商铺信息
     * @param shop
     * @return
     */
    Result update(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
