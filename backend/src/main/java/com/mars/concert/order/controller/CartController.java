package com.mars.concert.order.controller;

import com.mars.concert.common.result.Result;
import com.mars.concert.common.web.CurrentUser;
import com.mars.concert.order.service.CartService;
import com.mars.concert.order.model.vo.CartVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "交易-购物车")
@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @Operation(summary = "加入购物车")
    @PostMapping("/add")
    public Result<Void> add(@CurrentUser Long uid,
                            @RequestParam Long eventId,
                            @RequestParam Long tierId,
                            @RequestParam(defaultValue = "1") int qty) {
        cartService.add(uid, eventId, tierId, qty);
        return Result.success();
    }

    @Operation(summary = "修改购物车数量")
    @PutMapping("/update")
    public Result<Void> updateQty(@CurrentUser Long uid,
                                  @RequestParam Long eventId,
                                  @RequestParam Long tierId,
                                  @RequestParam int qty) {
        cartService.updateQty(uid, eventId, tierId, qty);
        return Result.success();
    }

    @Operation(summary = "删除购物车项")
    @DeleteMapping("/remove")
    public Result<Void> remove(@CurrentUser Long uid,
                               @RequestParam Long eventId,
                               @RequestParam Long tierId) {
        cartService.remove(uid, eventId, tierId);
        return Result.success();
    }

    @Operation(summary = "清空购物车")
    @DeleteMapping("/clear")
    public Result<Void> clear(@CurrentUser Long uid) {
        cartService.clear(uid);
        return Result.success();
    }

    @Operation(summary = "我的购物车")
    @GetMapping
    public Result<CartVO> myCart(@CurrentUser Long uid) {
        return Result.success(cartService.myCart(uid));
    }
}
