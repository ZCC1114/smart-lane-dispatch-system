/* eslint-disable @next/next/no-img-element */
"use client";

import { type FormEvent, useEffect, useMemo, useRef, useState } from "react";

const DESIGN_WIDTH = 1920;
const DESIGN_HEIGHT = 1080;
const ASSET = "/screen-assets";
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/api";

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
}

interface ScreenBoardData {
  generatedAt: string;
  activeEntryLaneId: string | null;
  activeExitLaneId: string | null;
  entryDispatchEnabled: boolean;
  exitDispatchEnabled: boolean;
  waitingAssignments: DispatchTicket[];
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
    return () => window.removeEventListener("resize", updateViewport);
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

function laneOpenSignalState(lane: LaneSnapshot | null, activeLaneId: string | null, enabled: boolean) {
  if (!lane) {
    return "yellow";
  }
  if (!enabled || lane.id !== activeLaneId) {
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
        "inline-flex items-center justify-center border font-mono font-black leading-none shadow-[inset_0_0_9px_rgba(255,255,255,0.72),0_0_7px_rgba(0,0,0,0.45)]",
        scale === "large" ? "h-[36px] min-w-[136px] px-3 text-[20px]" : scale === "entry" ? "h-[34px] w-[124px] px-2 text-[17px]" : "h-[23px] min-w-[88px] px-2 text-[14px]",
        tone === "green"
          ? "border-[#baffcf] bg-[linear-gradient(180deg,#effff3_0%,#4ee773_100%)] text-[#061a0a]"
          : "border-white bg-[linear-gradient(180deg,#f7fbff_0%,#2e72dd_100%)] text-white",
      ].join(" ")}
    >
      {text}
    </span>
  );
}

function YellowTaxi({ x, y, width = 38, height = 66 }: { x: number; y: number; width?: number; height?: number }) {
  return (
    <div
      className="absolute rounded-[45%_45%_36%_36%/12%_12%_18%_18%] border border-[#ffe36b] bg-[linear-gradient(180deg,#ffe568_0%,#ffc328_35%,#f2a20e_100%)] shadow-[0_0_14px_rgba(255,202,45,0.7)]"
      style={{ left: x, top: y, width, height }}
    >
      <span className="absolute left-[18%] right-[18%] top-[9%] h-[18%] rounded-[4px] bg-[linear-gradient(180deg,#315273_0%,#172638_100%)] shadow-[inset_0_1px_3px_rgba(255,255,255,0.35)]" />
      <span className="absolute left-[14%] right-[14%] top-[33%] h-[30%] rounded-[6px] bg-[linear-gradient(180deg,#ffd956_0%,#f6b31b_100%)] shadow-[inset_0_0_8px_rgba(255,255,255,0.35)]" />
      <span className="absolute bottom-[10%] left-[20%] right-[20%] h-[16%] rounded-[4px] bg-[linear-gradient(180deg,#2f4f6e_0%,#172435_100%)] shadow-[inset_0_1px_3px_rgba(255,255,255,0.25)]" />
      <span className="absolute left-1/2 top-[42%] h-[12%] w-[42%] -translate-x-1/2 rounded-[3px] bg-[#4b3410]/45" />
      <span className="absolute left-[-5px] top-[24%] h-[16%] w-[6px] rounded bg-[#202936]" />
      <span className="absolute right-[-5px] top-[24%] h-[16%] w-[6px] rounded bg-[#202936]" />
      <span className="absolute bottom-[22%] left-[-5px] h-[16%] w-[6px] rounded bg-[#202936]" />
      <span className="absolute bottom-[22%] right-[-5px] h-[16%] w-[6px] rounded bg-[#202936]" />
    </div>
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
  const height = size === "large" ? 382 : 188;

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
  return (
    <div className="relative mb-[9px] h-[40px] overflow-hidden">
      <Asset name="Frame 427319724.png" className="absolute inset-0 h-[40px] w-[335px]" />
      <span
        className={[
          "absolute left-[7px] top-[8px] grid size-[11px] place-items-center rounded-full text-[9px] font-bold text-white",
          blue ? "bg-[#16d7ff]" : "bg-[#ff8c22]",
        ].join(" ")}
      >
        !
      </span>
      <p className="absolute left-[24px] top-[7px] w-[250px] truncate text-[12px] font-bold text-white">
        {event ? `事件：${event.plate} ${event.message}` : "暂无事件"}
      </p>
      <p className="absolute left-[24px] top-[25px] text-[12px] text-[#bec7d0]">时间： {event ? formatScreenTime(event.occurredAt) : "--"}</p>
      {event ? (
        <button type="button" onClick={() => onHandle(event)} className="absolute right-[5px] top-[11px] h-[18px] w-[36px]">
          <Asset name="Frame 427319726.png" className="h-[18px] w-[36px]" />
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
  const rows = events.filter((event) => event.type === type).slice(0, 3);

  return (
    <>
      {Array.from({ length: 3 }).map((_, index) => (
        <AlertRow key={rows[index]?.id ?? `${type}-${index}`} event={rows[index]} blue={blue} onHandle={onHandle} />
      ))}
    </>
  );
}

function EntryInfoRows({ logs }: { logs: EntryLog[] }) {
  const rows = logs.slice(0, 5);

  return (
    <div className="space-y-[7px]">
      {rows.map((log) => (
        <div key={log.id} className="grid h-[45px] grid-cols-[124px_1fr] items-center gap-[7px] border-b border-[#4d7593]/80">
          <InlinePlate text={log.plate} tone={plateTone(log.plate)} scale="entry" />
          <div className="min-w-0 text-[11px] leading-[16px] text-white">
            <p className="whitespace-nowrap">时间:{formatScreenTime(log.entryTime)}</p>
          </div>
        </div>
      ))}
      {rows.length === 0 ? <div className="pt-6 text-center text-[16px] text-white/70">暂无入场车辆</div> : null}
    </div>
  );
}

function GuideRows({ tickets }: { tickets: DispatchTicket[] }) {
  const rows = [...tickets]
    .sort((left, right) => {
      const leftTime = new Date(left.assignedAt ?? left.yardEntryTime).getTime();
      const rightTime = new Date(right.assignedAt ?? right.yardEntryTime).getTime();
      return rightTime - leftTime;
    })
    .slice(0, 6);

  return (
    <div className="space-y-[15px] pt-[2px]">
      {rows.map((ticket) => (
        <div key={ticket.id} className="relative h-[39px]">
          <div className="absolute left-0 top-[3px]">
            <InlinePlate text={ticket.plate} tone={plateTone(ticket.plate)} scale="large" />
          </div>
          <span className="absolute left-[150px] top-[9px] h-0 w-0 border-y-[9px] border-l-[14px] border-y-transparent border-l-[#ffc247] drop-shadow-[0_0_5px_#ffc247]" />
          <div className="absolute left-[174px] top-0 h-[39px] w-[108px] border border-[#f1a21f] bg-[linear-gradient(180deg,#df9823_0%,#995100_100%)] text-center text-[25px] font-black leading-[37px] text-white shadow-[inset_0_0_12px_rgba(255,255,255,0.25)]">
            {laneNumber(ticket.assignedLaneName)}
          </div>
        </div>
      ))}
      {rows.length === 0 ? <div className="pt-6 text-center text-[16px] text-white/70">暂无引导车辆</div> : null}
    </div>
  );
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
  const carHeight = Math.max(28, Math.min(68, slot * 0.66));
  const carWidth = Math.max(20, Math.min(40, carHeight * 0.58));
  const plateHeight = Math.max(16, Math.min(23, slot * 0.3));
  const fontSize = Math.max(9, Math.min(14, plateHeight * 0.58));

  return (
    <>
      {rows.map((vehicle, index) => {
        const rowTop = laneTop + slot * index;
        const plateTop = Math.max(4, carHeight * 0.48 - plateHeight / 2);
        const contentHeight = Math.max(carHeight, plateTop + plateHeight);
        const top = rowTop + Math.max(0, (slot - contentHeight) / 2);
        const centerX = x + 46;
        return (
          <div key={`${lane.id}-${vehicle.id}-${index}`}>
            <YellowTaxi x={centerX - carWidth / 2} y={top} width={carWidth} height={carHeight} />
            <div
              className={[
                "absolute flex items-center justify-center overflow-hidden border px-1 font-mono font-black leading-none shadow-[inset_0_0_7px_rgba(255,255,255,0.72),0_0_6px_rgba(0,0,0,0.45)]",
                plateTone(vehicle.plate) === "green"
                  ? "border-[#baffcf] bg-[linear-gradient(180deg,#effff3_0%,#4ee773_100%)] text-[#061a0a]"
                  : "border-white bg-[linear-gradient(180deg,#f7fbff_0%,#2e72dd_100%)] text-white",
              ].join(" ")}
              style={{
                left: centerX - 40,
                top: top + plateTop,
                width: 80,
                height: plateHeight,
                fontSize,
              }}
            >
              <span className="max-w-full truncate">{vehicle.plate}</span>
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
  activeEntryLaneId,
  activeExitLaneId,
  entryDispatchEnabled,
  exitDispatchEnabled,
}: {
  lanes: LaneSnapshot[];
  laneVehicles: Record<string, DispatchTicket[]>;
  activeEntryLaneId: string | null;
  activeExitLaneId: string | null;
  entryDispatchEnabled: boolean;
  exitDispatchEnabled: boolean;
}) {
  const laneX = [404, 508, 611, 714, 817, 920, 1023, 1126, 1229, 1332, 1435];
  const displayLanes = buildDisplayLanes(lanes);

  return (
    <>
      {laneX.map((x, index) => (
        <div key={x}>
          <Signal x={x + 5} y={205} state={laneOpenSignalState(displayLanes[index], activeExitLaneId, exitDispatchEnabled)} />
          <Signal x={x + 5} y={917} state={laneOpenSignalState(displayLanes[index], activeEntryLaneId, entryDispatchEnabled)} />
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

export function ScreenBoard({ mode = "standalone" }: { mode?: "standalone" | "embedded" }) {
  const { containerRef, scale } = useScreenScale(mode);
  const { board, error, setBoard } = useScreenBoard();
  const [simulatePlate, setSimulatePlate] = useState("");
  const [simulateLanePlate, setSimulateLanePlate] = useState("");
  const [simulateLaneId, setSimulateLaneId] = useState("");
  const [simulateMessage, setSimulateMessage] = useState("");
  const [pendingEvent, setPendingEvent] = useState<ScreenEvent | null>(null);
  const [handlingEventId, setHandlingEventId] = useState<string | null>(null);
  const events = board?.events ?? [];
  const recentEntries = board?.recentEntryLogs ?? [];
  const guideEntries = board?.waitingAssignments ?? [];
  const lanes = useMemo(() => board?.lanes ?? [], [board?.lanes]);
  const laneVehicles = board?.laneVehicles ?? {};
  const activeEntryLaneId = board?.activeEntryLaneId ?? null;
  const activeExitLaneId = board?.activeExitLaneId ?? null;
  const entryDispatchEnabled = board?.entryDispatchEnabled ?? false;
  const exitDispatchEnabled = board?.exitDispatchEnabled ?? false;

  useEffect(() => {
    if (!simulateLaneId && lanes[0]?.id) {
      setSimulateLaneId(lanes[0].id);
    }
  }, [lanes, simulateLaneId]);

  function requestHandleEvent(event: ScreenEvent) {
    setPendingEvent(event);
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
      setBoard((current) => current ? { ...current, events: current.events.filter((event) => event.id !== eventId) } : current);
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
      setSimulateMessage(`模拟失败: HTTP ${response.status}`);
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
      setSimulateMessage(`模拟失败: HTTP ${response.status}`);
      return;
    }

    const nextBoard = await fetchScreenBoard();
    setBoard(nextBoard);
    setSimulateLanePlate("");
    setSimulateMessage(`已模拟${laneLabel(lanes, laneId)}入口`);
  }

  async function simulateLaneExit() {
    setSimulateMessage("提交中...");
    const response = await fetch(`${API_BASE_URL}/screen/simulate/global-exit`, {
      method: "POST",
    });
    if (!response.ok) {
      const message = await response.text();
      setSimulateMessage(`模拟失败: ${message || `HTTP ${response.status}`}`);
      return;
    }

    const nextBoard = await fetchScreenBoard();
    setBoard(nextBoard);
    setSimulateMessage("已模拟出场");
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
          2026-04-23&nbsp;&nbsp;09:22:12
        </div>
        <div className="absolute right-[18px] top-[19px] flex items-center gap-3 text-[20px] font-semibold text-[#cbd5e1]">
          <span className="text-[#ff9f1a]">☀</span>
          <span>多云</span>
          <span>23~34℃</span>
        </div>

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
          <EventRows events={events} type="not_entered" blue onHandle={requestHandleEvent} />
        </Panel>
        <Panel title="其他" x={16} y={860}>
          <EventRows events={events} type="other" onHandle={requestHandleEvent} />
        </Panel>

        <LaneOverlays
          lanes={lanes}
          laneVehicles={laneVehicles}
          activeEntryLaneId={activeEntryLaneId}
          activeExitLaneId={activeExitLaneId}
          entryDispatchEnabled={entryDispatchEnabled}
          exitDispatchEnabled={exitDispatchEnabled}
        />

        <Asset name="Group 48097127.png" className="absolute left-[1541px] top-[398px] h-[104px] w-[71px]" />
        <Asset name="Group 48097128.png" className="absolute left-[1541px] top-[730px] h-[104px] w-[71px]" />

        <Panel title="进场信息" x={1610} y={215} size="large">
          <EntryInfoRows logs={recentEntries} />
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
                  <span className="font-mono font-black text-[#9fffc2]">{pendingEvent.plate || "-"}</span>
                </p>
                <p className="truncate">内容：{pendingEvent.message}</p>
                <p>时间：{formatScreenTime(pendingEvent.occurredAt)}</p>
                <p className="mt-1 text-[14px] text-[#a9c4d9]">确认后该告警将从大屏告警列表中移除。</p>
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
