package com.mars.concert.content.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mars.concert.common.exception.BusinessException;
import com.mars.concert.common.result.PageResult;
import com.mars.concert.content.entity.Faq;
import com.mars.concert.content.enums.ContentErrorCode;
import com.mars.concert.content.mapper.FaqMapper;
import com.mars.concert.content.model.dto.FaqSaveDTO;
import com.mars.concert.content.model.query.FaqQuery;
import com.mars.concert.content.model.vo.FaqVO;
import com.mars.concert.content.service.FaqService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @Service：交给 Spring 管理，Controller 才能注入。
 * extends ServiceImpl<FaqMapper, Faq>：获得通用 CRUD 实现，同时 this.list()/this.getById() 可用。
 */
@Service
public class FaqServiceImpl extends ServiceImpl<FaqMapper, Faq> implements FaqService {

    @Override
    public Long createFaq(FaqSaveDTO dto) {
        Faq faq = new Faq();
        // BeanUtils.copyProperties：同名字段自动拷贝，避免一个个 set
        BeanUtils.copyProperties(dto, faq);
        if (faq.getStatus() == null) {
            faq.setStatus(1);                 // 默认上架
        }
        if (faq.getSortNo() == null) {
            faq.setSortNo(0);
        }
        faq.setViewCount(0);
        this.save(faq);                        // INSERT，id 自动回填到 faq.id
        return faq.getId();
    }

    @Override
    public void updateFaq(Long id, FaqSaveDTO dto) {
        Faq exist = this.getById(id);
        if (exist == null) {
            throw new BusinessException(ContentErrorCode.FAQ_NOT_FOUND);
        }
        Faq faq = new Faq();
        BeanUtils.copyProperties(dto, faq);
        faq.setId(id);
        // updateById 只更新非 null 字段；带 @Version 会自动乐观锁
        this.updateById(faq);
    }

    @Override
    public void deleteFaq(Long id) {
        Faq exist = this.getById(id);
        if (exist == null) {
            throw new BusinessException(ContentErrorCode.FAQ_NOT_FOUND);
        }
        this.removeById(id);    // 因为有 @TableLogic，实际是 UPDATE deleted=1
    }

    @Override
    public FaqVO getFaqDetail(Long id) {
        Faq faq = this.getById(id);
        if (faq == null) {
            throw new BusinessException(ContentErrorCode.FAQ_NOT_FOUND);
        }
        // 浏览量 +1（演示一个简单的自增更新，生产环境高并发可用 SQL 原子自增）
        Faq update = new Faq();
        update.setId(id);
        update.setViewCount(faq.getViewCount() + 1);
        this.updateById(update);
        faq.setViewCount(faq.getViewCount() + 1);

        FaqVO vo = new FaqVO();
        BeanUtils.copyProperties(faq, vo);
        return vo;
    }

    @Override
    public PageResult<FaqVO> pageFaq(FaqQuery query) {
        // 1. 构造分页对象
        Page<Faq> page = new Page<>(query.getPageNum(), query.getPageSize());

        // 2. 构造动态查询条件（Lambda 方式，编译期可检查字段名，避免手写字符串出错）
        LambdaQueryWrapper<Faq> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(query.getCategory() != null && !query.getCategory().isBlank(),
                        Faq::getCategory, query.getCategory())
               .eq(query.getStatus() != null, Faq::getStatus, query.getStatus())
               .and(query.getKeyword() != null && !query.getKeyword().isBlank(),
                        w -> w.like(Faq::getQuestionEn, query.getKeyword())
                              .or().like(Faq::getQuestionZh, query.getKeyword()))
               .orderByAsc(Faq::getSortNo)
               .orderByDesc(Faq::getId);

        // 3. 执行分页查询
        Page<Faq> result = this.page(page, wrapper);

        // 4. Entity 列表转 VO 列表
        List<FaqVO> voList = result.getRecords().stream().map(faq -> {
            FaqVO vo = new FaqVO();
            BeanUtils.copyProperties(faq, vo);
            return vo;
        }).toList();

        return PageResult.of(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }
}
