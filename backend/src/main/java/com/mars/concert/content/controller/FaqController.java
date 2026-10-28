package com.mars.concert.content.controller;

import com.mars.concert.common.result.PageResult;
import com.mars.concert.common.result.Result;
import com.mars.concert.content.model.dto.FaqSaveDTO;
import com.mars.concert.content.model.query.FaqQuery;
import com.mars.concert.content.model.vo.FaqVO;
import com.mars.concert.content.service.FaqService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * FAQ 接口（C 端 + 后台共用一套，学习期先不拆）。
 *
 * @RestController = @Controller + @ResponseBody：
 *   类被识别为控制器，且每个方法返回值自动转成 JSON（不再去找页面模板）。
 * @RequestMapping：类级别公共路径前缀。
 * @RequiredArgsConstructor：Lombok 为所有 final 字段生成构造函数，
 *   Spring 通过「构造器注入」把 FaqService 注入进来（推荐的注入方式，不可变、易测试）。
 */
@Tag(name = "内容-FAQ常见问题")
@RestController
@RequestMapping("/api/v1/faqs")
@RequiredArgsConstructor
public class FaqController {

    private final FaqService faqService;

    /** 新增：POST /api/v1/faqs，请求体是 JSON */
    @PreAuthorize("hasAuthority('content:faq:edit')")
    @Operation(summary = "新增FAQ")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody FaqSaveDTO dto) {
        return Result.success(faqService.createFaq(dto));
    }

    /** 修改：PUT /api/v1/faqs/123 */
    @PreAuthorize("hasAuthority('content:faq:edit')")
    @Operation(summary = "修改FAQ")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody FaqSaveDTO dto) {
        faqService.updateFaq(id, dto);
        return Result.success();
    }

    /** 删除：DELETE /api/v1/faqs/123 */
    @PreAuthorize("hasAuthority('content:faq:edit')")
    @Operation(summary = "删除FAQ(逻辑删除)")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        faqService.deleteFaq(id);
        return Result.success();
    }

    /** 详情：GET /api/v1/faqs/123 */
    @Operation(summary = "FAQ详情(浏览量+1)")
    @GetMapping("/{id}")
    public Result<FaqVO> detail(@PathVariable Long id) {
        return Result.success(faqService.getFaqDetail(id));
    }

    /**
     * 分页列表：GET /api/v1/faqs?category=SHOW&pageNum=1&pageSize=10
     * FaqQuery 是普通 POJO，SpringMVC 会把同名 query 参数自动 set 进去（不需要 @RequestBody）。
     */
    @Operation(summary = "FAQ分页条件查询")
    @GetMapping
    public Result<PageResult<FaqVO>> page(FaqQuery query) {
        return Result.success(faqService.pageFaq(query));
    }
}
