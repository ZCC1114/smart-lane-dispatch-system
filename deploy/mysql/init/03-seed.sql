USE smart_lane_dispatch;

INSERT INTO user_accounts (
  username,
  display_name,
  role,
  station,
  password_hash,
  system_protected
) VALUES (
  'admin',
  '系统管理员',
  'ADMIN',
  '总控中心',
  '$2y$10$s0mThDNemgGxyDtwKoulGexKZcCKntiE6pSE/LatVAijk/14WBm2W',
  b'1'
) ON DUPLICATE KEY UPDATE
  display_name = VALUES(display_name),
  role = VALUES(role),
  station = VALUES(station),
  password_hash = VALUES(password_hash),
  system_protected = VALUES(system_protected);

INSERT INTO lanes (
  id,
  code,
  name,
  zone,
  type,
  status,
  mode,
  capacity,
  vehicle_count,
  current_plate,
  last_action_at,
  priority,
  sensor_status,
  last_sensor_at,
  last_entry_plate,
  last_entry_at
) VALUES
  ('L01', 'L01', '1号车道',  'A区', 'ENTRY', 'OPEN', 'AUTO', 6, 0, NULL, CURRENT_TIMESTAMP(6), b'0', 'ONLINE', CURRENT_TIMESTAMP(6), NULL, NULL),
  ('L02', 'L02', '2号车道',  'A区', 'ENTRY', 'OPEN', 'AUTO', 6, 0, NULL, CURRENT_TIMESTAMP(6), b'0', 'ONLINE', CURRENT_TIMESTAMP(6), NULL, NULL),
  ('L03', 'L03', '3号车道',  'A区', 'ENTRY', 'OPEN', 'AUTO', 6, 0, NULL, CURRENT_TIMESTAMP(6), b'0', 'ONLINE', CURRENT_TIMESTAMP(6), NULL, NULL),
  ('L04', 'L04', '4号车道',  'A区', 'ENTRY', 'OPEN', 'AUTO', 6, 0, NULL, CURRENT_TIMESTAMP(6), b'0', 'ONLINE', CURRENT_TIMESTAMP(6), NULL, NULL),
  ('L05', 'L05', '5号车道',  'A区', 'ENTRY', 'OPEN', 'AUTO', 6, 0, NULL, CURRENT_TIMESTAMP(6), b'0', 'ONLINE', CURRENT_TIMESTAMP(6), NULL, NULL),
  ('L06', 'L06', '6号车道',  'A区', 'ENTRY', 'OPEN', 'AUTO', 6, 0, NULL, CURRENT_TIMESTAMP(6), b'0', 'ONLINE', CURRENT_TIMESTAMP(6), NULL, NULL),
  ('L07', 'L07', '7号车道',  'A区', 'ENTRY', 'OPEN', 'AUTO', 6, 0, NULL, CURRENT_TIMESTAMP(6), b'0', 'ONLINE', CURRENT_TIMESTAMP(6), NULL, NULL),
  ('L08', 'L08', '8号车道',  'A区', 'ENTRY', 'OPEN', 'AUTO', 6, 0, NULL, CURRENT_TIMESTAMP(6), b'0', 'ONLINE', CURRENT_TIMESTAMP(6), NULL, NULL),
  ('L09', 'L09', '9号车道',  'A区', 'ENTRY', 'OPEN', 'AUTO', 6, 0, NULL, CURRENT_TIMESTAMP(6), b'0', 'ONLINE', CURRENT_TIMESTAMP(6), NULL, NULL),
  ('L10', 'L10', '10号车道', 'A区', 'ENTRY', 'OPEN', 'AUTO', 6, 0, NULL, CURRENT_TIMESTAMP(6), b'0', 'ONLINE', CURRENT_TIMESTAMP(6), NULL, NULL),
  ('L11', 'L11', '11号车道', 'A区', 'ENTRY', 'OPEN', 'AUTO', 6, 0, NULL, CURRENT_TIMESTAMP(6), b'0', 'ONLINE', CURRENT_TIMESTAMP(6), NULL, NULL)
ON DUPLICATE KEY UPDATE
  code = VALUES(code),
  name = VALUES(name),
  zone = VALUES(zone),
  type = VALUES(type),
  capacity = VALUES(capacity),
  sensor_status = VALUES(sensor_status);

INSERT INTO dispatch_configs (
  config_key,
  config_value,
  updated_at,
  updated_by
) VALUES
  ('entry_lane_order', '1-11', CURRENT_TIMESTAMP(6), 'seed'),
  ('entry_dispatch_enabled', 'false', CURRENT_TIMESTAMP(6), 'seed'),
  ('exit_dispatch_enabled', 'false', CURRENT_TIMESTAMP(6), 'seed'),
  ('assignment_reserve_minutes', '2', CURRENT_TIMESTAMP(6), 'seed')
ON DUPLICATE KEY UPDATE
  config_value = VALUES(config_value),
  updated_at = VALUES(updated_at),
  updated_by = VALUES(updated_by);
