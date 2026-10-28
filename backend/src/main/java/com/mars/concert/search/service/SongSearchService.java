package com.mars.concert.search.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mars.concert.common.constant.RedisKeys;
import com.mars.concert.common.result.PageResult;
import com.mars.concert.search.doc.SongDoc;
import com.mars.concert.search.entity.Song;
import com.mars.concert.search.mapper.SongMapper;
import com.mars.concert.search.model.vo.HotWordVO;
import com.mars.concert.search.repository.SongEsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 歌曲搜索（手册 §8.4/§8.5）：
 * multiMatch 中英文加权检索 + 专辑精确过滤 + 分页；每次搜索 ZINCRBY 热词榜。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SongSearchService {

    private static final int HOT_TOP_N = 10;

    private final ElasticsearchOperations operations;
    private final SongEsRepository songEsRepository;
    private final SongMapper songMapper;
    private final StringRedisTemplate redis;

    public PageResult<SongDoc> search(String keyword, String album, int page, int size) {
        NativeQuery q = NativeQuery.builder()
                .withQuery(qb -> qb.bool(b -> {
                    if (keyword != null && !keyword.isBlank()) {
                        // nameZh 权重最高，其次英文名，再次歌手名
                        b.must(m -> m.multiMatch(mm -> mm
                                .query(keyword)
                                .fields("nameZh^3", "nameEn^2", "artistName")));
                    } else {
                        b.must(m -> m.matchAll(ma -> ma));
                    }
                    if (album != null && !album.isBlank()) {
                        b.filter(f -> f.term(t -> t.field("album").value(album)));
                    }
                    return b;
                }))
                .withPageable(PageRequest.of(Math.max(page, 1) - 1, size))
                .build();
        SearchHits<SongDoc> hits = operations.search(q, SongDoc.class);

        // 热词榜：有效关键词才计数
        if (keyword != null && !keyword.isBlank()) {
            try {
                redis.opsForZSet().incrementScore(RedisKeys.HOT_SONGS, keyword.trim(), 1);
            } catch (Exception e) {
                log.warn("热词计数失败 keyword={}", keyword, e);
            }
        }
        return PageResult.of(hits.get().map(SearchHit::getContent).toList(),
                hits.getTotalHits(), page, size);
    }

    /** 热搜 Top10（ZSet 按 score 倒序） */
    public List<HotWordVO> hot() {
        Set<ZSetOperations.TypedTuple<String>> set =
                redis.opsForZSet().reverseRangeWithScores(RedisKeys.HOT_SONGS, 0, HOT_TOP_N - 1);
        if (set == null) return List.of();
        List<HotWordVO> list = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> t : set) {
            if (t.getValue() != null) {
                list.add(new HotWordVO(t.getValue(), t.getScore() == null ? 0 : t.getScore()));
            }
        }
        return list;
    }

    /** 全量同步 DB → ES（管理端触发；学习期简单覆盖写，进阶走 MQ/Canal） */
    public int syncAll() {
        List<Song> songs = songMapper.selectList(Wrappers.<Song>lambdaQuery());
        List<SongDoc> docs = new ArrayList<>();
        for (Song s : songs) {
            SongDoc d = new SongDoc();
            d.setId(s.getId());
            d.setNameZh(s.getNameZh());
            d.setNameEn(s.getNameEn());
            d.setAlbum(s.getAlbum());
            d.setArtistName(s.getArtistName());
            d.setStatus(s.getStatus() != null && s.getStatus() == 1);
            List<String> suggest = new ArrayList<>();
            if (s.getNameZh() != null) suggest.add(s.getNameZh());
            if (s.getNameEn() != null && !s.getNameEn().isBlank()) suggest.add(s.getNameEn());
            d.setSuggest(suggest);
            docs.add(d);
        }
        songEsRepository.saveAll(docs);
        log.info("歌曲同步 ES 完成 count={}", docs.size());
        return docs.size();
    }
}
