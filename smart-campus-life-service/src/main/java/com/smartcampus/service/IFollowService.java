package com.smartcampus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartcampus.dto.Result;
import com.smartcampus.entity.Follow;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IFollowService extends IService<Follow> {

    Result follow(Long id, Boolean isFollow);

    Result isFollow(Long id);

    Result common(Long id);
}
