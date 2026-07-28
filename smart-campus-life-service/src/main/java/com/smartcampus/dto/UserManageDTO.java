package com.smartcampus.dto;

import lombok.Data;

/** 管理员查看用户列表时使用，刻意不暴露密码等敏感字段。 */
@Data
public class UserManageDTO {
    private Long id;
    private String phone;
    private String nickName;
    private String icon;
    /** 0 学生，1 管理员，2 商家。 */
    private Integer role;
}
