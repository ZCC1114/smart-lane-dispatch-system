SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS whitelist_records (
  id VARCHAR(255) NOT NULL COMMENT '白名单记录主键',
  plate VARCHAR(255) NOT NULL COMMENT '白名单车牌号，系统按规范化车牌唯一命中',
  created_at DATETIME(6) NOT NULL COMMENT '首次导入创建时间',
  updated_at DATETIME(6) NOT NULL COMMENT '最近一次导入或更新时间',
  created_by VARCHAR(255) NOT NULL COMMENT '首次导入操作人',
  updated_by VARCHAR(255) NOT NULL COMMENT '最近导入或更新操作人',
  PRIMARY KEY (id),
  UNIQUE KEY uk_whitelist_plate (plate),
  KEY idx_whitelist_updated_at (updated_at),
  KEY idx_whitelist_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='车辆白名单记录表';

INSERT INTO dispatch_configs (
  config_key,
  config_value,
  updated_at,
  updated_by
)
SELECT
  'whitelist_filter_enabled',
  'false',
  CURRENT_TIMESTAMP(6),
  'migration'
WHERE NOT EXISTS (
  SELECT 1 FROM dispatch_configs WHERE config_key = 'whitelist_filter_enabled'
);
