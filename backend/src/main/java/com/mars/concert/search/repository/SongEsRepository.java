package com.mars.concert.search.repository;

import com.mars.concert.search.doc.SongDoc;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface SongEsRepository extends ElasticsearchRepository<SongDoc, Long> {
    /** 方法名派生查询（简单条件） */
    List<SongDoc> findByNameZhContainingOrNameEnContaining(String zh, String en);
}
