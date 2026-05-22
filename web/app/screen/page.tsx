/* eslint-disable @next/next/no-img-element */
"use client";

import { type CSSProperties, type FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { Maximize2, Minimize2 } from "lucide-react";
import { formatPlateDisplay } from "@/lib/utils";
import { useDashboardLayoutStore } from "@/stores/dashboard-layout-store";

const DESIGN_WIDTH = 1920;
const DESIGN_HEIGHT = 1080;
const ASSET = "/screen-assets";
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/api";
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
  laneId: string;
  laneName: string;
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
  capacity: number;
  vehicleCount: number;
  currentPlate: string | null;
  lastEntryPlate: string | null;
  entrySignal: "RED" | "GREEN" | "OFFLINE";
  exitSignal: "RED" | "GREEN" | "OFFLINE";
}

interface ScreenEvent {
  id: string;
  type: "blacklist" | "wrong_lane" | "not_entered" | "other";
  plate: string;
  message: string;
  occurredAt: string;
  sourceId: string | null;
  sourceName: string | null;
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
  events: ScreenEvent[];
  lanes: LaneSnapshot[];
}

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

function laneNumber(label?: string | null) {
  const matched = label?.match(/\d+/);
  return matched ? `${Number.parseInt(matched[0], 10)}车道` : "--车道";
}

function laneLabel(lanes: LaneSnapshot[], laneId: string) {
  return lanes.find((lane) => lane.id === laneId)?.name ?? laneId;
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

export function ScreenBoard({ mode = "standalone" }: { mode?: "standalone" | "embedded" }) {
  const { containerRef, scale } = useScreenScale(mode);
  const { board, error, setBoard } = useScreenBoard();
  const [now, setNow] = useState(() => new Date());
  const [simulatePlate, setSimulatePlate] = useState("");
  const [simulateLanePlate, setSimulateLanePlate] = useState("");
  const [simulateLaneId, setSimulateLaneId] = useState("");
  const [simulateMessage, setSimulateMessage] = useState("");
  const [pendingEvent, setPendingEvent] = useState<ScreenEvent | null>(null);
  const [handlingEventId, setHandlingEventId] = useState<string | null>(null);
  const overviewExpanded = useDashboardLayoutStore((state) => state.overviewExpanded);
  const toggleOverviewExpanded = useDashboardLayoutStore((state) => state.toggleOverviewExpanded);
  const events = board?.events ?? [];
  const recentEntries = useMemo(() => buildEntryInfoItems(board), [board]);
  const pendingGuideEntries = board?.waitingAssignments ?? [];
  const guideEntries = pendingGuideEntries.length ? pendingGuideEntries : board?.guideAssignments ?? [];
  const lanes = useMemo(() => board?.lanes ?? [], [board?.lanes]);
  const laneVehicles = board?.laneVehicles ?? {};

  useEffect(() => {
    if (!simulateLaneId && lanes[0]?.id) {
      setSimulateLaneId(lanes[0].id);
    }
  }, [lanes, simulateLaneId]);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  function requestHandleEvent(event: ScreenEvent) {
    setPendingEvent(event);
  }

  async function readResponseError(response: Response) {
    const contentType = response.headers.get("content-type") ?? "";
    if (contentType.includes("application/json")) {
      try {
        const payload = (await response.json()) as { message?: string; error?: string };
        return payload.message || payload.error || `HTTP ${response.status}`;
      } catch {
        return `HTTP ${response.status}`;
      }
    }

    const message = (await response.text()).trim();
    return message || `HTTP ${response.status}`;
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
        events: current.events.map((event) => event.id === eventId ? { ...event, handled: true, handledAt: new Date().toISOString() } : event),
      } : current);
      setPendingEvent(null);
    } catch (handleError) {
      setSimulateMessage(`告警处理失败: ${handleError instanceof Error ? handleError.message : "请求失败"}`);
    } finally {
      setHandlingEventId(null);
    }
  }

  async function simulateYardEntry(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const plate = simulatePlate.trim();
    if (!plate) {
      setSimulateMessage("请输入车牌号");
      return;
    }

    setSimulateMessage("提交中...");
    const response = await fetch(`${API_BASE_URL}/screen/simulate/yard-entry`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ plate }),
    });
    if (!response.ok) {
      setSimulateMessage(`模拟失败: ${await readResponseError(response)}`);
      return;
    }

    const nextBoard = await fetchScreenBoard();
    setBoard(nextBoard);
    setSimulatePlate("");
    setSimulateMessage("已模拟总入口入场");
  }

  async function simulateLaneEntry() {
    const laneId = simulateLaneId || lanes[0]?.id || "";
    const plate = simulateLanePlate.trim();
    if (!laneId) {
      setSimulateMessage("请选择车道");
      return;
    }
    if (!plate) {
      setSimulateMessage("请输入车牌号");
      return;
    }

    setSimulateMessage("提交中...");
    const response = await fetch(`${API_BASE_URL}/screen/simulate/lane-entry`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ laneId, plate }),
    });
    if (!response.ok) {
      setSimulateMessage(`模拟失败: ${await readResponseError(response)}`);
      return;
    }

    const nextBoard = await fetchScreenBoard();
    setBoard(nextBoard);
    setSimulateLanePlate("");
    setSimulateMessage(`已模拟${laneLabel(lanes, laneId)}入口`);
  }

  async function simulateLaneExit() {
    const laneId = simulateLaneId || lanes[0]?.id || "";
    if (!laneId) {
      setSimulateMessage("请选择车道");
      return;
    }

    setSimulateMessage("提交中...");
    const response = await fetch(`${API_BASE_URL}/screen/simulate/lane-exit`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ laneId }),
    });
    if (!response.ok) {
      setSimulateMessage(`模拟失败: ${await readResponseError(response)}`);
      return;
    }

    const nextBoard = await fetchScreenBoard();
    setBoard(nextBoard);
    setSimulateMessage(`已模拟${laneLabel(lanes, laneId)}出场`);
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
        {error ? (
          <div className="absolute left-[730px] top-[78px] z-50 border border-red-400/70 bg-red-950/80 px-4 py-2 text-[16px] text-red-100">
            大屏接口异常：{error}
          </div>
        ) : null}

        <div className="absolute left-[29px] top-[17px] font-mono text-[22px] tracking-[0.12em] text-[#cbd5e1]">
          {formatBeijingDateTime(now)}
        </div>
        {mode === "embedded" ? (
          <div className="absolute right-[18px] top-[14px] z-50 flex items-center gap-4">
            <div className="flex items-center gap-3 text-[20px] font-semibold text-[#cbd5e1]">
              <span className="text-[#ff9f1a]">☀</span>
              <span>多云</span>
              <span>23~34℃</span>
            </div>
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
        ) : (
          <div className="absolute right-[18px] top-[19px] flex items-center gap-3 text-[20px] font-semibold text-[#cbd5e1]">
            <span className="text-[#ff9f1a]">☀</span>
            <span>多云</span>
            <span>23~34℃</span>
          </div>
        )}

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

        <form
          onSubmit={simulateYardEntry}
          className="absolute left-[430px] top-[82px] z-50 flex h-[34px] items-center gap-2 border border-[#34d8f0]/70 bg-[#04172b]/88 px-3 text-[14px] shadow-[0_0_12px_rgba(52,216,240,0.35)]"
        >
          <span className="font-semibold text-[#9cecff]">模拟总入口</span>
          <input
            value={simulatePlate}
            onChange={(event) => setSimulatePlate(event.target.value)}
            placeholder="输入车牌"
            className="h-[22px] w-[120px] border border-[#2aa6cf] bg-[#071f39] px-2 text-white outline-none placeholder:text-white/45"
          />
          <button type="submit" className="h-[22px] bg-[#b86c08] px-3 font-bold text-white">
            入场
          </button>
          <span className="mx-1 h-[18px] w-px bg-[#2aa6cf]/60" />
          <span className="font-semibold text-[#9cecff]">模拟车道</span>
          <select
            value={simulateLaneId}
            onChange={(event) => setSimulateLaneId(event.target.value)}
            className="h-[22px] w-[92px] border border-[#2aa6cf] bg-[#071f39] px-1 text-white outline-none"
          >
            {(lanes.length > 0 ? lanes : []).map((lane) => (
              <option key={lane.id} value={lane.id}>
                {lane.name}
              </option>
            ))}
            {lanes.length === 0 ? <option value="">无车道</option> : null}
          </select>
          <label className="flex h-[22px] items-center border border-[#2aa6cf] bg-[#071f39]">
            <span className="border-r border-[#2aa6cf]/60 px-2 text-[#9cecff]">入口</span>
            <input
              value={simulateLanePlate}
              onChange={(event) => setSimulateLanePlate(event.target.value)}
              placeholder="输入车牌"
              className="h-full w-[108px] bg-transparent px-2 text-white outline-none placeholder:text-white/45"
            />
          </label>
          <button type="button" onClick={simulateLaneEntry} className="h-[22px] bg-[#0f7b9a] px-3 font-bold text-white">
            入口
          </button>
          <button type="button" onClick={simulateLaneExit} className="h-[22px] bg-[#b86c08] px-3 font-bold text-white">
            出场
          </button>
          <span className="min-w-[140px] text-[#cbd5e1]">{simulateMessage}</span>
        </form>

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
      </div>
    </main>
  );
}

export default function ScreenPage() {
  return <ScreenBoard mode="standalone" />;
}
