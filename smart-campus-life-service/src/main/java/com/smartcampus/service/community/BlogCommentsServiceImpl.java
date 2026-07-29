package com.smartcampus.service.community;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartcampus.entity.BlogComments;
import com.smartcampus.mapper.community.BlogCommentsMapper;
import com.smartcampus.service.community.IBlogCommentsService;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

}
