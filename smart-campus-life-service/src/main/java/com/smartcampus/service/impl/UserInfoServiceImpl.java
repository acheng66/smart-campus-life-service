package com.smartcampus.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartcampus.entity.UserInfo;
import com.smartcampus.mapper.UserInfoMapper;
import com.smartcampus.service.IUserInfoService;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
