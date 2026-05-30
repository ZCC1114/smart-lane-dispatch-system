/* eslint-disable @next/next/no-img-element */
"use client";

import { type CSSProperties, useEffect, useMemo, useRef, useState } from "react";
import { RotateCcw, Settings2, Shield, TriangleAlert, Maximize2, Minimize2 } from "lucide-react";
import { useRouter } from "next/navigation";
import { formatPlateDisplay, screenEventTypeLabel } from "@/lib/utils";
import { useDashboardLayoutStore } from "@/stores/dashboard-layout-store";

const DESIGN_WIDTH = 1920;
const DESIGN_HEIGHT = 1080;
const ASSET = "/screen-assets";
const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ??
  (process.env.NODE_ENV === "development" ? "http://localhost:8080/api" : "/api");
const LANE_LEFTS = [404, 508, 611, 714, 817, 920, 1023, 1126, 1229, 1332, 1435];
const LANE_WIDTH = 92;
const LANE_PLATE_SIDE_GAP = 4;
const LANE_PLATE_WIDTH = LANE_WIDTH - LANE_PLATE_SIDE_GAP * 2;
const ENTRY_ROAD_WIDTH = 74;
const ENTRY_ROAD_PLATE_SIDE_GAP = 4;
const ENTRY_ROAD_PLATE_WIDTH = ENTRY_ROAD_WIDTH - ENTRY_ROAD_PLATE_SIDE_GAP * 2;
const BLUE_PLATE_CLASS = "border-2 border-white bg-[#1f6fe5] text-white shadow-[0_0_7px_rgba(0,0,0,0.45)]";
const GREEN_PLATE_CLASS =
  "border-2 border-white bg-[linear-gradient(180deg,#effff3_0%,#4ee773_100%)] text-[#061a0a] shadow-[inset_0_0_9px_rgba(255,255,255,0.72),0_0_7px_rgba(0,0,0,0.45)]";
const ALERT_BUTTON_BLUE_CLASS = "border-[#55ddff] bg-[linear-gradient(180deg,#1fb7e6_0%,#08699d_100%)]";
const ALERT_BUTTON_ORANGE_CLASS = "border-[#ff9366] bg-[linear-gradient(180deg,#c94b32_0%,#7f241b_100%)]";
const LANE_EMPTY_CONFIRMATION_THRESHOLD = 3;
const LANE_EMPTY_CONFIRMATION_INTERVAL_MS = 10 * 60 * 1000;
const ACTION_MESSAGE_TIMEOUT_MS = 6500;
const ALERT_POPUP_WIDTH = 500;
const ALERT_POPUP_HEIGHT = 270;
const ALERT_POPUP_STABLE_POLLS = 2;
const MAX_VISIBLE_ALERT_POPUPS = 4;
const DAILY_RESET_DISMISS_STORAGE_KEY = "smart-lane-screen-daily-reset-dismissed-v1";

interface DispatchTicket {
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
  status: string;
}

interface EntryLog {
  id: string;
  plate: string;
  laneId: string | null;
  laneName: string | null;
  entryTime: string;
  exitTime: string | null;
  vehicleType: string;
  status: string;
  source: string;
  operator: string;
}

interface EntryInfoItem {
  id: string;
  plate: string;
  occurredAt: string;
  laneName: string | null;
}

interface LaneSnapshot {
  id: string;
  code: string;
  name: string;
  status: "OPEN" | "BUSY" | "FULL" | "OFFLINE";
  mode: "AUTO" | "MANUAL" | "OFFLINE";
  capacity: number;
  vehicleCount: number;
  currentPlate: string | null;
  lastActionAt: string;
  lastEntryPlate: string | null;
  entrySignal: "RED" | "GREEN" | "OFFLINE";
  exitSignal: "RED" | "GREEN" | "OFFLINE";
  reservedCount: number;
  availableSlots: number;
}

interface ScreenEvent {
  id: string;
  type: "blacklist" | "wrong_lane" | "not_entered" | "other";
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

interface ScreenBoardData {
  generatedAt: string;
  activeEntryLaneId: string | null;
  activeExitLaneId: string | null;
  entryDispatchEnabled: boolean;
  exitDispatchEnabled: boolean;
  waitingAssignments: DispatchTicket[];
  guideAssignments: DispatchTicket[];
  recentDispatches: DispatchTicket[];
  recentEntryLogs: EntryLog[];
  laneVehicles: Record<string, DispatchTicket[]>;
  pendingEvents: ScreenEvent[];
  events: ScreenEvent[];
  lanes: LaneSnapshot[];
  lastDailyResetAt: string | null;
}

type PopupPosition = { x: number; y: number };

type DailyResetDialog =
  | { source: "manual" }
  | { source: "scheduled"; slotKey: string; label: string };

async function fetchScreenBoard() {
  const response = await fetch(`${API_BASE_URL}/screen/board`);
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
  return (await response.json()) as ScreenBoardData;
}

function useScreenScale(mode: "standalone" | "embedded") {
  const containerRef = useRef<HTMLElement | null>(null);
  const [viewport, setViewport] = useState({ width: DESIGN_WIDTH, height: DESIGN_HEIGHT });

  useEffect(() => {
    function updateViewport() {
      if (mode === "embedded" && containerRef.current) {
        const rect = containerRef.current.getBoundingClientRect();
        setViewport({ width: rect.width, height: rect.height });
        return;
      }

      setViewport({ width: window.innerWidth, height: window.innerHeight });
    }

    updateViewport();
    window.addEventListener("resize", updateViewport);
    if (mode !== "embedded" || !containerRef.current) {
      return () => window.removeEventListener("resize", updateViewport);
    }

    const observer = new ResizeObserver(updateViewport);
    observer.observe(containerRef.current);
    return () => {
      observer.disconnect();
      window.removeEventListener("resize", updateViewport);
    };
  }, [mode]);

  return {
    containerRef,
    scale: Math.min(viewport.width / DESIGN_WIDTH, viewport.height / DESIGN_HEIGHT),
  };
}

function useScreenBoard() {
  const [board, setBoard] = useState<ScreenBoardData | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function loadBoard() {
      try {
        const payload = await fetchScreenBoard();
        if (!cancelled) {
          setBoard(payload);
          setError(null);
        }
      } catch (fetchError) {
        if (!cancelled) {
          setError(fetchError instanceof Error ? fetchError.message : "请求失败");
        }
      }
    }

    loadBoard();
    const timer = window.setInterval(loadBoard, 3000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);

  return { board, error, setBoard };
}

function formatScreenTime(value?: string | null) {
  if (!value) {
    return "--";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return date.toLocaleString("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}

function eventTimeMs(event: ScreenEvent) {
  const timestamp = event.occurredAt ? Date.parse(event.occurredAt) : Number.NaN;
  return Number.isFinite(timestamp) ? timestamp : 0;
}

function formatBeijingDateTime(value: Date) {
  const parts = new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  }).formatToParts(value);
  const mapped = Object.fromEntries(parts.filter((part) => part.type !== "literal").map((part) => [part.type, part.value]));
  return `${mapped.year}-${mapped.month}-${mapped.day}  ${mapped.hour}:${mapped.minute}:${mapped.second}`;
}

function shanghaiDateParts(value: Date) {
  const parts = new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(value);
  return Object.fromEntries(parts.filter((part) => part.type !== "literal").map((part) => [part.type, part.value]));
}

function shanghaiDateKey(value: Date) {
  const parts = shanghaiDateParts(value);
  return `${parts.year}-${parts.month}-${parts.day}`;
}

function dateKeyFromIso(value?: string | null) {
  if (!value) {
    return null;
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  return shanghaiDateKey(date);
}

function loadDismissedDailyResetSlots() {
  if (typeof window === "undefined") {
    return {} as Record<string, true>;
  }
  try {
    const parsed = JSON.parse(window.localStorage.getItem(DAILY_RESET_DISMISS_STORAGE_KEY) ?? "{}") as Record<string, true>;
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

function saveDismissedDailyResetSlots(slots: Record<string, true>) {
  try {
    window.localStorage.setItem(DAILY_RESET_DISMISS_STORAGE_KEY, JSON.stringify(slots));
  } catch {
    // localStorage can be unavailable on locked-down display browsers.
  }
}

function currentDailyResetSlot(value: Date, lastDailyResetAt?: string | null, dismissedSlots: Record<string, true> = {}) {
  const todayKey = shanghaiDateKey(value);
  if (dateKeyFromIso(lastDailyResetAt) === todayKey) {
    return null;
  }

  const parts = shanghaiDateParts(value);
  const hour = Number(parts.hour);
  const minute = Number(parts.minute);
  if (!Number.isFinite(hour) || !Number.isFinite(minute) || hour < 3 || hour > 5 || (hour === 5 && minute >= 30)) {
    return null;
  }

  const slotHour = hour === 5 ? 5 : hour;
  const slotMinute = hour === 5 ? 0 : minute >= 30 ? 30 : 0;
  const slotKey = `${todayKey}-${String(slotHour).padStart(2, "0")}${String(slotMinute).padStart(2, "0")}`;
  if (dismissedSlots[slotKey]) {
    return null;
  }
  return {
    slotKey,
    label: `${String(slotHour).padStart(2, "0")}:${String(slotMinute).padStart(2, "0")}`,
  };
}

function laneNumber(label?: string | null) {
  const matched = label?.match(/\d+/);
  return matched ? `${Number.parseInt(matched[0], 10)}车道` : "--车道";
}

function extractLaneOrder(lane: LaneSnapshot) {
  const matched = `${lane.code} ${lane.name}`.match(/\d+/);
  return matched ? Number.parseInt(matched[0], 10) : Number.MAX_SAFE_INTEGER;
}

function buildDisplayLanes(lanes: LaneSnapshot[]) {
  const ordered = [...lanes].sort((left, right) => {
    const diff = extractLaneOrder(left) - extractLaneOrder(right);
    return diff !== 0 ? diff : `${left.code}-${left.name}`.localeCompare(`${right.code}-${right.name}`, "zh-CN");
  });
  return Array.from({ length: 11 }, (_, index) => ordered[index] ?? null);
}

function laneSignalState(signal: LaneSnapshot["entrySignal"] | LaneSnapshot["exitSignal"] | null | undefined) {
  if (!signal || signal === "OFFLINE") {
    return "yellow";
  }
  if (signal === "RED") {
    return "red";
  }
  return "green";
}

function laneRemainingCount(lane: LaneSnapshot, laneVehicles: ScreenBoardData["laneVehicles"]) {
  return Math.max(lane.vehicleCount, laneVehicles[lane.id]?.length ?? 0);
}

function laneLastActionAtMs(lane: LaneSnapshot) {
  const value = Date.parse(lane.lastActionAt);
  return Number.isFinite(value) ? value : 0;
}

function isLaneBaseClearable(lane: LaneSnapshot, board: ScreenBoardData, laneVehicles: ScreenBoardData["laneVehicles"]) {
  const remainingCount = laneRemainingCount(lane, laneVehicles);
  return Boolean(
    board.exitDispatchEnabled &&
      lane.id === board.activeExitLaneId &&
      lane.id !== board.activeEntryLaneId &&
      lane.entrySignal !== "GREEN" &&
      lane.exitSignal === "GREEN" &&
      remainingCount > 0 &&
      remainingCount <= LANE_EMPTY_CONFIRMATION_THRESHOLD,
  );
}

function plateTone(plate?: string | null) {
  if (!plate) {
    return "green";
  }
  const normalized = plate.replace(/[·.\-\s]/g, "").toUpperCase();
  const body = /^[\u4e00-\u9fa5][A-Z]/.test(normalized) ? normalized.slice(2) : normalized;
  if (body.length === 5) {
    return "blue";
  }
  if (body.length === 6) {
    return "green";
  }
  return "green";
}

function screenPlateText(plate: string) {
  return formatPlateDisplay(plate) || plate;
}

function estimatePlateTextUnits(text: string) {
  return Array.from(text).reduce((total, char) => {
    if (/[\u4e00-\u9fff]/.test(char)) {
      return total + 1;
    }
    if (/[·•・．。.\-\s]/.test(char)) {
      return total + 0.35;
    }
    return total + 0.62;
  }, 0);
}

function plateFontSize(text: string, plateWidth: number, maxFontSize: number, minFontSize: number) {
  const usableWidth = plateWidth - 6;
  const fittedFontSize = Math.floor(usableWidth / Math.max(estimatePlateTextUnits(text), 1));
  return Math.max(minFontSize, Math.min(maxFontSize, fittedFontSize));
}

function screenScrollStyle(rowCount: number, rowHeight: number): CSSProperties {
  return {
    "--screen-scroll-distance": `${rowCount * rowHeight}px`,
    "--screen-scroll-duration": `${Math.max(14, rowCount * 2.4)}s`,
  } as CSSProperties;
}

function clampPopupPosition(position: PopupPosition) {
  return {
    x: Math.max(390, Math.min(DESIGN_WIDTH - ALERT_POPUP_WIDTH - 330, position.x)),
    y: Math.max(130, Math.min(DESIGN_HEIGHT - ALERT_POPUP_HEIGHT - 90, position.y)),
  };
}

function defaultAlertPopupPosition(index: number) {
  return clampPopupPosition({
    x: 690 + index * 38,
    y: 315 + index * 34,
  });
}

function Asset({
  name,
  className,
  style,
}: {
  name: string;
  className?: string;
  style?: React.CSSProperties;
}) {
  return <img src={`${ASSET}/${name}`} alt="" draggable={false} className={className} style={style} />;
}

function Signal({ x, y, state = "green" }: { x: number; y: number; state?: "red" | "yellow" | "green" }) {
  const file = state === "red" ? "hong.png" : state === "yellow" ? "huang.png" : "绿灯.png";
  return <Asset name={file} className="absolute h-[17px] w-[60px]" style={{ left: x, top: y }} />;
}

function InlinePlate({
  text,
  tone = "green",
  scale = "normal",
}: {
  text: string;
  tone?: "green" | "blue";
  scale?: "normal" | "large" | "entry";
}) {
  return (
    <span
      className={[
        "inline-flex items-center justify-center font-mono font-black leading-none",
        scale === "large" ? "h-[36px] min-w-[136px] px-3 text-[20px]" : scale === "entry" ? "h-[34px] w-[124px] px-2 text-[17px]" : "h-[23px] min-w-[88px] px-2 text-[14px]",
        tone === "green" ? GREEN_PLATE_CLASS : BLUE_PLATE_CLASS,
      ].join(" ")}
    >
      {screenPlateText(text)}
    </span>
  );
}

function Panel({
  title,
  x,
  y,
  size = "small",
  children,
}: {
  title: string;
  x: number;
  y: number;
  size?: "small" | "large";
  children: React.ReactNode;
}) {
  const image = size === "large" ? "Frame 1321315113.png" : "Frame 1321315113(1).png";
  const width = size === "large" ? 292 : 359;
  const height = size === "large" ? 382 : 208;

  return (
    <section className="absolute overflow-hidden" style={{ left: x, top: y, width, height }}>
      <Asset name={image} className="absolute inset-0 h-full w-full" />
      <h2 className="absolute left-[22px] top-[12px] text-[25px] font-black tracking-[0.03em] text-white [text-shadow:0_0_8px_rgba(102,221,255,0.8)]">
        {title}
      </h2>
      <div className="absolute left-[12px] right-[12px] top-[58px]">{children}</div>
    </section>
  );
}

function AlertRow({
  event,
  blue = false,
  onHandle,
}: {
  event?: ScreenEvent;
  blue?: boolean;
  onHandle: (event: ScreenEvent) => void;
}) {
  const handled = event?.handled ?? false;
  const blueRow = blue || handled;

  return (
    <div className="relative mb-[9px] h-[40px] overflow-hidden">
      <Asset name="Frame 427319724.png" className={["absolute inset-0 h-[40px] w-[335px]", blueRow ? "opacity-30" : ""].join(" ")} />
      {blueRow ? (
        <div className="absolute inset-0 h-[40px] w-[335px] border border-[#28d8ff]/75 bg-[linear-gradient(90deg,rgba(14,125,199,0.68),rgba(11,67,124,0.5))] shadow-[inset_0_0_16px_rgba(40,216,255,0.24)]" />
      ) : null}
      <span
        className={[
          "absolute left-[7px] top-[8px] grid size-[11px] place-items-center rounded-full text-[9px] font-bold text-white",
          blueRow ? "bg-[#16d7ff]" : "bg-[#ff8c22]",
        ].join(" ")}
      >
        !
      </span>
      <p className="absolute left-[24px] top-[6px] w-[226px] truncate text-[12px] font-bold leading-[14px] text-white">
        {event ? `事件：${formatPlateDisplay(event.plate) || event.plate} ${event.message}` : "暂无事件"}
      </p>
      <p className="absolute left-[24px] top-[22px] text-[12px] leading-[14px] text-[#bec7d0]">时间： {event ? formatScreenTime(event.occurredAt) : "--"}</p>
      {event ? (
        <button
          type="button"
          disabled={handled}
          onClick={() => {
            if (!handled) {
              onHandle(event);
            }
          }}
          className={[
            "absolute right-[5px] top-[9px] flex h-[22px] w-[64px] items-center justify-center whitespace-nowrap border text-[10px] font-bold leading-none text-white shadow-[0_0_8px_rgba(0,0,0,0.35)]",
            blueRow ? ALERT_BUTTON_BLUE_CLASS : ALERT_BUTTON_ORANGE_CLASS,
          ].join(" ")}
        >
          {handled ? "已处理" : "处理"}
        </button>
      ) : null}
    </div>
  );
}

function EventRows({
  events,
  type,
  blue = false,
  onHandle,
}: {
  events: ScreenEvent[];
  type: ScreenEvent["type"];
  blue?: boolean;
  onHandle: (event: ScreenEvent) => void;
}) {
  const rows = events.filter((event) => event.type === type).slice(0, 10);
  const visibleRows = 3;
  const rowHeight = 49;

  if (rows.length === 0) {
    return (
      <>
        {Array.from({ length: visibleRows }).map((_, index) => (
          <AlertRow key={`${type}-${index}`} blue={blue} onHandle={onHandle} />
        ))}
      </>
    );
  }

  const displayRows = rows.length > visibleRows ? [...rows, ...rows] : rows;

  return (
    <div className="screen-scroll-viewport" style={{ height: visibleRows * rowHeight }}>
      <div className={rows.length > visibleRows ? "screen-scroll-track" : undefined} style={rows.length > visibleRows ? screenScrollStyle(rows.length, rowHeight) : undefined}>
        {displayRows.map((event, index) => (
          <AlertRow key={`${event.id}-${index}`} event={event} blue={blue} onHandle={onHandle} />
        ))}
        {rows.length < visibleRows
          ? Array.from({ length: visibleRows - rows.length }).map((_, index) => (
              <AlertRow key={`${type}-empty-${index}`} blue={blue} onHandle={onHandle} />
            ))
          : null}
      </div>
    </div>
  );
}

function EntryInfoRows({ entries }: { entries: EntryInfoItem[] }) {
  const rows = entries.slice(0, 10);
  const visibleRows = 6;
  const rowHeight = 52;
  const displayRows = rows.length > visibleRows ? [...rows, ...rows] : rows;

  return (
    <div className="screen-scroll-viewport" style={{ height: visibleRows * rowHeight }}>
      <div className={rows.length > visibleRows ? "screen-scroll-track" : undefined} style={rows.length > visibleRows ? screenScrollStyle(rows.length, rowHeight) : undefined}>
        {displayRows.map((entry, index) => (
          <div key={`${entry.id}-${index}`} className="mb-[7px] grid h-[45px] grid-cols-[124px_1fr] items-center gap-[7px] border-b border-[#4d7593]/80">
            <InlinePlate text={entry.plate} tone={plateTone(entry.plate)} scale="entry" />
            <div className="min-w-0 text-[11px] leading-[16px] text-white">
              <p className="whitespace-nowrap">时间:{formatScreenTime(entry.occurredAt)}</p>
              {entry.laneName ? <p className="truncate text-[#9cecff]">{entry.laneName}</p> : null}
            </div>
          </div>
        ))}
        {rows.length === 0 ? <div className="pt-6 text-center text-[16px] text-white/70">暂无入场车辆</div> : null}
      </div>
    </div>
  );
}

function buildEntryInfoItems(board: ScreenBoardData | null): EntryInfoItem[] {
  if (!board) {
    return [];
  }

  if (board.recentDispatches?.length) {
    return board.recentDispatches.map((ticket) => ({
      id: ticket.id,
      plate: ticket.plate,
      occurredAt: ticket.yardEntryTime,
      laneName: ticket.assignedLaneName ?? ticket.actualLaneName ?? null,
    }));
  }

  return (board.recentEntryLogs ?? []).map((entry) => ({
    id: entry.id,
    plate: entry.plate,
    occurredAt: entry.entryTime,
    laneName: entry.laneName ?? null,
  }));
}

function GuideRows({ tickets }: { tickets: DispatchTicket[] }) {
  const rows = [...tickets]
    .sort((left, right) => {
      const leftTime = new Date(left.assignedAt ?? left.yardEntryTime).getTime();
      const rightTime = new Date(right.assignedAt ?? right.yardEntryTime).getTime();
      return rightTime - leftTime;
    })
    .slice(0, 10);
  const visibleRows = 6;
  const rowHeight = 54;
  const displayRows = rows.length > visibleRows ? [...rows, ...rows] : rows;

  return (
    <div className="screen-scroll-viewport" style={{ height: visibleRows * rowHeight }}>
      <div className={rows.length > visibleRows ? "screen-scroll-track" : undefined} style={rows.length > visibleRows ? screenScrollStyle(rows.length, rowHeight) : undefined}>
        {displayRows.map((ticket, index) => (
          <div key={`${ticket.id}-${index}`} className="relative mb-[15px] h-[39px]">
            <div className="absolute left-0 top-[3px]">
              <InlinePlate text={ticket.plate} tone={plateTone(ticket.plate)} scale="large" />
            </div>
            <GuideTriangle className="left-[150px] top-[8px]" />
            <div className="absolute left-[174px] top-0 flex h-[39px] w-[94px] items-center justify-center border border-[#f1a21f] bg-[linear-gradient(180deg,#df9823_0%,#995100_100%)] text-[23px] font-black leading-none text-white shadow-[inset_0_0_12px_rgba(255,255,255,0.25)]">
              <GuideButtonTriangle className="left-[7px] top-[14px]" />
              <span className="relative z-10">{laneNumber(ticket.assignedLaneName)}</span>
              <GuideButtonTriangle className="right-[7px] top-[14px]" direction="left" />
            </div>
          </div>
        ))}
        {rows.length === 0 ? <div className="pt-6 text-center text-[16px] text-white/70">暂无引导车辆</div> : null}
      </div>
    </div>
  );
}

function GuideTriangle({ className = "", direction = "right" }: { className?: string; direction?: "left" | "right" }) {
  const width = 18;
  const height = 22;
  const clipPath = "polygon(0 0, 58% 0, 100% 50%, 58% 100%, 0 100%, 34% 50%)";
  const outerStyle: CSSProperties = {
    width,
    height,
    clipPath,
    background: "linear-gradient(180deg,#ffe889 0%,#f3b72d 52%,#8d4b05 100%)",
    transform: direction === "left" ? "rotate(180deg)" : undefined,
  };
  const innerStyle: CSSProperties = {
    left: 3,
    top: 3,
    width: 12,
    height: 16,
    clipPath,
    background: "linear-gradient(180deg,#d88911 0%,#7a3d02 100%)",
  };

  return (
    <span className={["absolute drop-shadow-[0_0_4px_rgba(255,195,59,0.85)]", className].join(" ")} style={outerStyle}>
      <span className="absolute" style={innerStyle} />
    </span>
  );
}

function GuideButtonTriangle({ className = "", direction = "right" }: { className?: string; direction?: "left" | "right" }) {
  const shape =
    direction === "right"
      ? "border-y-[5px] border-l-[7px] border-y-transparent border-l-[#ffd76a]"
      : "border-y-[5px] border-r-[7px] border-y-transparent border-r-[#ffd76a]";

  return <span className={["absolute h-0 w-0 drop-shadow-[0_0_3px_rgba(255,207,83,0.8)]", shape, className].join(" ")} />;
}

function Gate({ x, y, text }: { x: number; y: number; text: string }) {
  return (
    <div className="absolute" style={{ left: x, top: y }}>
      <Asset name="Group 1321318591.png" className="h-[71px] w-[88px]" />
      <span className="absolute left-0 top-[2px] block h-[22px] w-[88px] text-center text-[20px] font-bold leading-[22px] text-white">{text}</span>
    </div>
  );
}

function LaneVehicleStack({
  lane,
  vehicles,
  x,
}: {
  lane: LaneSnapshot | null;
  vehicles: DispatchTicket[];
  x: number;
}) {
  if (!lane) {
    return null;
  }

  const laneTop = 250;
  const laneHeight = 638;
  const capacity = Math.max(lane.capacity || 1, 1);
  const rows = vehicles
    .filter((ticket) => ticket.plate)
    .slice(0, capacity)
    .map((ticket) => ({ id: ticket.id, plate: ticket.plate }));
  const slot = laneHeight / capacity;
  const plateHeight = Math.max(30, Math.min(36, slot * 0.82));
  const plateWidth = LANE_PLATE_WIDTH;
  const fontSize = Math.max(13, Math.min(17, plateHeight * 0.56));

  return (
    <>
      {rows.map((vehicle, index) => {
        const displayPlate = screenPlateText(vehicle.plate);
        const fittedFontSize = plateFontSize(displayPlate, plateWidth, fontSize, 11);
        const rowTop = laneTop + slot * index;
        const top = rowTop + Math.max(0, (slot - plateHeight) / 2);
        const centerX = x + LANE_WIDTH / 2;
        return (
          <div key={`${lane.id}-${vehicle.id}-${index}`}>
            <div
              className={[
                "absolute flex items-center justify-center overflow-hidden px-[3px] font-mono font-black leading-none tracking-[-0.02em]",
                plateTone(vehicle.plate) === "green" ? GREEN_PLATE_CLASS : BLUE_PLATE_CLASS,
              ].join(" ")}
              style={{
                left: centerX - plateWidth / 2,
                top,
                width: plateWidth,
                height: plateHeight,
                fontSize: fittedFontSize,
              }}
            >
              <span className="max-w-full whitespace-nowrap">{displayPlate}</span>
            </div>
          </div>
        );
      })}
    </>
  );
}

function LaneOverlays({
  lanes,
  laneVehicles,
}: {
  lanes: LaneSnapshot[];
  laneVehicles: Record<string, DispatchTicket[]>;
}) {
  const displayLanes = buildDisplayLanes(lanes);

  return (
    <>
      {LANE_LEFTS.map((x, index) => (
        <div key={x}>
          <Signal x={x + 5} y={205} state={laneSignalState(displayLanes[index]?.exitSignal)} />
          <Signal x={x + 5} y={917} state={laneSignalState(displayLanes[index]?.entrySignal)} />
          <div className="absolute top-[504px] w-[64px] text-center text-[34px] font-semibold leading-[58px] text-white/22" style={{ left: x + 16 }}>
            <p>等</p>
            <p>候</p>
            <p>区</p>
          </div>
          <LaneVehicleStack lane={displayLanes[index]} vehicles={displayLanes[index] ? laneVehicles[displayLanes[index].id] ?? [] : []} x={x} />
        </div>
      ))}
    </>
  );
}

function MovingGuidePlates({ tickets, lanes }: { tickets: DispatchTicket[]; lanes: LaneSnapshot[] }) {
  const displayLanes = buildDisplayLanes(lanes);
  const pendingTickets = tickets
    .filter((ticket) => Boolean(ticket.assignedLaneId) && !ticket.laneEntryTime && !ticket.exitTime && !ticket.closedAt)
    .slice(0, 8);

  if (pendingTickets.length === 0) {
    return null;
  }

  return (
    <div className="pointer-events-none absolute inset-0 z-30">
      {pendingTickets.map((ticket, index) => {
        const laneIndex = displayLanes.findIndex((lane) => lane?.id === ticket.assignedLaneId);
        if (laneIndex < 0) {
          return null;
        }

        const laneLeft = LANE_LEFTS[laneIndex];
        const routeOffset = index % 3;
        const displayPlate = screenPlateText(ticket.plate);
        const plateWidth = ENTRY_ROAD_PLATE_WIDTH;
        const fittedFontSize = plateFontSize(displayPlate, plateWidth, 13, 9);
        const entryRoadCenterX = 1560;
        const targetLaneCenterX = laneLeft + LANE_WIDTH / 2;
        const duration = Math.max(5.2, Math.min(8.5, 5.2 + (1548 - laneLeft) / 300));
        const style = {
          "--guide-start-x": `${entryRoadCenterX - plateWidth / 2}px`,
          "--guide-start-y": `${418 + routeOffset * 22}px`,
          "--guide-turn-y": `${980 + routeOffset * 13}px`,
          "--guide-end-x": `${targetLaneCenterX - plateWidth / 2}px`,
          "--guide-end-y": `${980 + routeOffset * 13}px`,
          "--guide-duration": `${duration}s`,
          "--guide-delay": `${index * -0.72}s`,
        } as CSSProperties;
        const plateClass = plateTone(ticket.plate) === "green" ? GREEN_PLATE_CLASS : BLUE_PLATE_CLASS;

        return (
          <div key={ticket.id} className="screen-guide-plate-motion absolute left-0 top-0" style={style}>
            <div
              className={[
                "flex h-[28px] items-center justify-center overflow-hidden px-[2px] font-mono font-black leading-none tracking-[-0.04em]",
                plateClass,
              ].join(" ")}
              style={{ width: plateWidth, fontSize: fittedFontSize }}
            >
              <span className="max-w-full whitespace-nowrap">{displayPlate}</span>
            </div>
          </div>
        );
      })}
    </div>
  );
}

function DraggableAlertPopup({
  screenEvent,
  index,
  scale,
  position,
  busy,
  onPositionChange,
  onConfirm,
}: {
  screenEvent: ScreenEvent;
  index: number;
  scale: number;
  position?: PopupPosition;
  busy: boolean;
  onPositionChange: (eventId: string, position: PopupPosition) => void;
  onConfirm: (event: ScreenEvent) => void;
}) {
  const currentPosition = position ?? defaultAlertPopupPosition(index);
  const [drag, setDrag] = useState<{ startClientX: number; startClientY: number; startX: number; startY: number } | null>(null);

  useEffect(() => {
    if (!drag) {
      return undefined;
    }

    function handlePointerMove(event: PointerEvent) {
      if (!drag) {
        return;
      }
      const nextPosition = clampPopupPosition({
        x: drag.startX + (event.clientX - drag.startClientX) / Math.max(scale, 0.1),
        y: drag.startY + (event.clientY - drag.startClientY) / Math.max(scale, 0.1),
      });
      onPositionChange(screenEvent.id, nextPosition);
    }

    function handlePointerUp() {
      setDrag(null);
    }

    window.addEventListener("pointermove", handlePointerMove);
    window.addEventListener("pointerup", handlePointerUp);
    return () => {
      window.removeEventListener("pointermove", handlePointerMove);
      window.removeEventListener("pointerup", handlePointerUp);
    };
  }, [drag, onPositionChange, scale, screenEvent.id]);

  function handlePointerDown(event: React.PointerEvent<HTMLDivElement>) {
    event.preventDefault();
    setDrag({
      startClientX: event.clientX,
      startClientY: event.clientY,
      startX: currentPosition.x,
      startY: currentPosition.y,
    });
  }

  return (
    <div
      className="absolute flex flex-col border border-[#39e8ff] bg-[#061b32]/96 text-white shadow-[0_0_28px_rgba(57,232,255,0.45)]"
      style={{
        left: currentPosition.x,
        top: currentPosition.y,
        width: ALERT_POPUP_WIDTH,
        height: ALERT_POPUP_HEIGHT,
        zIndex: 84 + index,
      }}
    >
      <div
        role="button"
        tabIndex={0}
        onPointerDown={handlePointerDown}
        className="h-[58px] cursor-move border-b border-[#2ebdda]/70 bg-[linear-gradient(90deg,#0d5aa2_0%,#0d315d_100%)] px-7 text-[25px] font-black leading-[58px] text-white [text-shadow:0_0_8px_rgba(102,221,255,0.8)]"
      >
        新增告警
      </div>
      <div className="px-7 pt-5 text-[17px] leading-[31px]">
        <p>
          类型：
          <span className="font-black text-[#ffdf8a]">{screenEventTypeLabel(screenEvent.type)}</span>
        </p>
        <p>
          车牌：
          <span className="font-mono font-black text-[#9fffc2]">{formatPlateDisplay(screenEvent.plate) || screenEvent.plate || "-"}</span>
        </p>
        <p className="truncate">内容：{screenEvent.message}</p>
        <p>时间：{formatScreenTime(screenEvent.occurredAt)}</p>
      </div>
      <div className="mt-auto flex justify-end gap-3 px-7 pb-5 pt-3">
        <button
          type="button"
          onClick={() => onConfirm(screenEvent)}
          disabled={busy}
          className="h-[36px] w-[104px] border border-[#ffb53d] bg-[linear-gradient(180deg,#e89a1d_0%,#9a5300_100%)] text-[16px] font-black text-white disabled:opacity-60"
        >
          {busy ? "确认中" : "确认"}
        </button>
      </div>
    </div>
  );
}

function ScreenBottomActions({ onDailyReset }: { onDailyReset: () => void }) {
  const router = useRouter();
  const actions = [
    { label: "信号灯控制", icon: Settings2, onClick: () => router.push("/signals") },
    { label: "黑名单管理", icon: Shield, onClick: () => router.push("/blacklist") },
    { label: "车辆告警", icon: TriangleAlert, onClick: () => router.push("/vehicle-alerts") },
    { label: "完成保障", icon: RotateCcw, onClick: onDailyReset },
  ];

  return (
    <div className="absolute bottom-[2px] left-[684px] z-[65] flex h-[28px] items-center gap-[8px]">
      {actions.map((action) => (
        <button
          key={action.label}
          type="button"
          onClick={action.onClick}
          className="group flex h-[26px] min-w-[132px] items-center justify-center gap-1.5 border border-[#3ce9ff]/80 bg-[linear-gradient(180deg,rgba(15,102,174,0.86)_0%,rgba(5,42,88,0.9)_100%)] px-3 text-[13px] font-black text-[#e9fbff] shadow-[inset_0_0_10px_rgba(67,217,255,0.18),0_0_9px_rgba(21,177,255,0.14)] transition hover:border-[#ffcf66] hover:text-white hover:shadow-[inset_0_0_12px_rgba(255,203,92,0.2),0_0_12px_rgba(255,181,61,0.22)]"
        >
          <action.icon className="size-3.5 text-[#85f0ff] transition group-hover:text-[#ffdf8a]" />
          <span>{action.label}</span>
        </button>
      ))}
    </div>
  );
}

function ScreenTitle() {
  return (
    <div className="pointer-events-none absolute left-[560px] top-[3px] z-[52] h-[58px] w-[850px]">
      <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,rgba(5,33,69,0.88)_0%,rgba(5,33,69,0.7)_46%,rgba(5,33,69,0)_76%)]" />
      <h1 className="relative text-center text-[34px] font-black leading-[58px] tracking-[0.03em] text-white [text-shadow:0_0_6px_rgba(110,224,255,0.9),0_0_16px_rgba(25,145,255,0.72)]">
        无锡硕放机场出租车蓄车池排队管理系统
      </h1>
    </div>
  );
}

function DailyResetConfirmDialog({
  dialog,
  busy,
  onCancel,
  onConfirm,
}: {
  dialog: DailyResetDialog | null;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  if (!dialog) {
    return null;
  }

  const scheduled = dialog.source === "scheduled";

  return (
    <div className="absolute inset-0 z-[92] grid place-items-center bg-[#020b16]/68">
      <div className="relative flex h-[292px] w-[560px] flex-col border border-[#ffb53d] bg-[#07182a]/96 shadow-[0_0_30px_rgba(255,181,61,0.42)]">
        <div className="h-[58px] border-b border-[#b7791f]/70 bg-[linear-gradient(90deg,#9b4b08_0%,#23355c_100%)] px-7 text-[25px] font-black leading-[58px] text-white [text-shadow:0_0_8px_rgba(255,181,61,0.72)]">
          完成保障
        </div>
        <div className="px-7 pt-6 text-[17px] leading-[31px] text-white">
          <p className="font-black text-[#ffdf8a]">{scheduled ? `${dialog.label} 完成保障提醒` : "确认执行完成保障"}</p>
          <p className="mt-3 text-[15px] leading-[27px] text-[#d8eefc]">
            确认后将清空当前车道车辆信息并重启调度流程，首条入口车道会重新打开，出口放行等待重新计算。
          </p>
        </div>
        <div className="mt-auto flex justify-end gap-3 px-7 pb-5 pt-3">
          <button
            type="button"
            onClick={onCancel}
            disabled={busy}
            className="h-[36px] w-[96px] border border-[#5a8cad] bg-[#08223d] text-[16px] font-bold text-[#d8eefc] disabled:opacity-50"
          >
            取消
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={busy}
            className="h-[36px] w-[136px] border border-[#ffcf66] bg-[linear-gradient(180deg,#e89a1d_0%,#9a5300_100%)] text-[16px] font-black text-white disabled:opacity-60"
          >
            {busy ? "执行中" : "确认重置"}
          </button>
        </div>
      </div>
    </div>
  );
}

export function ScreenBoard({ mode = "standalone" }: { mode?: "standalone" | "embedded" }) {
  const { containerRef, scale } = useScreenScale(mode);
  const { board, error, setBoard } = useScreenBoard();
  const [now, setNow] = useState(() => new Date());
  const [actionMessage, setActionMessage] = useState("");
  const [pendingEvent, setPendingEvent] = useState<ScreenEvent | null>(null);
  const [handlingEventId, setHandlingEventId] = useState<string | null>(null);
  const [acknowledgingEventId, setAcknowledgingEventId] = useState<string | null>(null);
  const [alertPopupPositions, setAlertPopupPositions] = useState<Record<string, PopupPosition>>({});
  const [visiblePendingAlerts, setVisiblePendingAlerts] = useState<ScreenEvent[]>([]);
  const [pendingClearLane, setPendingClearLane] = useState<LaneSnapshot | null>(null);
  const [clearingLaneId, setClearingLaneId] = useState<string | null>(null);
  const [laneClearReminderAt, setLaneClearReminderAt] = useState<Record<string, number>>({});
  const [dailyResetDialog, setDailyResetDialog] = useState<DailyResetDialog | null>(null);
  const [dailyResetBusy, setDailyResetBusy] = useState(false);
  const [dismissedDailyResetSlots, setDismissedDailyResetSlots] = useState<Record<string, true>>(() => loadDismissedDailyResetSlots());
  const pendingAlertSeenCountsRef = useRef<Record<string, number>>({});
  const overviewExpanded = useDashboardLayoutStore((state) => state.overviewExpanded);
  const toggleOverviewExpanded = useDashboardLayoutStore((state) => state.toggleOverviewExpanded);
  const events = useMemo(() => (board?.events ?? []).filter((event) => !event.handled), [board?.events]);
  const pendingAlertEvents = useMemo(() => (board?.pendingEvents ?? []).filter((event) => !event.handled), [board?.pendingEvents]);
  const visibleAlertPopups = useMemo(() => visiblePendingAlerts.slice(0, MAX_VISIBLE_ALERT_POPUPS), [visiblePendingAlerts]);
  const recentEntries = useMemo(() => buildEntryInfoItems(board), [board]);
  const pendingGuideEntries = board?.waitingAssignments ?? [];
  const guideEntries = pendingGuideEntries.length ? pendingGuideEntries : board?.guideAssignments ?? [];
  const lanes = useMemo(() => board?.lanes ?? [], [board?.lanes]);
  const laneVehicles = useMemo(() => board?.laneVehicles ?? {}, [board?.laneVehicles]);
  const currentPendingClearLane = pendingClearLane ? lanes.find((lane) => lane.id === pendingClearLane.id) ?? pendingClearLane : null;
  const pendingClearLaneCount = currentPendingClearLane ? laneRemainingCount(currentPendingClearLane, laneVehicles) : 0;
  const pendingClearLaneClearable = Boolean(
    board && currentPendingClearLane && isLaneBaseClearable(currentPendingClearLane, board, laneVehicles),
  );

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!actionMessage) {
      return;
    }

    const timer = window.setTimeout(() => setActionMessage(""), ACTION_MESSAGE_TIMEOUT_MS);
    return () => window.clearTimeout(timer);
  }, [actionMessage]);

  useEffect(() => {
    if (!board) {
      return;
    }

    const incoming = (board.pendingEvents ?? []).filter((event) => !event.handled && !event.acknowledged);
    const incomingById = new Map(incoming.map((event) => [event.id, event]));
    const nextSeenCounts: Record<string, number> = {};
    incoming.forEach((event) => {
      nextSeenCounts[event.id] = Math.min((pendingAlertSeenCountsRef.current[event.id] ?? 0) + 1, ALERT_POPUP_STABLE_POLLS);
    });
    pendingAlertSeenCountsRef.current = nextSeenCounts;

    const stableIncoming = incoming.filter((event) => nextSeenCounts[event.id] >= ALERT_POPUP_STABLE_POLLS);
    const closedIds = new Set(
      [...(board.events ?? []), ...(board.pendingEvents ?? [])]
        .filter((event) => event.handled || event.acknowledged)
        .map((event) => event.id),
    );

    setVisiblePendingAlerts((current) => {
      const retained = current
        .filter((event) => !closedIds.has(event.id))
        .map((event) => incomingById.get(event.id) ?? event);
      const retainedIds = new Set(retained.map((event) => event.id));
      const additions = stableIncoming.filter((event) => !retainedIds.has(event.id));
      if (!retained.length && !additions.length) {
        return current.length ? [] : current;
      }
      return [...retained, ...additions.sort((left, right) => eventTimeMs(right) - eventTimeMs(left))];
    });
  }, [board]);

  useEffect(() => {
    if (!board || dailyResetDialog || dailyResetBusy) {
      return;
    }

    const slot = currentDailyResetSlot(now, board.lastDailyResetAt, dismissedDailyResetSlots);
    if (slot) {
      setDailyResetDialog({ source: "scheduled", ...slot });
    }
  }, [board, board?.lastDailyResetAt, dailyResetBusy, dailyResetDialog, dismissedDailyResetSlots, now]);

  useEffect(() => {
    if (!pendingClearLane || clearingLaneId || pendingClearLaneClearable) {
      return;
    }

    setPendingClearLane(null);
  }, [clearingLaneId, pendingClearLane, pendingClearLaneClearable]);

  useEffect(() => {
    if (!board || pendingEvent || pendingAlertEvents.length > 0 || pendingClearLane || clearingLaneId || dailyResetDialog) {
      return;
    }

    const nowMs = Date.now();
    const candidate = lanes.find((lane) => lane.id === board.activeExitLaneId);

    if (!candidate || !isLaneBaseClearable(candidate, board, laneVehicles)) {
      return;
    }
    const lastRelevantAt = Math.max(laneLastActionAtMs(candidate), laneClearReminderAt[candidate.id] ?? 0);
    if (nowMs - lastRelevantAt < LANE_EMPTY_CONFIRMATION_INTERVAL_MS) {
      return;
    }

    setLaneClearReminderAt((current) => ({
      ...current,
      [candidate.id]: nowMs,
    }));
    setPendingClearLane(candidate);
  }, [board, board?.activeExitLaneId, clearingLaneId, dailyResetDialog, laneClearReminderAt, laneVehicles, lanes, pendingAlertEvents.length, pendingClearLane, pendingEvent]);

  function requestHandleEvent(event: ScreenEvent) {
    setActionMessage("");
    setPendingEvent(event);
  }

  function updateAlertPopupPosition(eventId: string, position: PopupPosition) {
    setAlertPopupPositions((current) => ({
      ...current,
      [eventId]: position,
    }));
  }

  async function confirmAcknowledgeEvent(event: ScreenEvent) {
    setAcknowledgingEventId(event.id);
    try {
      const response = await fetch(`${API_BASE_URL}/screen/events/${encodeURIComponent(event.id)}/acknowledge`, { method: "POST" });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const refreshedBoard = await fetchScreenBoard();
      setBoard(refreshedBoard);
      setVisiblePendingAlerts((current) => current.filter((item) => item.id !== event.id));
      setAlertPopupPositions((current) => {
        const next = { ...current };
        delete next[event.id];
        return next;
      });
      setActionMessage("");
    } catch (acknowledgeError) {
      setActionMessage(`告警确认失败: ${acknowledgeError instanceof Error ? acknowledgeError.message : "请求失败"}`);
    } finally {
      setAcknowledgingEventId(null);
    }
  }

  function requestManualDailyReset() {
    setDailyResetDialog({ source: "manual" });
  }

  function dismissDailyResetDialog() {
    if (dailyResetDialog?.source === "scheduled") {
      const activeSlot = currentDailyResetSlot(now, board?.lastDailyResetAt, {})?.slotKey;
      const nextDismissed: Record<string, true> = {
        ...dismissedDailyResetSlots,
        [dailyResetDialog.slotKey]: true,
      };
      if (activeSlot) {
        nextDismissed[activeSlot] = true;
      }
      setDismissedDailyResetSlots(nextDismissed);
      saveDismissedDailyResetSlots(nextDismissed);
    }
    setDailyResetDialog(null);
  }

  async function confirmDailyReset() {
    setDailyResetBusy(true);
    try {
      const response = await fetch(`${API_BASE_URL}/screen/daily-reset`, { method: "POST" });
      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText || `HTTP ${response.status}`);
      }
      const refreshedBoard = await fetchScreenBoard();
      setBoard(refreshedBoard);
      setPendingClearLane(null);
      setDailyResetDialog(null);
      setActionMessage("完成保障已执行，车道已清空并重新进入调度流程");
    } catch (resetError) {
      setActionMessage(`完成保障失败: ${resetError instanceof Error ? resetError.message : "请求失败"}`);
    } finally {
      setDailyResetBusy(false);
    }
  }

  async function confirmHandleEvent() {
    if (!pendingEvent) {
      return;
    }

    const eventId = pendingEvent.id;
    setHandlingEventId(eventId);
    try {
      const response = await fetch(`${API_BASE_URL}/screen/events/${encodeURIComponent(eventId)}/handle`, { method: "POST" });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      setBoard((current) => current ? {
        ...current,
        pendingEvents: current.pendingEvents.filter((event) => event.id !== eventId),
        events: current.events.filter((event) => event.id !== eventId),
      } : current);
      setVisiblePendingAlerts((current) => current.filter((event) => event.id !== eventId));
      setPendingEvent(null);
      setActionMessage("");
    } catch (handleError) {
      setActionMessage(`告警处理失败: ${handleError instanceof Error ? handleError.message : "请求失败"}`);
    } finally {
      setHandlingEventId(null);
    }
  }

  async function confirmClearLaneRemaining() {
    if (!pendingClearLane) {
      return;
    }

    const lane = currentPendingClearLane ?? pendingClearLane;
    setClearingLaneId(lane.id);
    try {
      const response = await fetch(`${API_BASE_URL}/screen/lanes/${encodeURIComponent(lane.id)}/clear-remaining`, { method: "POST" });
      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText || `HTTP ${response.status}`);
      }
      const refreshedBoard = await fetchScreenBoard();
      setBoard(refreshedBoard);
      setPendingClearLane(null);
      setActionMessage(`${lane.name} 剩余车辆已清空，出口信号已重新计算`);
    } catch (clearError) {
      setActionMessage(`车道清空失败: ${clearError instanceof Error ? clearError.message : "请求失败"}`);
    } finally {
      setClearingLaneId(null);
    }
  }

  const shellClassName =
    mode === "standalone"
      ? "flex h-screen w-screen items-start justify-center overflow-hidden bg-[#06182d]"
      : "relative flex h-screen min-h-[720px] w-full items-start justify-center overflow-hidden bg-[#06182d]";

  return (
    <main ref={containerRef} className={shellClassName}>
      <div
        className="relative h-[1080px] w-[1920px] min-w-[1920px] origin-top overflow-hidden text-white"
        style={{ transform: `scale(${scale})` }}
      >
        <Asset name="背景.png" className="absolute inset-0 h-full w-full" />
        <ScreenTitle />
        {error ? (
          <div className="absolute left-[730px] top-[78px] z-50 border border-red-400/70 bg-red-950/80 px-4 py-2 text-[16px] text-red-100">
            大屏接口异常：{error}
          </div>
        ) : null}
        {actionMessage ? (
          <div className="absolute left-[730px] top-[126px] z-50 border border-amber-300/70 bg-amber-950/80 px-4 py-2 text-[16px] text-amber-100">
            {actionMessage}
          </div>
        ) : null}

        <div className="absolute left-[29px] top-[17px] font-mono text-[22px] tracking-[0.12em] text-[#cbd5e1]">
          {formatBeijingDateTime(now)}
        </div>
        {mode === "embedded" ? (
          <div className="absolute right-[18px] top-[14px] z-50 flex items-center">
            <button
              type="button"
              onClick={toggleOverviewExpanded}
              aria-label={overviewExpanded ? "恢复原样" : "放大总览"}
              title={overviewExpanded ? "恢复原样" : "放大总览"}
              className="grid size-[34px] place-items-center text-[#d8f6ff] drop-shadow-[0_0_10px_rgba(83,228,255,0.32)] transition hover:scale-110 hover:text-white"
            >
              {overviewExpanded ? <Minimize2 className="size-4.5" /> : <Maximize2 className="size-4.5" />}
            </button>
          </div>
        ) : null}

        <Asset name="道路.png" className="absolute left-[42px] top-[125px] h-[923px] w-[1839px]" />
        <Asset name="光斑 flare.png" className="absolute left-[532px] top-[68px] h-[72px] w-[849px] opacity-80" />

        <Gate x={217} y={113} text="出口" />
        <Gate x={1653} y={113} text="进口" />
        <Asset name="汽车.png" className="absolute left-[70px] top-[151px] h-[54px] w-[181px]" />
        <Asset name="汽车.png" className="absolute left-[1718px] top-[151px] h-[54px] w-[181px]" />

        <Panel title="黑名单" x={16} y={215}>
          <EventRows events={events} type="blacklist" onHandle={requestHandleEvent} />
        </Panel>
        <Panel title="走错车道" x={16} y={430}>
          <EventRows events={events} type="wrong_lane" onHandle={requestHandleEvent} />
        </Panel>
        <Panel title="未进车道" x={16} y={646}>
          <EventRows events={events} type="not_entered" onHandle={requestHandleEvent} />
        </Panel>
        <Panel title="其他" x={16} y={860}>
          <EventRows events={events} type="other" onHandle={requestHandleEvent} />
        </Panel>

        <LaneOverlays
          lanes={lanes}
          laneVehicles={laneVehicles}
        />
        <MovingGuidePlates tickets={pendingGuideEntries} lanes={lanes} />

        <Asset name="Group 48097127.png" className="absolute left-[1541px] top-[398px] h-[104px] w-[71px]" />
        <Asset name="Group 48097128.png" className="absolute left-[1541px] top-[730px] h-[104px] w-[71px]" />

        <Panel title="进场信息" x={1610} y={215} size="large">
          <EntryInfoRows entries={recentEntries} />
        </Panel>
        <Panel title="引导牌" x={1610} y={663} size="large">
          <GuideRows tickets={guideEntries} />
        </Panel>

        <ScreenBottomActions onDailyReset={requestManualDailyReset} />

        {visiblePendingAlerts.length > MAX_VISIBLE_ALERT_POPUPS ? (
          <div className="absolute left-[730px] top-[86px] z-[83] border border-[#39e8ff]/70 bg-[#061b32]/90 px-4 py-2 text-[15px] font-bold text-[#d8f6ff] shadow-[0_0_16px_rgba(57,232,255,0.28)]">
            还有 {visiblePendingAlerts.length - MAX_VISIBLE_ALERT_POPUPS} 条告警等待确认
          </div>
        ) : null}

        {visibleAlertPopups.map((event, index) => (
          <DraggableAlertPopup
            key={event.id}
            screenEvent={event}
            index={index}
            scale={scale}
            position={alertPopupPositions[event.id]}
            busy={acknowledgingEventId === event.id}
            onPositionChange={updateAlertPopupPosition}
            onConfirm={confirmAcknowledgeEvent}
          />
        ))}

        <DailyResetConfirmDialog
          dialog={dailyResetDialog}
          busy={dailyResetBusy}
          onCancel={dismissDailyResetDialog}
          onConfirm={confirmDailyReset}
        />

        {pendingEvent ? (
          <div className="absolute inset-0 z-[80] grid place-items-center bg-[#020b16]/68">
            <div className="relative flex h-[264px] w-[480px] flex-col border border-[#39e8ff] bg-[#061b32]/95 shadow-[0_0_28px_rgba(57,232,255,0.45)]">
              <div className="h-[56px] border-b border-[#2ebdda]/70 bg-[linear-gradient(90deg,#0d5aa2_0%,#0d315d_100%)] px-7 text-[26px] font-black leading-[56px] text-white [text-shadow:0_0_8px_rgba(102,221,255,0.8)]">
                确认处理告警
              </div>
              <div className="px-7 pt-5 text-[17px] leading-[30px] text-white">
                <p>
                  车牌：
                  <span className="font-mono font-black text-[#9fffc2]">{formatPlateDisplay(pendingEvent.plate) || pendingEvent.plate || "-"}</span>
                </p>
                <p className="truncate">内容：{pendingEvent.message}</p>
                <p>时间：{formatScreenTime(pendingEvent.occurredAt)}</p>
              </div>
              <div className="mt-auto flex justify-end gap-3 px-7 pb-5 pt-3">
                <button
                  type="button"
                  onClick={() => setPendingEvent(null)}
                  disabled={handlingEventId !== null}
                  className="h-[34px] w-[88px] border border-[#5a8cad] bg-[#08223d] text-[16px] font-bold text-[#d8eefc] disabled:opacity-50"
                >
                  取消
                </button>
                <button
                  type="button"
                  onClick={confirmHandleEvent}
                  disabled={handlingEventId === pendingEvent.id}
                  className="h-[34px] w-[96px] border border-[#ffb53d] bg-[linear-gradient(180deg,#e89a1d_0%,#9a5300_100%)] text-[16px] font-black text-white disabled:opacity-60"
                >
                  {handlingEventId === pendingEvent.id ? "处理中" : "确认处理"}
                </button>
              </div>
            </div>
          </div>
        ) : null}

        {pendingClearLane ? (
          <div className="absolute inset-0 z-[78] grid place-items-center bg-[#020b16]/68">
            <div className="relative flex h-[294px] w-[540px] flex-col border border-[#ffb53d] bg-[#07182a]/96 shadow-[0_0_30px_rgba(255,181,61,0.42)]">
              <div className="h-[58px] border-b border-[#b7791f]/70 bg-[linear-gradient(90deg,#9b4b08_0%,#23355c_100%)] px-7 text-[25px] font-black leading-[58px] text-white [text-shadow:0_0_8px_rgba(255,181,61,0.72)]">
                确认车道是否已空
              </div>
              <div className="px-7 pt-5 text-[17px] leading-[31px] text-white">
                <p>
                  车道：
                  <span className="font-black text-[#9cecff]">{pendingClearLane.name}</span>
                </p>
                <p>
                  系统剩余：
                  <span className="font-mono font-black text-[#ffdf8a]">{pendingClearLaneCount}</span>
                  <span> 辆</span>
                </p>
                <p className="mt-2 text-[15px] leading-[26px] text-[#d8eefc]">
                  请现场确认该车道车辆已经全部驶出。确认后将清空该车道剩余车辆信息，并重新判断下一条出口绿灯。
                </p>
              </div>
              <div className="mt-auto flex justify-end gap-3 px-7 pb-5 pt-3">
                <button
                  type="button"
                  onClick={() => setPendingClearLane(null)}
                  disabled={clearingLaneId !== null}
                  className="h-[34px] w-[96px] border border-[#5a8cad] bg-[#08223d] text-[16px] font-bold text-[#d8eefc] disabled:opacity-50"
                >
                  暂不确认
                </button>
                <button
                  type="button"
                  onClick={confirmClearLaneRemaining}
                  disabled={clearingLaneId === pendingClearLane.id || !pendingClearLaneClearable}
                  className="h-[34px] w-[154px] border border-[#ffcf66] bg-[linear-gradient(180deg,#e89a1d_0%,#9a5300_100%)] text-[16px] font-black text-white disabled:opacity-60"
                >
                  {clearingLaneId === pendingClearLane.id ? "清空中" : "确认已驶出"}
                </button>
              </div>
            </div>
          </div>
        ) : null}
      </div>
    </main>
  );
}
