package com.smartcampus.service.impl;

import com.smartcampus.entity.UserInfo;
import com.smartcampus.mapper.UserInfoMapper;
import com.smartcampus.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
