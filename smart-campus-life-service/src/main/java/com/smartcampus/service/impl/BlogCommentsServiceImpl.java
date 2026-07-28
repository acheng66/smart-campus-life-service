package com.smartcampus.service.impl;

import com.smartcampus.entity.BlogComments;
import com.smartcampus.mapper.BlogCommentsMapper;
import com.smartcampus.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

}
