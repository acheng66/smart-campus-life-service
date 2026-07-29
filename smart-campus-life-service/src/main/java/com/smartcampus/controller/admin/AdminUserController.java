package com.smartcampus.controller.admin;

import jakarta.annotation.Resource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartcampus.dto.Result;
import com.smartcampus.service.user.IUserService;

/** 平台管理员的用户与角色管理接口。 */
@RestController
@RequestMapping("/admin/users")
public class AdminUserController {
    @Resource
    private IUserService userService;

    /** 按手机号筛选用户，分页返回。 */
    @GetMapping
    public Result queryUsers(@RequestParam(value = "phone", required = false) String phone,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return userService.queryUsersForManage(phone, current);
    }

    /** 设置用户角色：0 学生，1 管理员。商家身份由店铺归属接口自动授予。 */
    @PutMapping("/{id}/role/{role}")
    public Result changeRole(@PathVariable("id") Long userId, @PathVariable Integer role) {
        return userService.changeUserRole(userId, role);
    }

}
