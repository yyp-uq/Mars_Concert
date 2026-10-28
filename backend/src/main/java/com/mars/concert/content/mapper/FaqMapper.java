package com.mars.concert.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mars.concert.content.entity.Faq;
import org.apache.ibatis.annotations.Mapper;

/**
 * 继承 BaseMapper<Faq> 即免费获得：
 * insert / deleteById / updateById / selectById / selectList(Wrapper) / selectPage ...
 * 复杂多表查询时，再在这里写自定义方法 + XML 或 @Select 注解。
 */
@Mapper
public interface FaqMapper extends BaseMapper<Faq> {
}
