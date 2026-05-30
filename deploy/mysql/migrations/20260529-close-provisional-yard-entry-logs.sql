-- Close legacy provisional entry_logs that were created when a yard-entry guide
-- was assigned, before any lane-entry camera actually confirmed the vehicle.
UPDATE entry_logs log
JOIN dispatch_tickets ticket
  ON ticket.plate = log.plate
 AND ticket.yard_entry_time = log.entry_time
 AND ticket.assigned_lane_id = log.lane_id
SET log.exit_time = COALESCE(ticket.closed_at, UTC_TIMESTAMP(6))
WHERE log.exit_time IS NULL
  AND ticket.lane_entry_time IS NULL
  AND ticket.actual_lane_id IS NULL
  AND ticket.status IN ('ASSIGNED', 'EXPIRED', 'NO_LANE_AVAILABLE')
  AND UPPER(log.source) IN ('ALPR_YARD', 'SMART_CAMERA', 'SCREEN_SIMULATION', 'YARD-CAMERA', 'YARD_CAMERA');
