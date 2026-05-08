import { clsx } from "clsx";
import { format } from "date-fns";
import type { BlacklistLevel, DispatchTicket, LaneMode, LaneStatus, SensorStatus, LaneType, LogStatus, ScreenEventType, SignalState } from "@/lib/types";

export function cn(...values: Array<string | false | null | undefined>) {
  return clsx(values);
}

export function formatDateTime(value: string) {
  return format(new Date(value), "yyyy-MM-dd HH:mm:ss");
}

export function formatShortTime(value: string) {
  return format(new Date(value), "HH:mm:ss");
}

export function formatPlateDisplay(plate?: string | null) {
  if (!plate) {
    return "";
  }

  const trimmed = plate.trim();
  if (!trimmed) {
    return "";
  }

  const compact = trimmed.replace(/[·•・．。\-.\s]/g, "");
  const normalized = `${compact.slice(0, 1)}${compact.slice(1).toUpperCase()}`;
  if (!/^[\u4e00-\u9fff][A-Z][A-Z0-9\u4e00-\u9fff]{5,6}$/.test(normalized)) {
    return trimmed;
  }

  return `${normalized.slice(0, 2)}·${normalized.slice(2)}`;
}

export function laneTypeLabel(type: LaneType) {
  if (type === "ENTRY") return "入口";
  if (type === "EXIT") return "出口";
  return "混合";
}

export function laneStatusLabel(status: LaneStatus) {
  if (status === "OPEN") return "开放中";
  if (status === "BUSY") return "接近满位";
  if (status === "FULL") return "满位待切换";
  return "离线";
}

export function laneModeLabel(mode: LaneMode) {
  if (mode === "AUTO") return "自动";
  if (mode === "MANUAL") return "手动";
  return "离线";
}

export function signalLabel(signal: SignalState) {
  if (signal === "GREEN") return "绿灯";
  if (signal === "RED") return "红灯";
  return "离线";
}

export function signalColor(signal: SignalState) {
  if (signal === "GREEN") return "bg-emerald-500 text-emerald-400";
  if (signal === "RED") return "bg-rose-500 text-rose-400";
  return "bg-slate-600 text-slate-500";
}

export function logStatusLabel(status: LogStatus) {
  if (status === "PASSED") return "正常放行";
  if (status === "REJECTED") return "拦截拒绝";
  return "人工处理";
}

export function levelLabel(level: BlacklistLevel) {
  const mapping: Record<string, string> = {
    LOW: "低",
    MEDIUM: "中",
    HIGH: "高",
    CRITICAL: "严重",
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
    YARD_CAMERA: "场地入口抓拍",
    LANE_CAMERA: "车道入口抓拍",
  };
  return mapping[normalized] ?? source;
}

export function dispatchTicketStatusLabel(status: DispatchTicket["status"] | string) {
  const mapping: Record<string, string> = {
    ASSIGNED: "已推荐车道",
    ENTERED: "已按推荐入道",
    ENTERED_MISMATCH: "错道入场",
    DIRECT_ENTERED: "直接入道",
    EXITED: "已驶出",
    EXPIRED: "预分配超时",
    RESET: "日清关闭",
    NO_LANE_AVAILABLE: "暂无可用车道",
  };
  return mapping[status] ?? status;
}

export function dispatchTicketSourceLabel(source: string) {
  const normalized = source.trim().toUpperCase();
  const mapping: Record<string, string> = {
    YARD_CAMERA: "场地入口抓拍",
    LANE_CAMERA: "车道入口抓拍",
    ALPR: "车牌识别设备",
    MANUAL: "人工录入",
    CORRECTION: "人工更正",
  };
  return mapping[normalized] ?? source;
}

export function screenEventTypeLabel(type: ScreenEventType | string) {
  const mapping: Record<string, string> = {
    blacklist: "黑名单",
    wrong_lane: "走错车道",
    not_entered: "未进车道",
    other: "其他",
  };
  return mapping[type] ?? type;
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
