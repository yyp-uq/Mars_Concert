package com.mars.concert.content.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 返回给前端的 FAQ 数据。
 * 故意不含 deleted 字段（前端无需知道逻辑删除标记），演示 VO 与 Entity 解耦。
 */
@Data
@Schema(description = "FAQ视图对象")
public class FaqVO {
    private Long id;
    private String category;
    private String questionEn;
    private String questionZh;
    private String answer;
    private Integer sortNo;
    private Integer viewCount;
    private Integer status;
    private LocalDateTime createTime;
}
