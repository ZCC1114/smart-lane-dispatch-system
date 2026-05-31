SET @schema_name = DATABASE();

SET @index_exists = (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = @schema_name
    AND table_name = 'entry_logs'
    AND index_name = 'idx_entry_logs_time_lane_plate'
);
SET @sql = IF(
  @index_exists = 0,
  'ALTER TABLE entry_logs ADD INDEX idx_entry_logs_time_lane_plate (entry_time, lane_id, plate)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = @schema_name
    AND table_name = 'dispatch_tickets'
    AND index_name = 'idx_dispatch_tickets_plate_time'
);
SET @sql = IF(
  @index_exists = 0,
  'ALTER TABLE dispatch_tickets ADD INDEX idx_dispatch_tickets_plate_time (plate, yard_entry_time)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = @schema_name
    AND table_name = 'dispatch_tickets'
    AND index_name = 'idx_dispatch_tickets_event_window'
);
SET @sql = IF(
  @index_exists = 0,
  'ALTER TABLE dispatch_tickets ADD INDEX idx_dispatch_tickets_event_window (yard_entry_time, status, plate)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = @schema_name
    AND table_name = 'blacklist_records'
    AND index_name = 'idx_blacklist_active_plate_effective'
);
SET @sql = IF(
  @index_exists = 0,
  'ALTER TABLE blacklist_records ADD INDEX idx_blacklist_active_plate_effective (active, plate, effective_date)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
