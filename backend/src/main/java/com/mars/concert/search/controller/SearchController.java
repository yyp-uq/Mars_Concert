package com.mars.concert.search.controller;

import com.mars.concert.common.result.PageResult;
import com.mars.concert.common.result.Result;
import com.mars.concert.search.doc.SongDoc;
import com.mars.concert.search.model.vo.HotWordVO;
import com.mars.concert.search.service.SongSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "搜索-歌曲")
@RestController
@RequiredArgsConstructor
public class SearchController {

    private final SongSearchService songSearchService;

    @Operation(summary = "歌曲搜索（中英文关键词 + 专辑过滤 + 分页）")
    @GetMapping("/api/v1/search/songs")
    public Result<PageResult<SongDoc>> songs(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String album,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "10") int size) {
        return Result.success(songSearchService.search(keyword, album, page, size));
    }

    @Operation(summary = "热搜榜 Top10")
    @GetMapping("/api/v1/search/hot")
    public Result<List<HotWordVO>> hot() {
        return Result.success(songSearchService.hot());
    }

    @Operation(summary = "全量同步歌曲到 ES（管理员）")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/admin/search/songs/sync")
    public Result<Integer> sync() {
        return Result.success(songSearchService.syncAll());
    }
}
