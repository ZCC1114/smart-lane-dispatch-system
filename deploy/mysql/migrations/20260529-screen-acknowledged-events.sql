-- Upgrade script for existing databases.
-- Safe to run repeatedly.

CREATE TABLE IF NOT EXISTS screen_acknowledged_events (
  id VARCHAR(255) NOT NULL COMMENT '大屏告警事件 ID，例如 BL-调度单号、WL-调度单号、DV-车道设备',
  acknowledged_at DATETIME(6) NOT NULL COMMENT '大屏弹窗确认时间',
  operator VARCHAR(255) NOT NULL COMMENT '确认来源或操作人',
  PRIMARY KEY (id),
  KEY idx_screen_acknowledged_events_acknowledged_at (acknowledged_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='大屏已确认展示告警记录表';
