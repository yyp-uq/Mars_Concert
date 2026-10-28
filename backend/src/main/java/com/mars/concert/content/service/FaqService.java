package com.mars.concert.content.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mars.concert.content.entity.Faq;
import com.mars.concert.content.model.dto.FaqSaveDTO;
import com.mars.concert.content.model.query.FaqQuery;
import com.mars.concert.content.model.vo.FaqVO;
import com.mars.concert.common.result.PageResult;

/**
 * 继承 IService<Faq> 可获得 save/updateById/removeById/getById 等基础方法。
 * 自定义复杂业务方法在这里声明。
 */
public interface FaqService extends IService<Faq> {

    /** 新增 FAQ，返回新 id */
    Long createFaq(FaqSaveDTO dto);

    /** 修改 FAQ */
    void updateFaq(Long id, FaqSaveDTO dto);

    /** 删除（逻辑删除） */
    void deleteFaq(Long id);

    /** 详情（浏览量 +1） */
    FaqVO getFaqDetail(Long id);

    /** 分页条件查询 */
    PageResult<FaqVO> pageFaq(FaqQuery query);
}
