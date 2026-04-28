export type UserRole = "ADMIN" | "DISPATCHER" | "VIEWER";

export type LaneType = "ENTRY" | "EXIT" | "MIXED";
export type LaneStatus = "OPEN" | "BUSY" | "FULL" | "OFFLINE";
export type LaneMode = "AUTO" | "MANUAL" | "OFFLINE";
export type SignalState = "RED" | "YELLOW" | "GREEN" | "OFFLINE";
export type SensorStatus = "ONLINE" | "DEGRADED" | "OFFLINE";
export type AlertLevel = "INFO" | "WARNING" | "DANGER" | "CRITICAL";
export type AlertStatus = "OPEN" | "ACKNOWLEDGED" | "RESOLVED";
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

export interface DashboardMetric {
  totalPassages: number;
  averagePassMinutes: number;
  blacklistAlerts: number;
  systemHealth: number;
  activeLaneRate: number;
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
}

export interface AlertItem {
  id: string;
  laneId: string;
  laneName: string;
  plate: string | null;
  type: string;
  level: AlertLevel;
  status: AlertStatus;
  message: string;
  createdAt: string;
}

export interface EntryLog {
  id: string;
  plate: string;
  laneId: string;
  laneName: string;
  entryTime: string;
  exitTime: string | null;
  vehicleType: string;
  status: LogStatus;
  source: string;
  operator: string;
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
  metrics: DashboardMetric;
  throughput: ThroughputPoint[];
  lanes: LaneSnapshot[];
  alerts: AlertItem[];
}

export interface SignalOverrideRequest {
  laneId: string;
  entrySignal: SignalState;
  exitSignal: SignalState;
  mode: LaneMode;
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

export interface BlacklistPayload {
  plate: string;
  reason: string;
  level: BlacklistLevel;
  operator: string;
  active: boolean;
}

export interface TcpDidoRelayRequest {
  host: string;
  port: number;
  relay: string;
  on: boolean;
  protocol?: "A1" | "A3";
}

export interface TcpDidoRelayResponse {
  host: string;
  port: number;
  relay: string;
  on: boolean;
  protocol: "A1" | "A3";
  commandHex: string;
  responseHex: string;
  responseText: string;
  responseReceived: boolean;
  message: string;
}
