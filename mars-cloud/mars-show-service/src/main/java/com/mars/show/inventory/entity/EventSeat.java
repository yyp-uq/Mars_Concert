package com.mars.show.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/** 场次维度座位动态状态（show_event_seat），数据量大，后期可按 event_id 分表 */
@Data
@TableName("show_event_seat")
public class EventSeat {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long eventId;
    private Long seatId;
    private Long areaId;
    private Long tierId;
    /** FREE/LOCKED/SOLD */
    private String status;
    /** 锁持有者 userId:orderNo */
    private String lockOwner;
    /** 锁过期时间(兜底对账用) */
    private LocalDateTime lockExpire;
    @Version
    private Integer version;
}
