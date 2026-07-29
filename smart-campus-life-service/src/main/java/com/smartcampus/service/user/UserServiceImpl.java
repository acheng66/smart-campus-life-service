package com.smartcampus.service.user;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;

import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartcampus.dto.LoginFormDTO;
import com.smartcampus.dto.Result;
import com.smartcampus.dto.UserDTO;
import com.smartcampus.dto.UserManageDTO;
import com.smartcampus.entity.User;
import com.smartcampus.entity.Shop;
import com.smartcampus.mapper.shop.ShopMapper;
import com.smartcampus.mapper.user.UserMapper;
import com.smartcampus.service.user.IUserService;
import com.smartcampus.utils.redis.RedisConstants;
import com.smartcampus.utils.common.RegexUtils;
import com.smartcampus.utils.common.SystemConstants;
import com.smartcampus.utils.auth.UserHolder;
import com.smartcampus.utils.auth.UserRole;

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
    @Resource
    private ShopMapper shopMapper;
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
            // 自助注册账号一律是学生，管理员身份只能由数据库迁移或受控管理流程授予。
            user.setRole(UserRole.STUDENT);
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
    public Result logout(String token) {
        if (token != null && !token.trim().isEmpty()) {
            stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + token);
        }
        return Result.ok();
    }

    @Override
    public Result queryUsersForManage(String phone, Integer current) {
        int pageNo = current == null || current < 1 ? 1 : current;
        Page<User> page = query()
                .like(phone != null && !phone.trim().isEmpty(), "phone", phone)
                .orderByAsc("id")
                .page(new Page<>(pageNo, 20));
        Page<UserManageDTO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(BeanUtil.copyToList(page.getRecords(), UserManageDTO.class));
        return Result.ok(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result changeUserRole(Long userId, Integer role) {
        if (role == null || (role != UserRole.STUDENT && role != UserRole.ADMIN && role != UserRole.MERCHANT)) {
            return Result.fail("角色仅支持 0（学生）、1（管理员）；商家由店铺分配时自动设置");
        }
        User target = getById(userId);
        if (target == null) {
            return Result.fail("用户不存在");
        }
        // 商家必须与至少一个店铺同时建立归属关系，不能只修改角色字段。
        if (role == UserRole.MERCHANT) {
            return Result.fail("请在店铺管理页分配店铺，系统会自动设为商家");
        }
        Long operatorId = UserHolder.getUser().getId();
        if (operatorId.equals(userId) && role != UserRole.ADMIN) {
            return Result.fail("不能撤销自己的管理员身份");
        }
        if (Integer.valueOf(UserRole.ADMIN).equals(target.getRole()) && role != UserRole.ADMIN
                && query().eq("role", UserRole.ADMIN).count() <= 1) {
            return Result.fail("系统至少需要保留一名管理员");
        }
        if (!update().eq("id", userId).set("role", role).update()) {
            return Result.fail("更新用户角色失败");
        }
        // 撤销商家角色后同步解除店铺归属，避免无角色用户仍残留 owner_id。
        shopMapper.update(null, new UpdateWrapper<Shop>()
                .eq("owner_id", userId)
                .set("owner_id", null));
        revokeUserTokens(userId);
        return Result.ok();
    }

    /**
     * 角色变更后立即删除该用户的全部 Redis 登录态，避免旧 token 在 TTL 内继续保留旧权限。
     * 当前项目的登录量较小，扫描 login:token:* 可保证权限实时收敛。
     */
    private void revokeUserTokens(Long userId) {
        Set<String> tokenKeys = stringRedisTemplate.keys(RedisConstants.LOGIN_USER_KEY + "*");
        if (tokenKeys == null || tokenKeys.isEmpty()) {
            return;
        }
        for (String tokenKey : tokenKeys) {
            Object tokenUserId = stringRedisTemplate.opsForHash().get(tokenKey, "id");
            if (tokenUserId != null && userId.toString().equals(tokenUserId.toString())) {
                stringRedisTemplate.delete(tokenKey);
            }
        }
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
