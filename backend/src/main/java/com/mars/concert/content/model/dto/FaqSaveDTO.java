package com.mars.concert.content.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增/修改 FAQ 的入参。
 * 修改时 id 单独走 URL，故 DTO 里不放 id，防止前端篡改其它字段。
 */
@Data
@Schema(description = "FAQ保存入参")
public class FaqSaveDTO {

    @Schema(description = "分类", example = "SHOW")
    @NotBlank(message = "分类不能为空")
    @Size(max = 32, message = "分类长度不能超过32")
    private String category;

    @Schema(description = "英文问题", example = "When and where is Mars Concert 2.0?")
    @NotBlank(message = "问题不能为空")
    @Size(max = 255, message = "问题过长")
    private String questionEn;

    @Schema(description = "中文问题")
    @Size(max = 255)
    private String questionZh;

    @Schema(description = "答案")
    @NotBlank(message = "答案不能为空")
    private String answer;

    @Schema(description = "排序，越小越靠前", example = "1")
    private Integer sortNo;

    @Schema(description = "状态 1上架 0下架", example = "1")
    private Integer status;
}
