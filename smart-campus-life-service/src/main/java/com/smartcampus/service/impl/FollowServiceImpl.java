package com.smartcampus.service.impl;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartcampus.dto.Result;
import com.smartcampus.dto.UserDTO;
import com.smartcampus.entity.Follow;
import com.smartcampus.mapper.FollowMapper;
import com.smartcampus.service.IFollowService;
import com.smartcampus.service.IUserService;
import com.smartcampus.utils.UserHolder;

import cn.hutool.core.bean.BeanUtil;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    /**
     * 关注与取关
     * @param id
     * @param isFollow
     * @return
     */
    @Override
    public Result follow(Long id, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        //1. 判断是否关注
        if (isFollow){
            //2. 关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            boolean isSuccess = save(follow);
            if (isSuccess){
                //把关注用户id放入redis的set集合中
                stringRedisTemplate.opsForSet().add(key, id.toString());
            }
        }else{
            //3. 取关
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", id));
            //把关注用户id从redis的set集合中移除
            if (isSuccess){
                stringRedisTemplate.opsForSet().remove(key, id.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询是否关注
     * @param id
     * @return
     */
    @Override
    public Result isFollow(Long id) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("follow_user_id", id).count();
        return Result.ok(count > 0);
    }

    /**
     * 查询共同关注
     * @param id
     * @return
     */
    @Override
    public Result common(Long id) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 2.获取id的follows:set
        String key2 = "follows:" + id;
        // 3.获取两个set的交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()){
            //没有共同关注
            return Result.ok(Collections.emptyList());
        }
        // 4.将交集结果转换为Long类型
        List<Long> intersectIds = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 5.根据id查询用户
        List<UserDTO> users= userService.listByIds(intersectIds)
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        // 6.返回结果
        return Result.ok(users);
    }
}
