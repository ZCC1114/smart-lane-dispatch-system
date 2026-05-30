SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE smart_lane_dispatch;

CREATE TABLE IF NOT EXISTS lanes (
  id VARCHAR(255) NOT NULL COMMENT '车道主键，系统内部标识，例如 L01',
  code VARCHAR(255) NOT NULL COMMENT '车道编码，前端展示和顺序配置使用，例如 L01',
  name VARCHAR(255) NOT NULL COMMENT '车道名称，例如 1号车道',
  zone VARCHAR(255) NOT NULL COMMENT '车道所属区域，例如 A区',
  type VARCHAR(255) NOT NULL COMMENT '车道类型：ENTRY 入口、EXIT 出口、MIXED 混合',
  status VARCHAR(255) NOT NULL COMMENT '车道运行状态：OPEN 正常、BUSY 高负载、FULL 已满、OFFLINE 离线',
  mode VARCHAR(255) NOT NULL COMMENT '控制模式：AUTO 自动、MANUAL 手动、OFFLINE 离线',
  capacity INT NOT NULL COMMENT '车道容量上限',
  vehicle_count INT NOT NULL COMMENT '当前在场车辆数，可由管理人员手动修正',
  current_plate VARCHAR(255) NULL COMMENT '当前队首或最近关联车牌号',
  last_action_at DATETIME(6) NOT NULL COMMENT '最近一次业务状态更新时间',
  priority BIT(1) NOT NULL COMMENT '是否为下一轮入口优先车道，出口仍按实际入场先后 FIFO 放行',
  sensor_status VARCHAR(255) NULL COMMENT '传感器或设备状态：ONLINE 在线、DEGRADED 降级、OFFLINE 离线',
  last_sensor_at DATETIME(6) NULL COMMENT '最近一次设备或传感器状态更新时间',
  last_entry_plate VARCHAR(255) NULL COMMENT '最近一次入场识别车牌号',
  last_entry_at DATETIME(6) NULL COMMENT '最近一次入场时间',
  queue_head_at DATETIME(6) NULL COMMENT '当前在场队列的队首进入时间；有逐车流水时由 entry_logs 队首兜底，无流水时按首次观测到该批车辆占用的时间记录',
  PRIMARY KEY (id),
  UNIQUE KEY uk_lanes_code (code),
  KEY idx_lanes_mode_status (mode, status),
  KEY idx_lanes_priority (priority),
  KEY idx_lanes_vehicle_count (vehicle_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='车道基础信息与业务统计表，不保存实时红绿灯状态';

CREATE TABLE IF NOT EXISTS dispatch_configs (
  config_key VARCHAR(255) NOT NULL COMMENT '配置键，例如 entry_lane_order、entry_dispatch_enabled、exit_dispatch_enabled、active_entry_lane、active_exit_lane',
  config_value VARCHAR(255) NOT NULL COMMENT '配置值，例如 L01,L02,L03、1-11、true、false',
  updated_at DATETIME(6) NOT NULL COMMENT '配置最近更新时间',
  updated_by VARCHAR(255) NOT NULL COMMENT '最近更新人或来源',
  PRIMARY KEY (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='调度策略配置表';

CREATE TABLE IF NOT EXISTS entry_logs (
  id VARCHAR(255) NOT NULL COMMENT '入场记录主键',
  plate VARCHAR(255) NOT NULL COMMENT '车牌号',
  lane_id VARCHAR(255) NOT NULL COMMENT '入场车道 ID',
  lane_name VARCHAR(255) NOT NULL COMMENT '入场车道名称快照',
  entry_time DATETIME(6) NOT NULL COMMENT '入场时间',
  exit_time DATETIME(6) NULL COMMENT '离场时间，NULL 表示仍在场',
  vehicle_type VARCHAR(255) NOT NULL COMMENT '车辆类型，例如 出租车、社会车辆',
  status VARCHAR(255) NOT NULL COMMENT '通行状态：PASSED 正常、REJECTED 拒绝、MANUAL 人工',
  source VARCHAR(255) NOT NULL COMMENT '记录来源：ALPR 车牌识别、SMART_CAMERA 智能相机、MANUAL 人工等',
  operator VARCHAR(255) NOT NULL COMMENT '操作人或采集来源说明',
  PRIMARY KEY (id),
  KEY idx_entry_logs_lane_fifo (lane_id, exit_time, entry_time),
  KEY idx_entry_logs_plate (plate),
  KEY idx_entry_logs_entry_time (entry_time),
  KEY idx_entry_logs_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='车辆入场与在场队列记录表';

CREATE TABLE IF NOT EXISTS dispatch_tickets (
  id VARCHAR(255) NOT NULL COMMENT '总入口预分配记录主键',
  plate VARCHAR(255) NOT NULL COMMENT '车牌号',
  yard_entry_time DATETIME(6) NOT NULL COMMENT '车辆进入蓄车池场地时间',
  assigned_lane_id VARCHAR(255) NULL COMMENT '大屏推荐进入的目标车道 ID',
  assigned_lane_name VARCHAR(255) NULL COMMENT '目标车道名称快照',
  assigned_at DATETIME(6) NULL COMMENT '完成预分配时间',
  actual_lane_id VARCHAR(255) NULL COMMENT '入口抓拍识别到的实际进入车道 ID',
  actual_lane_name VARCHAR(255) NULL COMMENT '实际进入车道名称快照',
  lane_entry_time DATETIME(6) NULL COMMENT '车辆实际进入车道时间',
  exit_time DATETIME(6) NULL COMMENT '车辆从车道驶出时间',
  closed_at DATETIME(6) NULL COMMENT '该条预分配记录结束时间，如过期、日清或已驶出',
  vehicle_type VARCHAR(255) NOT NULL COMMENT '车辆类型，例如 出租车',
  status VARCHAR(255) NOT NULL COMMENT '状态：ASSIGNED、ENTERED、ENTERED_MISMATCH、DIRECT_ENTERED、EXITED、EXPIRED、RESET、NO_LANE_AVAILABLE',
  source VARCHAR(255) NOT NULL COMMENT '来源，例如 YARD_CAMERA、LANE_CAMERA、MANUAL',
  operator VARCHAR(255) NOT NULL COMMENT '操作来源说明',
  notes VARCHAR(255) NULL COMMENT '补充说明，如未按屏显进入推荐车道',
  PRIMARY KEY (id),
  KEY idx_dispatch_tickets_plate (plate),
  KEY idx_dispatch_tickets_assigned_lane (assigned_lane_id, lane_entry_time, closed_at),
  KEY idx_dispatch_tickets_actual_lane (actual_lane_id, exit_time, closed_at),
  KEY idx_dispatch_tickets_yard_entry_time (yard_entry_time),
  KEY idx_dispatch_tickets_status (status, closed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='总入口抓拍、大屏预分配、实际入道和驶出闭环记录表';

CREATE TABLE IF NOT EXISTS blacklist_records (
  id VARCHAR(255) NOT NULL COMMENT '黑名单记录主键',
  plate VARCHAR(255) NOT NULL COMMENT '黑名单车牌号',
  reason VARCHAR(255) NOT NULL COMMENT '加入黑名单原因',
  level VARCHAR(255) NOT NULL COMMENT '风险等级：LOW 低、MEDIUM 中、HIGH 高、CRITICAL 严重',
  effective_date DATETIME(6) NOT NULL COMMENT '生效时间',
  operator VARCHAR(255) NOT NULL COMMENT '维护操作人',
  active BIT(1) NOT NULL COMMENT '是否启用',
  PRIMARY KEY (id),
  KEY idx_blacklist_plate_active (plate, active),
  KEY idx_blacklist_effective_date (effective_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='车辆黑名单记录表';

CREATE TABLE IF NOT EXISTS screen_handled_events (
  id VARCHAR(255) NOT NULL COMMENT '大屏告警事件 ID，例如 BL-调度单号、WL-调度单号、DV-车道设备',
  handled_at DATETIME(6) NOT NULL COMMENT '处理确认时间',
  operator VARCHAR(255) NOT NULL COMMENT '处理来源或操作人',
  PRIMARY KEY (id),
  KEY idx_screen_handled_events_handled_at (handled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='大屏已处理告警记录表';

CREATE TABLE IF NOT EXISTS screen_acknowledged_events (
  id VARCHAR(255) NOT NULL COMMENT '大屏告警事件 ID，例如 BL-调度单号、WL-调度单号、DV-车道设备',
  acknowledged_at DATETIME(6) NOT NULL COMMENT '大屏弹窗确认时间',
  operator VARCHAR(255) NOT NULL COMMENT '确认来源或操作人',
  PRIMARY KEY (id),
  KEY idx_screen_acknowledged_events_acknowledged_at (acknowledged_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='大屏已确认展示告警记录表';

CREATE TABLE IF NOT EXISTS user_accounts (
  username VARCHAR(255) NOT NULL COMMENT '登录用户名',
  display_name VARCHAR(255) NOT NULL COMMENT '用户显示名称',
  role VARCHAR(255) NOT NULL COMMENT '用户角色：ADMIN 管理员、DISPATCHER 调度员、VIEWER 观察员',
  station VARCHAR(255) NOT NULL COMMENT '所属岗位或站点',
  password_hash VARCHAR(255) NOT NULL COMMENT 'BCrypt 密码哈希',
  system_protected BIT(1) NOT NULL COMMENT '是否为系统保护账号',
  PRIMARY KEY (username),
  KEY idx_user_accounts_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台用户账号表';
