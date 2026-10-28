package com.mars.show.inventory.controller;

import com.mars.common.result.Result;
import com.mars.show.inventory.service.InventoryWarmupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "后台-库存预热")
@RestController
@RequestMapping("/api/admin/inventory")
@RequiredArgsConstructor
public class AdminInventoryController {

    private final InventoryWarmupService inventoryWarmupService;

    @Operation(summary = "库存预热(开售前 DB→Redis)")
    @PostMapping("/warmup/{eventId}")
    public Result<Void> warmup(@PathVariable Long eventId) {
        inventoryWarmupService.warmup(eventId);
        return Result.success();
    }
}
