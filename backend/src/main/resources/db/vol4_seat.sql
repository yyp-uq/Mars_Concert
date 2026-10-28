-- 第4卷：座位相关建表 SQL（手册 §2）
CREATE TABLE IF NOT EXISTS show_venue_area (
  id BIGINT NOT NULL AUTO_INCREMENT,
  venue_id BIGINT NOT NULL,
  code VARCHAR(16) NOT NULL COMMENT '区码 A/B/VIP',
  name VARCHAR(32) NOT NULL,
  type VARCHAR(16) NOT NULL DEFAULT 'SEATED' COMMENT 'SEATED坐票 STAND站票',
  capacity INT NOT NULL DEFAULT 0,
  PRIMARY KEY(id), KEY idx_venue(venue_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS show_seat (
  id BIGINT NOT NULL AUTO_INCREMENT,
  venue_id BIGINT NOT NULL,
  area_id BIGINT NOT NULL,
  seat_no VARCHAR(16) NOT NULL COMMENT '如 A-5-12',
  x INT NOT NULL COMMENT '座位图X坐标',
  y INT NOT NULL COMMENT '座位图Y坐标',
  PRIMARY KEY(id), KEY idx_area(area_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS show_event_seat (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id BIGINT NOT NULL,
  seat_id BIGINT NOT NULL,
  area_id BIGINT NOT NULL,
  tier_id BIGINT NOT NULL,
  status VARCHAR(8) NOT NULL DEFAULT 'FREE' COMMENT 'FREE/LOCKED/SOLD',
  lock_owner VARCHAR(64) COMMENT '锁持有者 userId:orderNo',
  lock_expire DATETIME COMMENT '锁过期时间(兜底对账用)',
  version INT NOT NULL DEFAULT 0,
  PRIMARY KEY(id),
  UNIQUE KEY uk_event_seat(event_id, seat_id),
  KEY idx_event_status(event_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='场次座位状态';

-- 订单明细增加座位列（手册 §9）
ALTER TABLE ord_order_item ADD COLUMN seat_id BIGINT NULL COMMENT '座位ID(选座订单)' AFTER viewer_id;

-- 测试数据：A区 5×5=25 坐票 + S 站票区
INSERT INTO show_venue_area(id,venue_id,code,name,type,capacity) VALUES
 (1,1,'A','内场A区','SEATED',25),(2,1,'S','站票区','STAND',1000);

-- 生成 25 个座位 A-排-列（x/y 每排 20px 间隔，从 10 开始）
INSERT INTO show_seat(venue_id,area_id,seat_no,x,y)
SELECT 1, 1, CONCAT('A-', r.r, '-', c.c), 10 + (c.c-1)*20, 10 + (r.r-1)*20
FROM (SELECT 1 r UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5) r
JOIN (SELECT 1 c UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5) c;

-- 场次1 座位实例：模板复制为场次座位，绑定票档2=内场票
INSERT INTO show_event_seat(event_id,seat_id,area_id,tier_id,status)
SELECT 1,id,area_id,2,'FREE' FROM show_seat WHERE area_id=1;

-- 给用户1追加 ADMIN 角色（冒烟测试后台预热接口用）
INSERT IGNORE INTO uc_user_role(user_id, role_id) VALUES (1, 2);
