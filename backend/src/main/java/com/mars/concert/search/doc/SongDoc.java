package com.mars.concert.search.doc;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.List;

/**
 * 歌曲 ES 文档（手册 §8.3）。
 * 中文走 IK：索引 ik_max_word 细切、查询 ik_smart 粗切；专辑 Keyword 精确过滤。
 */
@Data
@Document(indexName = "idx_song")
public class SongDoc {
    @Id
    private Long id;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String nameZh;

    @Field(type = FieldType.Text)
    private String nameEn;

    @Field(type = FieldType.Keyword)
    private String album;

    @Field(type = FieldType.Text, analyzer = "ik_max_word")
    private String artistName;

    @Field(type = FieldType.Boolean)
    private Boolean status;

    /** 搜索联想词（Spring Data ES 5.x 已移除 FieldType.Completion，先用 Keyword 存词） */
    @Field(type = FieldType.Keyword)
    private List<String> suggest;
}
