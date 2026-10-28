package com.mars.concert.content.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 列表查询条件（都是可选条件，所以不加校验注解）。
 */
@Data
@Schema(description = "FAQ分页查询条件")
public class FaqQuery {

    @Schema(description = "分类", example = "SHOW")
    private String category;

    @Schema(description = "问题关键词(模糊)")
    private String keyword;

    @Schema(description = "状态 1上架0下架，不传查全部", example = "1")
    private Integer status;

    @Schema(description = "页码", example = "1")
    private Long pageNum = 1L;

    @Schema(description = "每页条数", example = "10")
    private Long pageSize = 10L;
}
