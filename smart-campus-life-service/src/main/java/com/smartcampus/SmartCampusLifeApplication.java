package com.smartcampus;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.smartcampus.mapper")
@SpringBootApplication
public class SmartCampusLifeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartCampusLifeApplication.class, args);
    }

}
