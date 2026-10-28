USE mars_concert;

-- 用户表
CREATE TABLE uc_user (
  id BIGINT NOT NULL AUTO_INCREMENT,
  phone VARCHAR(32) NOT NULL COMMENT '手机号(加密或明文,学习期先明文)',
  password VARCHAR(100) DEFAULT NULL COMMENT 'BCrypt密文',
  nickname VARCHAR(64) DEFAULT NULL,
  avatar VARCHAR(255) DEFAULT NULL,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1正常 0冻结 2注销',
  last_login_time DATETIME DEFAULT NULL,
  last_login_ip VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  PRIMARY KEY(id), UNIQUE KEY uk_phone(phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员用户';

-- 角色
CREATE TABLE uc_role (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL COMMENT '角色码 如 ROLE_MEMBER/ROLE_ADMIN',
  name VARCHAR(64) NOT NULL,
  PRIMARY KEY(id), UNIQUE KEY uk_code(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 权限点
CREATE TABLE uc_permission (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(128) NOT NULL COMMENT '权限点 如 content:faq:edit',
  name VARCHAR(128) NOT NULL,
  PRIMARY KEY(id), UNIQUE KEY uk_code(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE uc_user_role (
  user_id BIGINT NOT NULL, role_id BIGINT NOT NULL,
  PRIMARY KEY(user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE uc_role_permission (
  role_id BIGINT NOT NULL, permission_id BIGINT NOT NULL,
  PRIMARY KEY(role_id, permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 实名观演人
CREATE TABLE uc_viewer (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  name VARCHAR(64) NOT NULL,
  id_card VARCHAR(32) NOT NULL COMMENT '学习期明文,生产加密',
  phone VARCHAR(32) DEFAULT NULL,
  is_default TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY(id), KEY idx_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='观演人';

-- 初始化角色与权限
INSERT INTO uc_role(id,code,name) VALUES (1,'ROLE_MEMBER','会员'),(2,'ROLE_ADMIN','管理员');
INSERT INTO uc_permission(id,code,name) VALUES
 (1,'content:faq:edit','编辑FAQ'),(2,'order:refund:audit','退款审核'),(3,'show:event:edit','编辑场次');
INSERT INTO uc_role_permission(role_id,permission_id) VALUES (2,1),(2,2),(2,3);
