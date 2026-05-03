"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, CircleOff, MapPinned, MoveRight, TrafficCone } from "lucide-react";
import { useState } from "react";
import { ConfirmModal } from "@/components/confirm-modal";
import { Panel } from "@/components/panel";
import { api } from "@/lib/api";
import type { LaneSnapshot, LaneStatus, SignalState } from "@/lib/types";
import { cn, signalLabel } from "@/lib/utils";

const signalOptions: SignalState[] = ["RED", "GREEN"];

type SignalKey = "entrySignal" | "exitSignal";

interface DisplayLane {
  slot: number;
  lane: LaneSnapshot | null;
}

function createPreviewLanes(): LaneSnapshot[] {
  const now = new Date().toISOString();
  const presets = [
    { vehicleCount: 2, capacity: 6, status: "OPEN", entrySignal: "GREEN", exitSignal: "RED", priority: false },
    { vehicleCount: 4, capacity: 6, status: "BUSY", entrySignal: "RED", exitSignal: "RED", priority: false },
    { vehicleCount: 6, capacity: 6, status: "FULL", entrySignal: "RED", exitSignal: "RED", priority: false },
    { vehicleCount: 1, capacity: 5, status: "OPEN", entrySignal: "GREEN", exitSignal: "GREEN", priority: true },
    { vehicleCount: 3, capacity: 7, status: "OPEN", entrySignal: "GREEN", exitSignal: "RED", priority: false },
    { vehicleCount: 5, capacity: 7, status: "BUSY", entrySignal: "RED", exitSignal: "RED", priority: false },
    { vehicleCount: 0, capacity: 6, status: "OPEN", entrySignal: "GREEN", exitSignal: "RED", priority: false },
    { vehicleCount: 6, capacity: 8, status: "BUSY", entrySignal: "RED", exitSignal: "RED", priority: false },
    { vehicleCount: 7, capacity: 8, status: "FULL", entrySignal: "RED", exitSignal: "RED", priority: false },
    { vehicleCount: 2, capacity: 5, status: "OPEN", entrySignal: "GREEN", exitSignal: "RED", priority: false },
    { vehicleCount: 4, capacity: 6, status: "BUSY", entrySignal: "RED", exitSignal: "GREEN", priority: true },
  ] as const;

  return presets.map((preset, index) => ({
    id: `PREVIEW-L${String(index + 1).padStart(2, "0")}`,
    code: `T${String(index + 1).padStart(2, "0")}`,
    name: `出租车蓄车道 ${String(index + 1).padStart(2, "0")}`,
    zone: "出租车蓄车区",
    type: "MIXED",
    status: preset.status,
    mode: "AUTO",
    capacity: preset.capacity,
    vehicleCount: preset.vehicleCount,
    currentPlate: preset.vehicleCount > 0 ? `苏A${String(8300 + index).padStart(5, "0")}` : null,
    lastActionAt: now,
    entrySignal: preset.entrySignal,
    exitSignal: preset.exitSignal,
    ledMessage:
      preset.status === "FULL" ? "车位已满，暂停入场" : preset.status === "BUSY" ? "车辆排队中，请减速" : "允许车辆有序通行",
    ledStatus: "SYNCED",
    priority: preset.priority,
    sensorStatus: "ONLINE",
    lastSensorAt: now,
    lastEntryPlate: preset.vehicleCount > 0 ? `苏A${String(8300 + index).padStart(5, "0")}` : null,
    lastEntryAt: now,
    reservedCount: 0,
    availableSlots: preset.capacity - preset.vehicleCount,
  }));
}

function extractLaneOrder(lane: LaneSnapshot) {
  const raw = `${lane.code} ${lane.name}`;
  const matched = raw.match(/\d+/);
  return matched ? Number.parseInt(matched[0], 10) : Number.MAX_SAFE_INTEGER;
}

function buildDisplayLanes(lanes: LaneSnapshot[]): DisplayLane[] {
  const ordered = [...lanes].sort((left, right) => {
    const byNumber = extractLaneOrder(right) - extractLaneOrder(left);
    if (byNumber !== 0) {
      return byNumber;
    }
    return `${right.code}-${right.name}`.localeCompare(`${left.code}-${left.name}`, "zh-CN");
  });

  return Array.from({ length: 11 }, (_, index): DisplayLane => ({
    slot: 11 - index,
    lane: ordered[index] ?? null,
  }));
}

function laneTheme(status: LaneStatus | null) {
  if (status === "OPEN") {
    return {
      container: "border-[#bfd4c9] bg-linear-to-r from-[#dfe9e3] via-[#eaf2ed] to-[#f6faf7] text-slate-700",
      badge: "border-white/85 bg-white/70 text-slate-800",
      flow: "text-emerald-700/22",
    };
  }

  if (status === "BUSY") {
    return {
      container: "border-[#c7d5df] bg-linear-to-r from-[#e3ebf0] via-[#edf3f6] to-[#f8fbfc] text-slate-700",
      badge: "border-white/85 bg-white/72 text-slate-800",
      flow: "text-sky-700/18",
    };
  }

  if (status === "FULL") {
    return {
      container: "border-[#dec7cc] bg-linear-to-r from-[#efe2e5] via-[#f5ecee] to-[#fbf8f8] text-slate-700",
      badge: "border-white/85 bg-white/72 text-slate-800",
      flow: "text-rose-700/18",
    };
  }

  return {
    container: "border-[#d4dae2] bg-linear-to-r from-[#e9edf1] via-[#f1f4f7] to-[#fafbfd] text-slate-600",
    badge: "border-white/85 bg-white/75 text-slate-700",
    flow: "text-slate-500/14",
  };
}

function passStatus(lane: LaneSnapshot | null) {
  if (!lane || lane.status === "OFFLINE") {
    return { label: "未接入", className: "border-slate-300 bg-slate-100 text-slate-600" };
  }

  const entrySignal = normalizeSignal(lane.entrySignal);
  const exitSignal = normalizeSignal(lane.exitSignal);

  if (lane.status === "FULL" || (entrySignal === "RED" && exitSignal === "RED")) {
    return { label: "禁止通行", className: "border-rose-300 bg-rose-100 text-rose-700" };
  }

  if (entrySignal === "GREEN" || exitSignal === "GREEN") {
    return { label: "允许通行", className: "border-emerald-300 bg-emerald-100 text-emerald-700" };
  }

  return { label: "待放行", className: "border-sky-300 bg-sky-100 text-sky-700" };
}

function normalizeSignal(signal: SignalState): SignalState {
  if (signal === "OFFLINE") {
    return "OFFLINE";
  }

  return signal === "GREEN" ? "GREEN" : "RED";
}

function signalLampClasses(signal: SignalState) {
  if (signal === "GREEN") {
    return "border-emerald-300 bg-emerald-500 shadow-[0_0_18px_rgba(16,185,129,0.45)]";
  }

  if (signal === "RED") {
    return "border-rose-300 bg-rose-500 shadow-[0_0_18px_rgba(244,63,94,0.4)]";
  }

  return "border-slate-300 bg-slate-300";
}

function signalOptionClasses(signal: SignalState) {
  if (signal === "GREEN") {
    return "border-emerald-300 bg-emerald-50 text-emerald-700 hover:bg-emerald-100";
  }

  return "border-rose-300 bg-rose-50 text-rose-700 hover:bg-rose-100";
}

function laneNumberLabel(lane: LaneSnapshot | null, slot: number) {
  const order = lane ? extractLaneOrder(lane) : slot;
  const normalized = !Number.isFinite(order) || order === Number.MAX_SAFE_INTEGER ? slot : order;
  return String(normalized).padStart(2, "0");
}

function SignalTerminal({
  lane,
  signalKey,
  canOperate,
  open,
  onToggleOpen,
  onChooseSignal,
}: {
  lane: LaneSnapshot | null;
  signalKey: SignalKey;
  canOperate: boolean;
  open: boolean;
  onToggleOpen: () => void;
  onChooseSignal: (signal: SignalState) => void;
}) {
  const signal = normalizeSignal(lane ? lane[signalKey] : "OFFLINE");
  const disabled = !lane || !canOperate || lane.status === "OFFLINE";
  const label = signalKey === "entrySignal" ? "入口灯" : "出口灯";

  return (
    <div className="relative flex flex-col items-center gap-2">
      <span className="text-[10px] font-semibold uppercase tracking-[0.22em] text-slate-500">{label}</span>
      <button
        type="button"
        disabled={disabled}
        onClick={onToggleOpen}
        className={cn(
          "flex h-12 w-12 items-center justify-center rounded-full border bg-slate-950/90 transition-transform duration-200",
          disabled ? "cursor-not-allowed opacity-60" : "hover:-translate-y-0.5",
        )}
      >
        <span className={cn("h-6 w-6 rounded-full border-2", signalLampClasses(signal))} />
      </button>

      {open ? (
        <div className="absolute left-1/2 top-full z-30 mt-2 w-28 -translate-x-1/2 rounded-2xl border border-[var(--border-soft)] bg-white/95 p-2 shadow-xl backdrop-blur">
          <div className="grid gap-1">
            {signalOptions.map((option) => (
              <button
                key={option}
                type="button"
                onClick={() => onChooseSignal(option)}
                className={cn(
                  "rounded-xl border px-2 py-1.5 text-xs font-semibold transition-colors",
                  signalOptionClasses(option),
                )}
              >
                {signalLabel(option)}
              </button>
            ))}
          </div>
        </div>
      ) : null}
    </div>
  );
}

function LaneStrip({ slot, lane }: { slot: number; lane: LaneSnapshot | null }) {
  const theme = laneTheme(lane?.status ?? null);
  const laneNumber = laneNumberLabel(lane, slot);
  const canRelease = lane ? normalizeSignal(lane.exitSignal) === "GREEN" : false;
  const flowArrows = Array.from({ length: 12 }, (_, index) => index);

  return (
    <div
      className={cn(
        "relative flex h-16 items-center justify-center overflow-hidden rounded-[24px] border shadow-[0_14px_30px_rgba(15,23,42,0.08)] transition-transform duration-200 hover:scale-[1.01]",
        theme.container,
      )}
      title={lane?.name ?? `${laneNumber} 号车道`}
    >
      {canRelease ? (
        <div className={cn("lane-flow-track pointer-events-none absolute inset-y-0 left-0 flex w-[220%] items-center gap-8 px-5", theme.flow)}>
          {flowArrows.map((index) => (
            <ArrowRight key={index} className="size-4 shrink-0" />
          ))}
        </div>
      ) : null}

      <span className={cn("relative z-10 rounded-full border px-5 py-2 text-2xl font-bold tracking-[0.34em] backdrop-blur-sm", theme.badge)}>
        {laneNumber}
      </span>
    </div>
  );
}

export function LaneYardMap({ lanes, canOperate }: { lanes: LaneSnapshot[]; canOperate: boolean }) {
  const queryClient = useQueryClient();
  const [previewLanes, setPreviewLanes] = useState<LaneSnapshot[]>(() => createPreviewLanes());
  const usingPreview = lanes.length === 0;
  const effectiveLanes = usingPreview ? previewLanes : lanes;
  const displayLanes = buildDisplayLanes(effectiveLanes);
  const hiddenLaneCount = Math.max(effectiveLanes.length - displayLanes.length, 0);
  const [editor, setEditor] = useState<{ laneId: string; signalKey: SignalKey } | null>(null);
  const [pendingAction, setPendingAction] = useState<{ lane: LaneSnapshot; signalKey: SignalKey; signal: SignalState } | null>(null);

  const signalMutation = useMutation({
    mutationFn: () => {
      if (!pendingAction) {
        throw new Error("缺少待提交的信号控制参数");
      }

      return api.updateSignal({
        laneId: pendingAction.lane.id,
        entrySignal: pendingAction.signalKey === "entrySignal" ? pendingAction.signal : normalizeSignal(pendingAction.lane.entrySignal),
        exitSignal: pendingAction.signalKey === "exitSignal" ? pendingAction.signal : normalizeSignal(pendingAction.lane.exitSignal),
        mode: "MANUAL",
        reason: `场区图手动切换${pendingAction.signalKey === "entrySignal" ? "入口" : "出口"}信号至${signalLabel(pendingAction.signal)}`,
      });
    },
    onSuccess: async () => {
      setPendingAction(null);
      setEditor(null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
        queryClient.invalidateQueries({ queryKey: ["lanes"] }),
      ]);
    },
  });

  function applyPreviewSignalChange() {
    if (!pendingAction) {
      return;
    }

    setPreviewLanes((current) =>
      current.map((lane) => {
        if (lane.id !== pendingAction.lane.id) {
          return lane;
        }

        const nextLane = {
          ...lane,
          mode: "MANUAL",
          lastActionAt: new Date().toISOString(),
          entrySignal: pendingAction.signalKey === "entrySignal" ? pendingAction.signal : normalizeSignal(lane.entrySignal),
          exitSignal: pendingAction.signalKey === "exitSignal" ? pendingAction.signal : normalizeSignal(lane.exitSignal),
        } satisfies LaneSnapshot;

        return {
          ...nextLane,
          ledMessage:
            nextLane.entrySignal === "RED" && nextLane.exitSignal === "RED"
              ? "人工禁止通行"
              : nextLane.exitSignal === "GREEN"
                ? "人工允许通行"
                : "人工待放行",
        };
      }),
    );
    setPendingAction(null);
    setEditor(null);
  }

  return (
    <>
      <ConfirmModal
        open={Boolean(pendingAction)}
        title="确认切换车道信号"
        description={
          pendingAction
            ? `${pendingAction.lane.name} 的${pendingAction.signalKey === "entrySignal" ? "入口灯" : "出口灯"}将切换为${signalLabel(
                pendingAction.signal,
              )}，并将车道控制模式切换为手动。`
            : ""
        }
        confirmText="确认切换"
        busy={signalMutation.isPending}
        onCancel={() => {
          setPendingAction(null);
          setEditor(null);
        }}
        onConfirm={() => {
          if (usingPreview) {
            applyPreviewSignalChange();
            return;
          }
          signalMutation.mutate();
        }}
      />

      <Panel
        title="场区 11 车道纵览"
        eyebrow="出租车待蓄区平面示意"
        action={
          <div className="flex flex-wrap items-center gap-2 text-[11px] font-semibold text-slate-600">
            <span className="inline-flex items-center gap-1 rounded-full border border-[#bfd4c9] bg-[#eef5f1] px-2.5 py-1 text-[#49685a]">正常</span>
            <span className="inline-flex items-center gap-1 rounded-full border border-[#c7d5df] bg-[#eef4f7] px-2.5 py-1 text-[#506774]">繁忙</span>
            <span className="inline-flex items-center gap-1 rounded-full border border-[#dec7cc] bg-[#f7eff1] px-2.5 py-1 text-[#775962]">禁入</span>
            <span className="inline-flex items-center gap-1 rounded-full border border-slate-300 bg-slate-100 px-2.5 py-1 text-slate-600">离线</span>
          </div>
        }
      >
        <div className="overflow-x-auto">
          <div className="relative min-w-[1160px] overflow-visible rounded-[36px] border border-slate-200 bg-[linear-gradient(180deg,#d9e7fb_0%,#d5e4fa_38%,#d9e7fb_100%)] p-8">
            <div className="absolute left-6 right-56 top-6 h-24 rounded-[999px] border-[14px] border-white/70 border-b-0 opacity-95" />
            <div className="absolute bottom-6 left-4 right-40 h-28 rounded-[999px] border-[14px] border-white/70 border-t-0 opacity-95" />
            <div className="absolute bottom-24 right-8 top-28 w-28 rounded-[999px] border-[14px] border-white/70 border-l-0 opacity-95" />
            <div className="absolute left-14 top-20 flex items-center gap-2 rounded-full bg-white/80 px-4 py-2 text-xs font-semibold text-slate-600 shadow-sm">
              <MapPinned className="size-4 text-blue-500" />
              场区外围道路
            </div>

            <div className="absolute right-10 top-[10.5rem] flex items-center gap-2 rounded-full bg-white/[0.92] px-4 py-2 text-sm font-semibold text-slate-700 shadow-lg">
              <MoveRight className="size-4 text-emerald-600" />
              出口
            </div>
            <div className="absolute bottom-24 right-10 flex items-center gap-2 rounded-full bg-white/[0.92] px-4 py-2 text-sm font-semibold text-slate-700 shadow-lg">
              <ArrowRight className="size-4 text-blue-600" />
              入口
            </div>

            <div className="relative z-10 ml-[4.5rem] mt-24 w-[780px] rounded-[32px] border border-white/[0.75] bg-white/[0.38] px-6 py-7 shadow-[0_24px_44px_rgba(15,23,42,0.10)] backdrop-blur-sm">
              <div className="mb-5 flex items-center justify-between">
                <div>
                  <p className="text-[11px] font-semibold uppercase tracking-[0.28em] text-slate-500">车道平面</p>
                  <h3 className="mt-1 text-xl font-semibold text-slate-900">入口汇入右下，出口汇出右上，11 条车道并行排布</h3>
                </div>
                <div className="rounded-2xl border border-white/80 bg-white/[0.65] px-4 py-2 text-right text-xs text-slate-600">
                  <p className="font-semibold text-slate-900">{effectiveLanes.length}</p>
                  <p>{usingPreview ? "预览车道" : "已接入车道"}</p>
                </div>
              </div>

              <div className="space-y-3">
                {displayLanes.map(({ slot, lane }) => (
                  <div key={slot} className="grid grid-cols-[80px_minmax(0,1fr)_80px] items-center gap-3">
                    <SignalTerminal
                      lane={lane}
                      signalKey="entrySignal"
                      canOperate={canOperate}
                      open={Boolean(editor && lane && editor.laneId === lane.id && editor.signalKey === "entrySignal")}
                      onToggleOpen={() => setEditor(editor && lane && editor.laneId === lane.id && editor.signalKey === "entrySignal" ? null : lane ? { laneId: lane.id, signalKey: "entrySignal" } : null)}
                      onChooseSignal={(signal) => {
                        if (!lane) {
                          return;
                        }
                        setEditor(null);
                        setPendingAction({ lane, signalKey: "entrySignal", signal });
                      }}
                    />

                    <LaneStrip slot={slot} lane={lane} />

                    <SignalTerminal
                      lane={lane}
                      signalKey="exitSignal"
                      canOperate={canOperate}
                      open={Boolean(editor && lane && editor.laneId === lane.id && editor.signalKey === "exitSignal")}
                      onToggleOpen={() => setEditor(editor && lane && editor.laneId === lane.id && editor.signalKey === "exitSignal" ? null : lane ? { laneId: lane.id, signalKey: "exitSignal" } : null)}
                      onChooseSignal={(signal) => {
                        if (!lane) {
                          return;
                        }
                        setEditor(null);
                        setPendingAction({ lane, signalKey: "exitSignal", signal });
                      }}
                    />
                  </div>
                ))}
              </div>
            </div>

            <aside className="absolute right-8 top-36 z-20 w-[260px] space-y-4">
              <div className="rounded-[28px] border border-white/80 bg-white/[0.88] p-5 shadow-[0_18px_36px_rgba(15,23,42,0.12)] backdrop-blur-sm">
                <p className="text-[11px] font-semibold uppercase tracking-[0.28em] text-slate-500">操作说明</p>
                <div className="mt-4 space-y-3 text-sm text-slate-600">
                  <p>1. 车道颜色代表当前运行状态，绿为正常，黄为繁忙，红为满停禁入。</p>
                  <p>2. 黄色出租车块表示已停放车辆，空白块表示待停位置。</p>
                  <p>3. 点击车道两端信号灯可切换红黄绿，提交后会立即下发手动覆盖命令。</p>
                </div>
              </div>

              <div className="rounded-[28px] border border-white/80 bg-slate-950 p-5 text-sm text-slate-200 shadow-[0_18px_36px_rgba(15,23,42,0.18)]">
                <p className="text-[11px] font-semibold uppercase tracking-[0.28em] text-slate-400">快速摘要</p>
                <div className="mt-4 space-y-3">
                  <div className="flex items-center justify-between rounded-2xl bg-white/[0.06] px-3 py-2">
                    <span>可通行车道</span>
                    <span className="font-semibold text-white">{effectiveLanes.filter((lane) => passStatus(lane).label === "允许通行").length}</span>
                  </div>
                  <div className="flex items-center justify-between rounded-2xl bg-white/[0.06] px-3 py-2">
                    <span>满停车道</span>
                    <span className="font-semibold text-white">{effectiveLanes.filter((lane) => lane.status === "FULL").length}</span>
                  </div>
                  <div className="flex items-center justify-between rounded-2xl bg-white/[0.06] px-3 py-2">
                    <span>当前在场车辆</span>
                    <span className="font-semibold text-white">{effectiveLanes.reduce((sum, lane) => sum + lane.vehicleCount, 0)}</span>
                  </div>
                </div>

                {!canOperate ? (
                  <div className="mt-4 flex items-start gap-2 rounded-2xl border border-amber-500/30 bg-amber-500/10 px-3 py-3 text-xs text-amber-100">
                    <TrafficCone className="mt-0.5 size-4 shrink-0" />
                    当前账号仅可查看车道状态，不能修改信号灯。
                  </div>
                ) : null}

                {hiddenLaneCount > 0 ? (
                  <p className="mt-4 text-xs text-slate-400">还有 {hiddenLaneCount} 条车道未在当前 11 车道平面图中展示。</p>
                ) : null}
              </div>
            </aside>
          </div>
        </div>

        {usingPreview ? (
          <div className="mt-4 flex items-start gap-3 rounded-[24px] border border-dashed border-[var(--border-strong)] bg-slate-50 px-4 py-4 text-sm text-[var(--text-secondary)]">
            <CircleOff className="mt-0.5 size-4 shrink-0 text-slate-500" />
            当前数据库还没有真实车道记录，页面正在使用 11 条车道的前端预览数据。你可以直接点两端信号灯演示交互；后端接入真实车道后会自动切换。
          </div>
        ) : null}
      </Panel>
    </>
  );
}
