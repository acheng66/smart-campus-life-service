package com.smartcampus.dto;

import lombok.Data;

@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
    /** 用户角色：0 学生，1 管理员。 */
    private Integer role;
}
