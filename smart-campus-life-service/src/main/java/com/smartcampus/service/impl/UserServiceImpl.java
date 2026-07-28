package com.smartcampus.service.impl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartcampus.dto.LoginFormDTO;
import com.smartcampus.dto.Result;
import com.smartcampus.dto.UserDTO;
import com.smartcampus.entity.User;
import com.smartcampus.mapper.UserMapper;
import com.smartcampus.service.IUserService;
import com.smartcampus.utils.RedisConstants;
import com.smartcampus.utils.RegexUtils;
import com.smartcampus.utils.SystemConstants;
import com.smartcampus.utils.UserHolder;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 发送手机验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式无效！");
        }
        //2.生成验证码
        String code = RandomUtil.randomString(6);
//        //3.保存验证码到session
//        session.setAttribute("code", code);
        //3.保存验证码到redis
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //4.发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        //5.返回结果
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式无效！");
        }
        //2.校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            //3.不一致，报错
            return Result.fail("验证码错误");
        }
        //4.一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //5.不存在，创建新用户并保存
        if (user == null) {
            user = new User();
            user.setPhone(phone);
            user.setNickName( SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            save(user);
        }
        //6.保存用户信息到redis中
        //6.1.随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //6.2.将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor( (fieldName, fieldValue) -> fieldValue.toString()));
        //6.3.存储
        stringRedisTemplate.opsForHash().
                putAll(RedisConstants.LOGIN_USER_KEY + token, userMap);
        //6.4.设置token有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //7.返回
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.写入redis
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.获取本月截止今天为止的所有签到记录，返回的是一个十进制的数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.size() == 0) {
            //没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null|| num == 0){
            return Result.ok(0);
        }
        //6.循环遍历
        int count = 0;
        while (true) {
            //6.1.让这个数字与1做与运算，得到数字的最后一个bit位
            if ((num & 1) == 0) {
                //6.2.判断这个bit位是否为0
                break;
            }else {
                //6.3.如果不为0，计数器+1
                count++;
            }
            //6.4.把数字右移一位，相当于向右移动一位
            num >>>= 1;
        }
        return Result.ok(count);
    }
}
