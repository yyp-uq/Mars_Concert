package com.mars.api.show;

import com.mars.api.show.dto.*;
import com.mars.common.exception.BusinessException;
import com.mars.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 演出服务降级（第8卷 §9.4）：show-service 宕机/熔断时给用户友好提示而不是 500。
 * 核心链路（查价/锁座/扣库存）抛业务异常让下单失败并回滚；
 * 非核心链路（回补/释放）记录日志后返回成功，靠兜底任务最终对齐。
 */
@Slf4j
@Component
public class ShowClientFallback implements FallbackFactory<ShowClient> {

    @Override
    public ShowClient create(Throwable cause) {
        return new ShowClient() {
            @Override
            public Result<EventInfoDTO> getEvent(Long id) {
                log.error("ShowClient.getEvent 降级 eventId={}", id, cause);
                throw new BusinessException(5001, "演出服务暂不可用，请稍后重试");
            }

            @Override
            public Result<TierInfoDTO> getTierInfo(Long eventId, Long tierId) {
                log.error("ShowClient.getTierInfo 降级 eventId={} tierId={}", eventId, tierId, cause);
                throw new BusinessException(5001, "演出服务暂不可用，请稍后重试");
            }

            @Override
            public Result<Boolean> deductStock(DeductStockDTO dto) {
                log.error("ShowClient.deductStock 降级 dto={}", dto, cause);
                throw new BusinessException(5002, "库存服务繁忙，下单失败");
            }

            @Override
            public Result<Void> addStock(AddStockDTO dto) {
                log.error("ShowClient.addStock 降级（回补丢失，由对账兜底）dto={}", dto, cause);
                return Result.success();
            }

            @Override
            public Result<Void> lockSeats(LockSeatsDTO dto) {
                log.error("ShowClient.lockSeats 降级 dto={}", dto, cause);
                throw new BusinessException(5002, "库存服务繁忙，下单失败");
            }

            @Override
            public Result<List<SeatInfoDTO>> listEventSeats(SeatListDTO dto) {
                log.error("ShowClient.listEventSeats 降级 dto={}", dto, cause);
                throw new BusinessException(5001, "演出服务暂不可用，请稍后重试");
            }

            @Override
            public Result<Void> releaseByOrderNo(String orderNo) {
                log.error("ShowClient.releaseByOrderNo 降级 orderNo={}", orderNo, cause);
                return Result.success();
            }
        };
    }
}
