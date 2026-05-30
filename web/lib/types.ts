export type UserRole = "ADMIN" | "DISPATCHER" | "VIEWER";

export type LaneType = "ENTRY" | "EXIT" | "MIXED";
export type LaneStatus = "OPEN" | "BUSY" | "FULL" | "OFFLINE";
export type LaneMode = "AUTO" | "MANUAL" | "OFFLINE";
export type SignalState = "RED" | "GREEN" | "OFFLINE";
export type SensorStatus = "ONLINE" | "DEGRADED" | "OFFLINE";
export type RelayControlTarget = "ENTRY_RED" | "ENTRY_GREEN" | "EXIT_RED" | "EXIT_GREEN";
export type LogStatus = "PASSED" | "REJECTED" | "MANUAL";
export type BlacklistLevel = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
export type DispatchCommandType =
  | "FORCE_OPEN_GATE"
  | "MANUAL_ENTRY"
  | "PLATE_CORRECTION"
  | "TEMP_ALLOW"
  | "CORRECT_COUNT"
  | "SET_PRIORITY";

export interface UserSession {
  username: string;
  displayName: string;
  role: UserRole;
  station: string;
}

export interface AuthResponse {
  token: string;
  expiresAt: string;
  user: UserSession;
}

export interface ThroughputPoint {
  label: string;
  count: number;
}

export interface LaneSnapshot {
  id: string;
  code: string;
  name: string;
  zone: string;
  type: LaneType;
  status: LaneStatus;
  mode: LaneMode;
  capacity: number;
  vehicleCount: number;
  currentPlate: string | null;
  lastActionAt: string;
  entrySignal: SignalState;
  exitSignal: SignalState;
  ledMessage: string;
  ledStatus: "SYNCED" | "PENDING" | "FAILED";
  priority: boolean;
  sensorStatus: SensorStatus | null;
  lastSensorAt: string | null;
  lastEntryPlate: string | null;
  lastEntryAt: string | null;
  reservedCount: number;
  availableSlots: number;
}

export interface EntryLog {
  id: string;
  plate: string;
  laneId: string | null;
  laneName: string | null;
  assignedLaneId: string | null;
  assignedLaneName: string | null;
  entryTime: string;
  exitTime: string | null;
  vehicleType: string;
  status: LogStatus;
  source: string;
  operator: string;
}

export type ScreenEventType = "blacklist" | "wrong_lane" | "not_entered" | "other";

export interface ScreenEvent {
  id: string;
  type: ScreenEventType;
  plate: string;
  message: string;
  occurredAt: string;
  sourceId: string | null;
  sourceName: string | null;
  acknowledged: boolean;
  acknowledgedAt: string | null;
  handled: boolean;
  handledAt: string | null;
}

export interface BlacklistRecord {
  id: string;
  plate: string;
  reason: string;
  level: BlacklistLevel;
  effectiveDate: string;
  operator: string;
  active: boolean;
}

export interface DashboardPayload {
  generatedAt: string;
  throughput: ThroughputPoint[];
  lanes: LaneSnapshot[];
  dispatchBoard?: DispatchBoard;
}

export interface SignalOverrideRequest {
  laneId: string;
  entrySignal: SignalState;
  exitSignal: SignalState;
  mode?: LaneMode;
  reason: string;
}

export interface RelayControlRequest {
  laneId: string;
  target: RelayControlTarget;
  on: boolean;
  reason: string;
}

export interface ManualDispatchRequest {
  laneId: string;
  commandType: DispatchCommandType;
  plate?: string;
  reason: string;
  vehicleType?: string;
  correctedVehicleCount?: number;
  markPriority?: boolean;
}

export interface DispatchConfig {
  entryLaneOrder: string;
  entryDispatchEnabled: boolean;
  exitDispatchEnabled: boolean;
  activeEntryLaneId?: string | null;
  activeExitLaneId?: string | null;
  assignmentReserveMinutes?: number;
}

export interface DispatchRuntimeState {
  entryDispatchEnabled: boolean;
  exitDispatchEnabled: boolean;
}

export interface DispatchTicket {
  id: string;
  plate: string;
  yardEntryTime: string;
  assignedLaneId: string | null;
  assignedLaneName: string | null;
  assignedAt: string | null;
  actualLaneId: string | null;
  actualLaneName: string | null;
  laneEntryTime: string | null;
  exitTime: string | null;
  closedAt: string | null;
  vehicleType: string;
  status:
    | "ASSIGNED"
    | "ENTERED"
    | "ENTERED_MISMATCH"
    | "DIRECT_ENTERED"
    | "EXITED"
    | "EXPIRED"
    | "RESET"
    | "NO_LANE_AVAILABLE";
  source: string;
  operator: string;
  notes: string | null;
}

export interface DispatchBoard {
  generatedAt: string;
  activeEntryLaneId: string | null;
  activeExitLaneId: string | null;
  activeEntryLaneName: string | null;
  activeExitLaneName: string | null;
  entryDispatchEnabled: boolean;
  exitDispatchEnabled: boolean;
  waitingAssignments: DispatchTicket[];
  recentDispatches: DispatchTicket[];
}

export interface BlacklistPayload {
  plate: string;
  reason: string;
  level: BlacklistLevel;
  operator: string;
  active: boolean;
}
