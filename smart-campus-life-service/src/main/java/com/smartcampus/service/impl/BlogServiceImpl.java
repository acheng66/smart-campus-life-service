package com.smartcampus.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartcampus.dto.Result;
import com.smartcampus.dto.ScrollResult;
import com.smartcampus.dto.UserDTO;
import com.smartcampus.entity.Blog;
import com.smartcampus.entity.Follow;
import com.smartcampus.entity.User;
import com.smartcampus.mapper.BlogMapper;
import com.smartcampus.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartcampus.service.IFollowService;
import com.smartcampus.service.IUserService;
import com.smartcampus.utils.RedisConstants;
import com.smartcampus.utils.SystemConstants;
import com.smartcampus.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IFollowService followService;
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 查询热门博客
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 根据id查询博客
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        // 1. 查询博客
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        // 2. 查询博客相关的用户
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        // 3. 查询博客是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 判断博客是否被点赞
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        // 1. 获取用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        Long userId = user.getId();
        // 2. 判断当前用户是否已经点赞
        String key = "blog:liked:" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 点赞博客
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        // 1. 获取用户
        Long userId = UserHolder.getUser().getId();
        // 2. 判断当前用户是否已经点赞
        String key = RedisConstants.BLOG_LIKED + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score != null) {
            // 3. 如果已经点赞，取消点赞
            // 3.1 删除用户点赞数据
            // 3.2 数据库点赞数量减1
            boolean isSuccess =update().setSql("liked = liked - 1")
                    .eq("id", id).update();
            if (isSuccess) {
                //3.3 删除用户点赞数据
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        } else {
            // 4. 如果没有点赞，点赞
            // 4.1 数据库点赞数量加1
            boolean isSuccess = update().setSql("liked = liked + 1")
                    .eq("id", id).update();
            if (isSuccess) {
                // 4.2 保存用户点赞数据
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }
        // 4.3 返回
        return Result.ok();
    }

    /**
     * 查询博客点赞名单
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED + id;
        // 1. 查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            // 2. 如果没有，返回一个空集合
            return Result.ok(Collections.emptyList());
        }
        //3.解析出用户id
        List<Long> userIds = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String ids = StrUtil.join(",", userIds);
        //4.根据用户id查询用户
        List<UserDTO> userDTOS = userService.query().in("id", userIds)
                        .last("ORDER BY FIELD(id," + ids + ")").list()
                        .stream()
                        .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                        .collect(Collectors.toList());
        //5.返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        //1. 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        //2. 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败！");
        }
        //3.查询笔记作者的所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //4.推送笔记
        for (Follow follow : follows) {
            String key = RedisConstants.FEED_KEY + follow.getUserId();
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 查询收件箱
        String key = RedisConstants.FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        //3. 解析数据
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;int os=1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            String blogIdStr = tuple.getValue();
            ids.add(Long.valueOf(blogIdStr));
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        //4.根据id查询笔记
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            //5.查询blog的作者
            Long userId1 = blog.getUserId();
            User user = userService.getById(userId1);
            blog.setIcon(user.getIcon());
            blog.setName(user.getNickName());
            //6.查询blog是否被点赞
            isBlogLiked(blog);
        }
        //7.返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }
}
