package com.smartcampus.service.shop;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartcampus.dto.Result;
import com.smartcampus.entity.ShopType;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IShopTypeService extends IService<ShopType> {
    /**
     * 查询商铺类型列表
     * @return
     */
    Result queryShopTypeList();


}
