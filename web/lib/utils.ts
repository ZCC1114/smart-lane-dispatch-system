import { clsx } from "clsx";
import { format } from "date-fns";
import type {
  AlertLevel,
  AlertStatus,
  BlacklistLevel,
  LaneMode,
  LaneStatus,
  SensorStatus,
  LaneType,
  LogStatus,
  SignalState,
} from "@/lib/types";

export function cn(...values: Array<string | false | null | undefined>) {
  return clsx(values);
}

export function formatDateTime(value: string) {
  return format(new Date(value), "yyyy-MM-dd HH:mm:ss");
}

export function formatShortTime(value: string) {
  return format(new Date(value), "HH:mm:ss");
}

export function laneTypeLabel(type: LaneType) {
  if (type === "ENTRY") return "入口";
  if (type === "EXIT") return "出口";
  return "混合";
}

export function laneStatusLabel(status: LaneStatus) {
  if (status === "OPEN") return "正常运行";
  if (status === "BUSY") return "高负载";
  if (status === "FULL") return "满停锁定";
  return "离线";
}

export function laneModeLabel(mode: LaneMode) {
  if (mode === "AUTO") return "自动";
  if (mode === "MANUAL") return "手动";
  return "离线";
}

export function signalLabel(signal: SignalState) {
  if (signal === "GREEN") return "绿灯";
  if (signal === "YELLOW") return "黄灯";
  if (signal === "RED") return "红灯";
  return "离线";
}

export function signalColor(signal: SignalState) {
  if (signal === "GREEN") return "bg-emerald-500 text-emerald-400";
  if (signal === "YELLOW") return "bg-amber-500 text-amber-400";
  if (signal === "RED") return "bg-rose-500 text-rose-400";
  return "bg-slate-600 text-slate-500";
}

export function logStatusLabel(status: LogStatus) {
  if (status === "PASSED") return "正常放行";
  if (status === "REJECTED") return "拦截拒绝";
  return "人工处理";
}

export function alertStatusLabel(status: AlertStatus) {
  if (status === "OPEN") return "待处理";
  if (status === "ACKNOWLEDGED") return "已确认";
  return "已处理";
}

export function levelLabel(level: AlertLevel | BlacklistLevel) {
  const mapping: Record<string, string> = {
    INFO: "信息",
    WARNING: "预警",
    DANGER: "危险",
    CRITICAL: "严重",
    LOW: "低",
    MEDIUM: "中",
    HIGH: "高",
  };
  return mapping[level] ?? level;
}

export function sensorStatusLabel(status: SensorStatus | null) {
  if (status === "ONLINE") return "在线";
  if (status === "DEGRADED") return "降级";
  if (status === "OFFLINE") return "离线";
  return "未接入";
}

export function ledStatusLabel(status: "SYNCED" | "PENDING" | "FAILED") {
  if (status === "SYNCED") return "已同步";
  if (status === "PENDING") return "同步中";
  return "同步失败";
}

export function logSourceLabel(source: string) {
  const normalized = source.trim().toUpperCase();
  const mapping: Record<string, string> = {
    ALPR: "车牌识别设备",
    MANUAL: "人工录入",
    CORRECTION: "人工更正",
  };
  return mapping[normalized] ?? source;
}

export function downloadCsv(filename: string, rows: string[][]) {
  const content = rows.map((row) => row.map((item) => `"${item.replaceAll('"', '""')}"`).join(",")).join("\n");
  const blob = new Blob(["\ufeff", content], { type: "text/csv;charset=utf-8;" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.setAttribute("download", filename);
  link.click();
  URL.revokeObjectURL(link.href);
}
