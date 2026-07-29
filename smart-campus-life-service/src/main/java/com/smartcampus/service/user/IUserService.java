package com.smartcampus.service.user;

import jakarta.servlet.http.HttpSession;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartcampus.dto.LoginFormDTO;
import com.smartcampus.dto.Result;
import com.smartcampus.entity.User;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IUserService extends IService<User> {
    /**
     * 发送手机验证码
     * @param phone
     * @param session
     * @return
     */
    Result sendCode(String phone, HttpSession session);

    /**
     * 登录功能
     * @param loginForm
     * @param session
     * @return
     */
    Result login(LoginFormDTO loginForm, HttpSession session);

    /** 删除当前登录令牌。 */
    Result logout(String token);

    /** 管理员分页查询用户。 */
    Result queryUsersForManage(String phone, Integer current);

    /** 管理员设置用户角色。 */
    Result changeUserRole(Long userId, Integer role);

    Result sign();

    Result signCount();
}
