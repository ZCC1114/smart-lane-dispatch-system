"use client";

import { ArrowDown, ArrowLeft, ArrowUp, Car, CloudSun, Navigation, Sun } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import type { DispatchBoard, DispatchTicket, LaneSnapshot, SignalState } from "@/lib/types";
import { cn, formatPlateDisplay } from "@/lib/utils";

interface DisplayLane {
  slot: number;
  lane: LaneSnapshot | null;
}

function extractLaneOrder(raw: { code?: string; name?: string }) {
  const matched = `${raw.code ?? ""} ${raw.name ?? ""}`.match(/\d+/);
  return matched ? Number.parseInt(matched[0], 10) : Number.MAX_SAFE_INTEGER;
}

function buildDisplayLanes(lanes: LaneSnapshot[]) {
  const ordered = [...lanes].sort((left, right) => {
    const orderDiff = extractLaneOrder(left) - extractLaneOrder(right);
    if (orderDiff !== 0) {
      return orderDiff;
    }
    return `${left.code}-${left.name}`.localeCompare(`${right.code}-${right.name}`, "zh-CN");
  });

  return Array.from({ length: 11 }, (_, index): DisplayLane => ({
    slot: index + 1,
    lane: ordered[index] ?? null,
  }));
}

function laneNumberLabel(lane: LaneSnapshot | null, slot: number) {
  if (!lane) {
    return String(slot);
  }

  const extracted = extractLaneOrder(lane);
  return String(extracted === Number.MAX_SAFE_INTEGER ? slot : extracted);
}

function formatDateTime(value: Date) {
  const date = value
    .toLocaleDateString("zh-CN", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    })
    .replaceAll("/", "-");
  return `${date} ${value.toLocaleTimeString("zh-CN", { hour12: false })}`;
}

function formatTicketTime(ticket?: DispatchTicket | null) {
  if (!ticket) {
    return "2026/04/21 09:33";
  }

  const raw = ticket.assignedAt ?? ticket.laneEntryTime ?? ticket.yardEntryTime;
  return new Date(raw).toLocaleString("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}

function plateForLane(lane: LaneSnapshot | null, fallbackIndex: number) {
  return lane?.currentPlate ?? lane?.lastEntryPlate ?? (fallbackIndex % 3 === 0 ? "苏B·E54G1" : "苏B·A12594");
}

function signalColors(signal?: SignalState) {
  return {
    red: signal === "RED" || signal === "OFFLINE",
    yellow: signal === "OFFLINE",
    green: signal === "GREEN",
  };
}

function SignalMini({ signal }: { signal?: SignalState }) {
  const colors = signalColors(signal);

  return (
    <div className="flex gap-1 rounded-full bg-[#112033] px-1.5 py-1 shadow-[0_0_10px_rgba(12,43,73,0.9)]">
      <span className={cn("size-3 rounded-full bg-[#512022]", colors.red && "bg-[#f23a2f] shadow-[0_0_8px_#f23a2f]")} />
      <span className={cn("size-3 rounded-full bg-[#51441c]", colors.yellow && "bg-[#f6b51b] shadow-[0_0_8px_#f6b51b]")} />
      <span className={cn("size-3 rounded-full bg-[#1b412d]", colors.green && "bg-[#3ed160] shadow-[0_0_8px_#3ed160]")} />
    </div>
  );
}

function TaxiGlyph({ compact = false }: { compact?: boolean }) {
  return (
    <div
      className={cn(
        "relative rounded-[7px] border border-[#f6d24a] bg-[linear-gradient(180deg,#ffd84a_0%,#f2a90c_100%)] shadow-[0_0_12px_rgba(255,183,22,0.45)]",
        compact ? "h-8 w-5" : "h-10 w-7",
      )}
    >
      <span className="absolute left-1 right-1 top-1 h-2 rounded bg-[#252b35]/55" />
      <span className="absolute bottom-1 left-1 right-1 h-2 rounded bg-[#252b35]/45" />
      <span className="absolute -left-1 top-2 h-3 w-1 rounded bg-[#222]" />
      <span className="absolute -right-1 top-2 h-3 w-1 rounded bg-[#222]" />
    </div>
  );
}

function PlateBadge({ plate, tone = "green" }: { plate: string; tone?: "green" | "blue" }) {
  return (
    <span
      className={cn(
        "inline-flex h-6 items-center justify-center whitespace-nowrap border px-2 py-0.5 font-mono text-[12px] font-bold leading-none shadow-[inset_0_0_10px_rgba(255,255,255,0.65),0_0_8px_rgba(0,0,0,0.35)]",
        tone === "green"
          ? "border-[#b8ffd1] bg-[linear-gradient(180deg,#ecfff1_0%,#4be66e_100%)] text-[#041c0b]"
          : "border-[#d9ebff] bg-[linear-gradient(180deg,#f6fbff_0%,#2d72df_100%)] text-white",
      )}
    >
      {formatPlateDisplay(plate) || plate}
    </span>
  );
}

function SectionPanel({
  title,
  children,
  className,
}: {
  title: string;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <section className={cn("border border-[#34d8f0] bg-[#082847]/85 shadow-[0_0_18px_rgba(39,184,255,0.35)]", className)}>
      <div className="h-12 border-b border-[#1a84aa] bg-[linear-gradient(135deg,#1d64be_0%,#114a85_42%,rgba(17,74,133,0.2)_100%)] px-4 py-2 text-[24px] font-bold text-white shadow-[inset_0_0_22px_rgba(74,179,255,0.35)]">
        {title}
      </div>
      {children}
    </section>
  );
}

function AlertRows({ variant, count = 3 }: { variant: "red" | "cyan"; count?: number }) {
  return (
    <div className="space-y-2 p-3">
      {Array.from({ length: count }).map((_, index) => (
        <div
          key={index}
          className={cn(
            "grid grid-cols-[1fr_34px] gap-2 border-l-2 px-2 py-1.5 text-[12px]",
            variant === "red" ? "border-[#ff8b28] bg-[#5b302d]/82" : "border-[#21d9ff] bg-[#0e4875]/82",
          )}
        >
          <div>
            <p className="truncate font-semibold text-white">
              <span className={cn("mr-1 inline-block size-3 rounded-full text-center text-[10px] leading-3", variant === "red" ? "bg-[#ff8b28]" : "bg-[#21d9ff]")}>!</span>
              事件：{formatPlateDisplay("苏B5M9J4")}{variant === "red" ? "为黑名单车辆" : "未进车道"}，请及时处理
            </p>
            <p className="mt-1 text-[#b8c7d6]">时间：2023/05/26 2:23:24</p>
          </div>
          <button className={cn("self-center rounded-sm px-1.5 py-1 text-white", variant === "red" ? "bg-[#bd3d2a]" : "bg-[#087dbc]")} type="button">
            处理
          </button>
        </div>
      ))}
    </div>
  );
}

function LaneColumn({
  displayLane,
  activeEntryLaneId,
  activeExitLaneId,
}: {
  displayLane: DisplayLane;
  activeEntryLaneId: string | null;
  activeExitLaneId: string | null;
}) {
  const { slot, lane } = displayLane;
  const laneNumber = laneNumberLabel(lane, slot);
  const capacity = Math.max(lane?.capacity ?? 10, 1);
  const vehicleCount = Math.min(lane?.vehicleCount ?? (slot === 3 ? 12 : slot === 4 ? 9 : slot === 1 ? 0 : 0), capacity + 2);
  const plates = Array.from({ length: Math.max(vehicleCount, 0) }, (_, index) => plateForLane(lane, index + slot));
  const active = lane?.id === activeEntryLaneId || lane?.id === activeExitLaneId;

  return (
    <div className="relative h-[766px] w-[92px] shrink-0 border-x-[3px] border-[#26a9df] bg-[#041d37]/82 shadow-[inset_0_0_30px_rgba(32,138,203,0.34)]">
      <div className="absolute left-0 right-0 top-0 h-28 bg-[linear-gradient(135deg,rgba(98,169,223,0.42)_0%,rgba(7,28,53,0.2)_48%,rgba(255,255,255,0.07)_49%,transparent_72%)]" />
      <div className="absolute left-1/2 top-9 bottom-12 w-px -translate-x-1/2 border-l border-dashed border-[#6a89a9]/55" />
      <div className="absolute left-2 top-1">
        <SignalMini signal={lane?.exitSignal ?? "OFFLINE"} />
      </div>
      <div className="absolute left-1/2 top-3 -translate-x-1/2 text-[32px] font-black text-[#74c4ee]/78 [text-shadow:0_0_12px_rgba(73,186,238,0.65)]">{laneNumber}</div>
      <div className="absolute left-1/2 top-[175px] -translate-x-1/2 space-y-4 text-center text-[34px] font-semibold leading-tight text-white/20">
        <p>等</p>
        <p>候</p>
        <p>区</p>
      </div>
      <div className="absolute inset-x-0 top-[70px] bottom-[88px] flex flex-col-reverse items-center justify-start gap-2 overflow-hidden">
        {plates.slice(0, 13).map((plate, index) => (
          <div key={`${slot}-${plate}-${index}`} className="flex flex-col items-center gap-1">
            {(slot === 4 || index % 4 === 0) && <TaxiGlyph compact={slot !== 4} />}
            <PlateBadge plate={plate} tone={index % 3 === 0 ? "blue" : "green"} />
          </div>
        ))}
      </div>
      <div className="absolute bottom-1 left-2">
        <SignalMini signal={lane?.entrySignal ?? "OFFLINE"} />
      </div>
      <div className="absolute bottom-1 left-1/2 -translate-x-1/2 text-[34px] font-black text-[#9cc7e4]/85">{laneNumber}</div>
      {active ? <div className="absolute inset-0 border-2 border-[#ffdf28] shadow-[0_0_18px_rgba(255,223,40,0.85)]" /> : null}
      <ArrowUp className="absolute left-1/2 top-[250px] size-9 -translate-x-1/2 text-[#6d86a4]/55" />
      <ArrowUp className="absolute left-1/2 bottom-[255px] size-9 -translate-x-1/2 text-[#6d86a4]/55" />
    </div>
  );
}

function RoadLabel({ label, className }: { label: string; className?: string }) {
  return (
    <div className={cn("absolute rounded-sm bg-[#ffa729] px-7 py-0.5 text-[20px] font-bold text-white shadow-[0_0_12px_rgba(255,169,41,0.65)]", className)}>
      {label}
    </div>
  );
}

function InfoList({ dispatchBoard, lanes }: { dispatchBoard?: DispatchBoard; lanes: LaneSnapshot[] }) {
  const tickets = dispatchBoard?.waitingAssignments?.length ? dispatchBoard.waitingAssignments : dispatchBoard?.recentDispatches ?? [];
  const rows = Array.from({ length: 6 }, (_, index) => {
    const ticket = tickets[index];
    const lane = lanes[index % Math.max(lanes.length, 1)] ?? null;
    return {
      plate: ticket?.plate ?? plateForLane(lane, index),
      time: formatTicketTime(ticket),
      tone: index % 3 === 1 ? "green" : "blue",
      lane: ticket?.assignedLaneName ?? `${[1, 10, 5, 1, 1, 5][index]}车道`,
    };
  });

  return (
    <>
      <SectionPanel title="进场信息" className="h-[382px]">
        <div className="divide-y divide-[#34749a] p-2">
          {rows.slice(0, 5).map((row, index) => (
            <div key={`${row.plate}-${index}`} className="grid grid-cols-[136px_1fr] items-center gap-3 py-2.5 text-white">
              <PlateBadge plate={row.plate} tone={row.tone as "green" | "blue"} />
              <div className="text-[14px] leading-tight">
                <p>时间：</p>
                <p>{row.time}</p>
              </div>
            </div>
          ))}
        </div>
      </SectionPanel>

      <SectionPanel title="引导牌" className="h-[386px]">
        <div className="space-y-4 p-3">
          {rows.map((row, index) => (
            <div key={`${row.plate}-guide-${index}`} className="grid grid-cols-[136px_18px_1fr] items-center gap-2">
              <PlateBadge plate={row.plate} tone={row.tone as "green" | "blue"} />
              <Navigation className="size-5 fill-[#f0b33c] text-[#f0b33c]" />
              <div className="border border-[#e59a26] bg-[linear-gradient(180deg,#d99726_0%,#9b5000_100%)] px-3 py-1 text-center text-[22px] font-black text-white shadow-[inset_0_0_12px_rgba(255,255,255,0.35)]">
                {row.lane}
              </div>
            </div>
          ))}
        </div>
      </SectionPanel>
    </>
  );
}

export function LaneOverviewReference({
  lanes,
  dispatchBoard,
  fullScreen = false,
}: {
  lanes: LaneSnapshot[];
  dispatchBoard?: DispatchBoard;
  fullScreen?: boolean;
}) {
  const [now, setNow] = useState<Date | null>(null);
  const [viewport, setViewport] = useState({ width: 1920, height: 1080 });
  const displayLanes = useMemo(() => buildDisplayLanes(lanes), [lanes]);
  const activeEntryLaneId = dispatchBoard?.activeEntryLaneId ?? null;
  const activeExitLaneId = dispatchBoard?.activeExitLaneId ?? null;
  const scale = fullScreen ? Math.min(viewport.width / 1920, viewport.height / 1080) : 1;

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!fullScreen) {
      return;
    }

    function updateViewport() {
      setViewport({ width: window.innerWidth, height: window.innerHeight });
    }

    updateViewport();
    window.addEventListener("resize", updateViewport);
    return () => window.removeEventListener("resize", updateViewport);
  }, [fullScreen]);

  return (
    <div
      className={cn(
        "bg-[#031225]",
        fullScreen ? "flex h-screen w-screen items-start justify-center overflow-hidden p-0" : "-m-5 overflow-auto p-4 sm:-m-6",
      )}
    >
      <div
        className="relative h-[1080px] w-[1920px] min-w-[1920px] origin-top overflow-hidden bg-[#06182d] text-white shadow-[0_0_36px_rgba(0,0,0,0.8)]"
        style={fullScreen ? { transform: `scale(${scale})` } : undefined}
      >
        <div className="absolute inset-0 bg-[radial-gradient(circle_at_50%_0%,rgba(32,130,198,0.32),transparent_28%),linear-gradient(180deg,#061a32_0%,#041126_100%)]" />
        <div className="absolute inset-0 opacity-25 [background-image:linear-gradient(rgba(84,169,230,0.12)_1px,transparent_1px),linear-gradient(90deg,rgba(84,169,230,0.1)_1px,transparent_1px)] [background-size:44px_44px]" />

        <header className="absolute left-0 right-0 top-0 z-20 h-[88px]">
          <div className="absolute left-0 top-0 h-12 w-[430px] border-t border-[#49bde9] bg-[linear-gradient(145deg,rgba(54,152,220,0.42),rgba(7,24,49,0.9)_70%)] [clip-path:polygon(0_0,96%_0,88%_100%,0_100%)]" />
          <div className="absolute right-0 top-0 h-12 w-[430px] border-t border-[#49bde9] bg-[linear-gradient(215deg,rgba(54,152,220,0.42),rgba(7,24,49,0.9)_70%)] [clip-path:polygon(4%_0,100%_0,100%_100%,12%_100%)]" />
          <div className="absolute left-1/2 top-0 h-[70px] w-[650px] -translate-x-1/2 border-x border-b border-[#50c8ff] bg-[linear-gradient(180deg,#1673b7_0%,#0b2e5a_100%)] shadow-[0_0_24px_rgba(77,194,255,0.78)] [clip-path:polygon(0_0,100%_0,94%_82%,6%_82%)]" />
          <h1 className="absolute left-1/2 top-3 -translate-x-1/2 whitespace-nowrap text-[38px] font-black tracking-[0.14em] text-white [text-shadow:0_3px_0_#0a3e66,0_0_18px_rgba(120,220,255,0.9)]">
            无锡硕放机场出租车排队管理系统
          </h1>
          <div className="absolute left-7 top-4 font-mono text-[22px] tracking-[0.12em] text-[#cbd5e1]">{now ? formatDateTime(now) : ""}</div>
          <div className="absolute right-6 top-4 flex items-center gap-3 text-[20px] text-[#cbd5e1]">
            <Sun className="size-5 fill-[#ff9f1a] text-[#ff9f1a]" />
            <span>多云</span>
            <span>23~34℃</span>
          </div>
        </header>

        <aside className="absolute left-4 top-[215px] z-10 w-[360px] space-y-7">
          <SectionPanel title="黑名单" className="h-[188px]">
            <AlertRows variant="red" />
          </SectionPanel>
          <SectionPanel title="走错车道" className="h-[188px]">
            <AlertRows variant="red" />
          </SectionPanel>
          <SectionPanel title="未进车道" className="h-[188px]">
            <AlertRows variant="cyan" />
          </SectionPanel>
          <SectionPanel title="其他" className="h-[188px]">
            <AlertRows variant="red" count={2} />
          </SectionPanel>
        </aside>

        <main className="absolute left-[395px] top-[124px] z-10 h-[924px] w-[1205px] border-2 border-[#f0d000] bg-[#05162b]/35">
          <div className="absolute -left-[354px] top-0 h-20 w-[354px] border-t-2 border-[#f0d000]" />
          <div className="absolute -right-0 top-0 h-full w-[78px] border-l-2 border-[#f0d000]" />
          <RoadLabel label="出口" className="-left-[178px] -top-3" />
          <RoadLabel label="进口" className="right-[-142px] -top-12" />
          <div className="absolute -left-[325px] top-26 flex items-center gap-4">
            <Car className="size-20 text-[#6bc6ff] drop-shadow-[0_0_10px_rgba(91,202,255,0.9)]" />
            <div className="h-px w-36 bg-[linear-gradient(90deg,#52d8ff,transparent)]" />
          </div>
          <div className="absolute right-[-375px] top-20 flex items-center gap-4">
            <div className="h-px w-36 bg-[linear-gradient(90deg,transparent,#52d8ff)]" />
            <Car className="size-20 scale-x-[-1] text-[#6bc6ff] drop-shadow-[0_0_10px_rgba(91,202,255,0.9)]" />
          </div>

          <div className="absolute left-0 right-[78px] top-0 h-[80px] border-b border-[#26475f]">
            <div className="absolute left-6 right-8 top-10 border-t-2 border-dashed border-[#8aa1b9]/55" />
            {Array.from({ length: 6 }).map((_, index) => (
              <ArrowLeft key={index} className="absolute top-7 size-9 text-[#6f8199]" style={{ left: 280 + index * 170 }} />
            ))}
          </div>

          <div className="absolute bottom-0 left-0 right-0 h-[78px] bg-[#27313a]/82">
            <div className="absolute left-0 right-0 top-0 border-t-4 border-dashed border-white" />
            <div className="absolute left-7 right-8 top-[37px] border-t-2 border-dashed border-white/70" />
            <ArrowLeft className="absolute bottom-2 left-[350px] size-10 text-[#a6aeb7]" />
            <ArrowLeft className="absolute bottom-2 left-[740px] size-10 text-[#a6aeb7]" />
            <span className="absolute left-[110px] top-2">
              <PlateBadge plate="苏B·A12594" tone="green" />
            </span>
            <span className="absolute left-[305px] top-2">
              <PlateBadge plate="苏B·E54G1" tone="blue" />
            </span>
            <span className="absolute left-[790px] top-2">
              <PlateBadge plate="苏B·E54G1" tone="blue" />
            </span>
            <span className="absolute left-[405px] bottom-2">
              <PlateBadge plate="苏B·A12594" tone="green" />
            </span>
            <span className="absolute right-16 bottom-2">
              <PlateBadge plate="苏B·E54G1" tone="blue" />
            </span>
          </div>

          <div className="absolute right-[36px] top-0 bottom-0 w-[46px] border-x-2 border-dashed border-white/75">
            <ArrowDown className="absolute left-1/2 top-[275px] size-9 -translate-x-1/2 text-[#a6aeb7]" />
            <ArrowDown className="absolute left-1/2 top-[520px] size-9 -translate-x-1/2 text-[#a6aeb7]" />
            <ArrowDown className="absolute left-1/2 bottom-24 size-9 -translate-x-1/2 text-[#a6aeb7]" />
          </div>
          <div className="absolute right-[-1px] top-0 bottom-0 w-[78px] bg-[#1f2b32]/72">
            <ArrowLeft className="absolute left-5 top-3 size-8 text-[#a6aeb7]" />
            <ArrowDown className="absolute left-1 top-[275px] size-9 text-[#a6aeb7]" />
            <ArrowDown className="absolute left-1 bottom-24 size-9 text-[#a6aeb7]" />
            <ArrowDown className="absolute right-1 top-[275px] size-9 text-[#a6aeb7]" />
            <ArrowDown className="absolute right-1 bottom-24 size-9 text-[#a6aeb7]" />
          </div>

          <div className="absolute left-3 top-[80px] flex h-[766px] gap-[8px]">
            {displayLanes.map((displayLane) => (
              <LaneColumn
                key={displayLane.slot}
                displayLane={displayLane}
                activeEntryLaneId={activeEntryLaneId}
                activeExitLaneId={activeExitLaneId}
              />
            ))}
          </div>
        </main>

        <aside className="absolute right-4 top-[215px] z-10 w-[290px] space-y-16">
          <InfoList dispatchBoard={dispatchBoard} lanes={lanes} />
        </aside>

        <div className="absolute right-[292px] top-[394px] z-20 grid size-[58px] place-items-center">
          <div className="absolute size-16 rotate-45 border-2 border-[#44e8ec] bg-[#0f727e]/80 shadow-[0_0_18px_rgba(68,232,236,0.8)]" />
          <CloudSun className="relative size-9 text-[#a9fff6]" />
        </div>
        <div className="absolute right-[292px] top-[728px] z-20 grid size-[58px] place-items-center">
          <div className="absolute size-16 rotate-45 border-2 border-[#44e8ec] bg-[#0f727e]/80 shadow-[0_0_18px_rgba(68,232,236,0.8)]" />
          <Navigation className="relative size-9 text-[#a9fff6]" />
        </div>
      </div>
    </div>
  );
}
