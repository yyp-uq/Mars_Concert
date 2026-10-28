package com.mars.common.utils;

/**
 * 订单号生成：前缀M + yyyyMMddHHmmss + 雪花ID后缀。
 * 不用 Hutool 依赖，自己写一个简化雪花（workerId/datacenterId 固定 1）。
 */
public class OrderNoGenerator {

    private static final long WORKER_ID = 1L;
    private static final long DATACENTER_ID = 1L;

    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);
    private static final long EPOCH = 1704067200000L; // 2024-01-01 00:00:00

    private static long sequence = 0L;
    private static long lastTimestamp = -1L;

    public static synchronized String next() {
        return "M" + dateStr() + snowflake();
    }

    /** 支付单号：P + 时间 + 雪花后缀 */
    public static String payNo() {
        return "P" + dateStr() + snowflake();
    }

    /** 电子票号：T + 时间 + 雪花后缀 */
    public static String ticketNo() {
        return "T" + dateStr() + snowflake();
    }

    /** 退款单号：R + 时间 + 雪花后缀 */
    public static String refundNo() {
        return "R" + dateStr() + snowflake();
    }

    private static String dateStr() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    }

    private static synchronized long snowflake() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            timestamp = lastTimestamp; // 时钟回拨保护，本卷简化处理
        }
        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (DATACENTER_ID << DATACENTER_ID_SHIFT)
                | (WORKER_ID << WORKER_ID_SHIFT)
                | sequence;
    }

    private static long waitNextMillis(long lastTs) {
        long ts = System.currentTimeMillis();
        while (ts <= lastTs) ts = System.currentTimeMillis();
        return ts;
    }
}
