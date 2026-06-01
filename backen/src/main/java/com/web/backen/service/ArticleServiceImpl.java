package com.web.backen.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.web.backen.entity.Article;
import com.web.backen.mapper.ArticleMapper;
import org.springframework.stereotype.Service;

@Service
public class ArticleServiceImpl extends ServiceImpl<ArticleMapper, Article> implements ArticleService {
}
