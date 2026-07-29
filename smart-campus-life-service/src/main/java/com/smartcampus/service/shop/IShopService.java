package com.smartcampus.service.shop;

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

    /** 查询当前管理员或商家有权管理的店铺。 */
    Result queryManageableShops();

    /** 管理员新增店铺；可在请求体的 ownerId 中同时指定归属商家。 */
    Result createManagedShop(Shop shop);

    /** 管理员将店铺归属给用户，并同步将该用户设为商家。 */
    Result assignShopOwner(Long shopId, Long userId);

    /** 管理员解除店铺与商家的归属关系。 */
    Result clearShopOwner(Long shopId);
}
