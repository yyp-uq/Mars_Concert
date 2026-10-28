package com.mars.concert.search.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 热搜词榜项 */
@Data
@AllArgsConstructor
public class HotWordVO {
    private String word;
    private Double score;
}
