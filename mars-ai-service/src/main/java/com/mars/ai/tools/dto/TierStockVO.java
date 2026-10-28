package com.mars.ai.tools.dto;

/** 场次票档余票 */
public record TierStockVO(Long tierId, String name, String price, Integer remainStock) {
}
