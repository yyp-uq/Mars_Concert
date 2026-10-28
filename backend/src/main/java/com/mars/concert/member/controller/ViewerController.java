package com.mars.concert.member.controller;

import com.mars.concert.common.result.Result;
import com.mars.concert.common.web.CurrentUser;
import com.mars.concert.member.model.dto.ViewerSaveDTO;
import com.mars.concert.member.model.vo.ViewerVO;
import com.mars.concert.member.service.ViewerService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "会员-观演人")
@RestController
@RequestMapping("/api/v1/member/viewers")
@RequiredArgsConstructor
public class ViewerController {

    private final ViewerService viewerService;

    @PostMapping
    public Result<Long> add(@CurrentUser Long userId, @Valid @RequestBody ViewerSaveDTO dto) {
        return Result.success(viewerService.add(userId, dto));
    }

    @GetMapping
    public Result<List<ViewerVO>> list(@CurrentUser Long userId) {
        return Result.success(viewerService.listByUser(userId));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@CurrentUser Long userId,
                               @PathVariable Long id,
                               @Valid @RequestBody ViewerSaveDTO dto) {
        viewerService.updateForUser(userId, id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@CurrentUser Long userId, @PathVariable Long id) {
        viewerService.deleteForUser(userId, id);
        return Result.success();
    }
}
