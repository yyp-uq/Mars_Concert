package com.mars.common.result;

import lombok.Data;
import java.util.List;

/**
 * 分页返回统一结构。
 * 泛型 <T> 表示列表里装的可以是任意 VO。
 */
@Data
public class PageResult<T> {
    private List<T> list;      // 当前页数据
    private Long total;        // 总条数
    private Long pageNum;      // 当前页码
    private Long pageSize;     // 每页条数

    public static <T> PageResult<T> of(List<T> list, long total, long pageNum, long pageSize) {
        PageResult<T> p = new PageResult<>();
        p.list = list;
        p.total = total;
        p.pageNum = pageNum;
        p.pageSize = pageSize;
        return p;
    }
}
