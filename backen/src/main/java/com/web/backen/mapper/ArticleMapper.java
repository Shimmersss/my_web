package com.web.backen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.web.backen.entity.Article;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ArticleMapper extends BaseMapper<Article> {
}
