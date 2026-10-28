-- 第5卷：支付 / 电子票 / 本地消息 / 消息中心 建表 SQL（手册 §4.1 §5 §14.1）
-- 可重复执行：CREATE TABLE IF NOT EXISTS + INSERT IGNORE

-- ---------- 支付单 ----------
CREATE TABLE IF NOT EXISTS pay_payment (
  id BIGINT NOT NULL AUTO_INCREMENT,
  payment_no VARCHAR(40) NOT NULL COMMENT '我方支付单号',
  order_no VARCHAR(40) NOT NULL,
  user_id BIGINT NOT NULL,
  channel VARCHAR(16) NOT NULL DEFAULT 'MOCK' COMMENT 'MOCK/ALIPAY/WECHAT',
  amount DECIMAL(10,2) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'INIT' COMMENT 'INIT/SUCCESS/FAIL/CLOSED',
  channel_trade_no VARCHAR(64) COMMENT '渠道流水号(幂等关键)',
  callback_raw TEXT,
  paid_time DATETIME,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_payment_no(payment_no),
  UNIQUE KEY uk_channel_trade(channel, channel_trade_no)  -- 同一渠道流水只处理一次
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付单';

-- ---------- 退款单 ----------
CREATE TABLE IF NOT EXISTS pay_refund (
  id BIGINT NOT NULL AUTO_INCREMENT,
  refund_no VARCHAR(40) NOT NULL,
  order_no VARCHAR(40) NOT NULL,
  payment_no VARCHAR(40) NOT NULL,
  amount DECIMAL(10,2) NOT NULL,
  reason VARCHAR(255),
  status VARCHAR(16) NOT NULL DEFAULT 'APPLY' COMMENT 'APPLY/APPROVED/SUCCESS/REJECT',
  channel_refund_no VARCHAR(64),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id), UNIQUE KEY uk_refund_no(refund_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款单';

-- ---------- 电子票 ----------
CREATE TABLE IF NOT EXISTS tk_ticket (
  id BIGINT NOT NULL AUTO_INCREMENT,
  ticket_no VARCHAR(40) NOT NULL,
  order_id BIGINT NOT NULL,
  order_item_id BIGINT NOT NULL,
  event_id BIGINT NOT NULL,
  seat_id BIGINT COMMENT '站票为空',
  viewer_id BIGINT,
  qr_token VARCHAR(64) NOT NULL COMMENT '随机不可枚举',
  status VARCHAR(16) NOT NULL DEFAULT 'VALID' COMMENT 'VALID/USED/REFUNDED',
  checkin_time DATETIME,
  checkin_device VARCHAR(64),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_ticket_no(ticket_no),
  UNIQUE KEY uk_qr(qr_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电子票';

-- ---------- 本地消息表（可靠投递） ----------
CREATE TABLE IF NOT EXISTS sys_local_message (
  id BIGINT NOT NULL AUTO_INCREMENT,
  biz_no VARCHAR(64) NOT NULL COMMENT '业务号(订单号)',
  exchange VARCHAR(64) NOT NULL,
  routing_key VARCHAR(64) NOT NULL,
  payload TEXT NOT NULL COMMENT '消息体JSON',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT',
  retry_count INT NOT NULL DEFAULT 0,
  next_retry DATETIME,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id), KEY idx_status_next(status,next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';

-- ---------- 消息中心：模板 ----------
CREATE TABLE IF NOT EXISTS msg_template (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL COMMENT 'PAY_SUCCESS / REFUND_SUCCESS',
  channel VARCHAR(16) NOT NULL COMMENT 'EMAIL/SMS/INBOX',
  title VARCHAR(128),
  content VARCHAR(512) NOT NULL COMMENT '占位 ${name} ${orderNo}',
  PRIMARY KEY(id), UNIQUE KEY uk_code_channel(code,channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息模板';

-- ---------- 消息中心：记录 ----------
CREATE TABLE IF NOT EXISTS msg_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT, channel VARCHAR(16), template_code VARCHAR(64),
  target VARCHAR(128), content TEXT, status VARCHAR(16) DEFAULT 'PENDING',
  retry_count INT DEFAULT 0, remark VARCHAR(255),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), KEY idx_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息记录';

-- ---------- seed 模板 ----------
INSERT IGNORE INTO msg_template(code,channel,title,content) VALUES
 ('PAY_SUCCESS','INBOX','支付成功','您的订单 ${orderNo} 已支付成功，电子票已生成。'),
 ('PAY_SUCCESS','EMAIL','Mars Concert 购票成功','Hello ${name}, 订单 ${orderNo} 已出票，请凭二维码入场。');
