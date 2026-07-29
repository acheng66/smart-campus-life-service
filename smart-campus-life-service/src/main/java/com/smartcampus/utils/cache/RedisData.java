package com.smartcampus.utils.cache;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
