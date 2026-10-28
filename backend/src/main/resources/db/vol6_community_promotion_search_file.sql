-- 第6卷：社区(敏感词) / 营销(优惠券/积分/秒杀) / 搜索(歌曲) / 操作日志 建表 SQL（手册 §4.1 §6.1 §7.1 §8 §11）
-- 可重复执行：CREATE TABLE IF NOT EXISTS + INSERT IGNORE（依赖唯一键/主键幂等）
-- 约定：单号类列一律 VARCHAR(40)

-- ---------- 社区：敏感词库（弹幕/评论 DFA 过滤用） ----------
CREATE TABLE IF NOT EXISTS cm_sensitive_word (
  id BIGINT NOT NULL AUTO_INCREMENT,
  word VARCHAR(64) NOT NULL COMMENT '敏感词',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), UNIQUE KEY uk_word(word)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感词库';

INSERT IGNORE INTO cm_sensitive_word(word) VALUES
 ('黄牛'),('退票中介'),('假票'),('刷票'),('代抢'),('骗子'),('加价转'),('内部票');

-- ---------- 营销：优惠券模板 ----------
CREATE TABLE IF NOT EXISTS pm_coupon_template (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  type VARCHAR(16) NOT NULL COMMENT 'FULL_CUT满减/DISCOUNT折扣/CASH立减',
  face_value DECIMAL(10,2) NOT NULL COMMENT '面额或折扣(如8.5)',
  threshold DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '使用门槛(满X)',
  total_count INT NOT NULL COMMENT '发行总量',
  per_user_limit INT NOT NULL DEFAULT 1 COMMENT '每人限领',
  scope VARCHAR(16) NOT NULL DEFAULT 'ALL' COMMENT 'ALL/EVENT/TIER',
  scope_id BIGINT COMMENT 'scope为EVENT/TIER时对应id',
  valid_days INT NOT NULL DEFAULT 30 COMMENT '领取后N天有效',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1上架 0下架',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券模板';

-- ---------- 营销：用户券 ----------
CREATE TABLE IF NOT EXISTS pm_user_coupon (
  id BIGINT NOT NULL AUTO_INCREMENT,
  template_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED/USED/EXPIRED',
  order_no VARCHAR(40) COMMENT '核销订单号(回退时清空)',
  valid_start DATETIME NOT NULL,
  valid_end DATETIME NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_tpl_user(template_id,user_id),   -- 配合Redis防超领,DB兜底
  KEY idx_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户优惠券';

INSERT IGNORE INTO pm_coupon_template(id,name,type,face_value,threshold,total_count,per_user_limit,valid_days)
 VALUES (1,'早鸟满500减50','FULL_CUT',50.00,500.00,1000,1,30);

-- ---------- 营销：积分账户（乐观锁防超扣） ----------
CREATE TABLE IF NOT EXISTS pm_point_account (
  user_id BIGINT NOT NULL,
  balance INT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  PRIMARY KEY(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分账户';

-- ---------- 营销：积分流水 ----------
CREATE TABLE IF NOT EXISTS pm_point_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  change_value INT NOT NULL COMMENT '正为得负为用',
  type VARCHAR(32) NOT NULL COMMENT 'SIGN/ORDER_REFUND/ORDER_EARN/EXCHANGE',
  biz_no VARCHAR(64) COMMENT '业务号(订单号/日期)',
  balance_after INT NOT NULL COMMENT '变动后余额',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  KEY idx_user(user_id),
  UNIQUE KEY uk_type_biz(type,biz_no)   -- 同业务同类型只记一次(消费幂等兜底)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分流水';

-- ---------- 营销：秒杀活动 ----------
CREATE TABLE IF NOT EXISTS pm_seckill_activity (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id BIGINT NOT NULL,
  tier_id BIGINT NOT NULL,
  seckill_price DECIMAL(10,2) NOT NULL,
  total_stock INT NOT NULL,
  start_time DATETIME NOT NULL,
  end_time DATETIME NOT NULL,
  per_user_limit INT NOT NULL DEFAULT 1,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动';

-- 种子：对现有场次1/票档1 开一场秒杀（昨天开始、7天后结束，保证学习期可立即体验）
INSERT IGNORE INTO pm_seckill_activity(id,event_id,tier_id,seckill_price,total_stock,start_time,end_time,per_user_limit,status)
 VALUES (1,1,1,99.00,50,DATE_SUB(NOW(),INTERVAL 1 DAY),DATE_ADD(NOW(),INTERVAL 7 DAY),1,1);

-- ---------- 内容：歌曲（ES 搜索数据源） ----------
CREATE TABLE IF NOT EXISTS ct_song (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name_zh VARCHAR(128) NOT NULL COMMENT '中文名',
  name_en VARCHAR(128) COMMENT '英文/拼音名',
  album VARCHAR(128) COMMENT '专辑',
  artist_name VARCHAR(64) NOT NULL DEFAULT '华晨宇',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1上架 0下架',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY(id), UNIQUE KEY uk_name_zh(name_zh)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='歌曲';

-- 种子：华晨宇真实作品（中文/拼音/英文别名都入 name_en，方便中英文检索演示）
INSERT IGNORE INTO ct_song(id,name_zh,name_en,album,artist_name) VALUES
 (1,'烟火里的尘埃','Ashes from Fireworks','卡西莫多的礼物','华晨宇'),
 (2,'卡西莫多的礼物','Quasimodo''s Gift','卡西莫多的礼物','华晨宇'),
 (3,'微光','Glimmer','卡西莫多的礼物','华晨宇'),
 (4,'齐天','Qi Tian','H','华晨宇'),
 (5,'我管你','I Don''t Care','异类','华晨宇'),
 (6,'异类','Aliens','异类','华晨宇'),
 (7,'国王与乞丐','King and Beggar','异类','华晨宇'),
 (8,'蜉蝣','Mayfly','异类','华晨宇'),
 (9,'好想爱这个世界啊','Love This World','好想爱这个世界啊','华晨宇'),
 (10,'疯人院','Madhouse','新世界NEW WORLD','华晨宇'),
 (11,'斗牛','Bullfighting','新世界NEW WORLD','华晨宇'),
 (12,'新世界','New World','新世界NEW WORLD','华晨宇'),
 (13,'七重人格','Seven Personalities','新世界NEW WORLD','华晨宇'),
 (14,'与火星的孩子对话','Dialogue with Mars Kids','与火星的孩子对话','华晨宇');

-- ---------- 系统：操作日志（AOP 异步落库） ----------
CREATE TABLE IF NOT EXISTS sys_operation_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  module VARCHAR(32) NOT NULL COMMENT '模块',
  action VARCHAR(64) NOT NULL COMMENT '动作',
  biz_id VARCHAR(64) COMMENT '业务id(SpEL提取)',
  operator VARCHAR(64) COMMENT '操作人',
  params TEXT COMMENT '入参JSON(截断2000)',
  success TINYINT NOT NULL DEFAULT 1 COMMENT '1成功 0失败',
  error_msg VARCHAR(512),
  cost_ms INT COMMENT '耗时毫秒',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  KEY idx_module(module), KEY idx_operator(operator), KEY idx_create_time(create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志';

-- ---------- 权限：订单导出（管理员） ----------
INSERT IGNORE INTO uc_permission(id,code,name) VALUES (4,'order:export','导出订单');
INSERT IGNORE INTO uc_role_permission(role_id,permission_id) VALUES (2,4);
