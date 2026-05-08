"use client";

import Link from "next/link";
import { MoreVertical, Signal } from "lucide-react";
import type { LaneSnapshot } from "@/lib/types";
import { cn, formatPlateDisplay, formatShortTime, laneStatusLabel, laneTypeLabel, sensorStatusLabel } from "@/lib/utils";

function activeSignal(lane: LaneSnapshot) {
  if (lane.entrySignal !== "OFFLINE") return lane.entrySignal;
  return lane.exitSignal;
}

export function LaneOverviewCard({
  lane,
  canOperate,
}: {
  lane: LaneSnapshot;
  canOperate: boolean;
}) {
  const signal = activeSignal(lane);
  const statusColor =
    lane.status === "OPEN"
      ? "bg-green-500"
      : lane.status === "BUSY"
        ? "bg-amber-500"
        : lane.status === "FULL"
          ? "bg-red-500"
          : "bg-slate-400";

  return (
    <article className="overflow-hidden rounded-sm border border-[var(--border-soft)] bg-[var(--bg-panel-strong)] transition-colors hover:border-blue-300">
      <div className="flex items-center justify-between border-b border-[var(--border-soft)] p-4">
        <div className="flex items-center gap-3">
          <span
            className={cn(
              "rounded-sm px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.18em]",
              lane.type === "ENTRY"
                ? "bg-blue-50 text-blue-700"
                : lane.type === "EXIT"
                  ? "bg-slate-100 text-slate-700"
                  : "bg-violet-50 text-violet-700",
            )}
          >
            {laneTypeLabel(lane.type)}
          </span>
          <span className="text-sm font-bold text-[var(--text-primary)]">{lane.name}</span>
        </div>
        <button type="button" className="text-slate-400 transition-colors hover:text-slate-900">
          <MoreVertical className="size-4" />
        </button>
      </div>

      <div className="bg-slate-50/70 p-6">
        <div className="mb-4 flex items-center justify-between">
          <div className="space-y-1">
            <p className="text-[10px] font-bold uppercase tracking-[0.18em] text-slate-500">在道车辆 / 预留</p>
            <p className="font-mono text-3xl font-bold tracking-wide text-blue-700">
              {lane.vehicleCount}
              <span className="text-lg font-semibold text-slate-500"> / {lane.reservedCount}</span>
            </p>
          </div>
          <div>
            {signal === "OFFLINE" ? (
              <div className="rounded-sm border border-slate-200 bg-slate-100 px-2 py-1 text-[10px] font-bold uppercase tracking-[0.18em] text-slate-500">
                信号离线
              </div>
            ) : (
              <div className="flex items-center space-x-1.5 rounded-sm border border-slate-200 bg-white px-3 py-1 shadow-sm">
                <div className={cn("size-2 rounded-full", signal === "RED" ? "bg-red-500 shadow-[0_0_8px_rgba(239,68,68,0.45)]" : "bg-slate-200")} />
                <div className={cn("size-2 rounded-full", signal === "GREEN" ? "bg-green-500 shadow-[0_0_8px_rgba(34,197,94,0.45)]" : "bg-slate-200")} />
              </div>
            )}
          </div>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="rounded-sm border border-[var(--border-soft)] bg-white p-3">
            <p className="text-[10px] font-bold uppercase text-slate-500">最后动作时间</p>
            <p className="mt-1 font-mono text-sm text-[var(--text-primary)]">
              {lane.lastActionAt ? formatShortTime(lane.lastActionAt) : "--:--"}
            </p>
          </div>
          <div className="rounded-sm border border-[var(--border-soft)] bg-white p-3">
            <p className="text-[10px] font-bold uppercase text-slate-500">车道状态</p>
            <div className="mt-1 flex items-center">
              <span className={cn("mr-2 size-2 rounded-full", statusColor)} />
              <span className="text-sm font-bold text-[var(--text-primary)]">{laneStatusLabel(lane.status)}</span>
            </div>
          </div>
        </div>

        <div className="mt-4 flex items-center justify-between text-xs text-[var(--text-secondary)]">
          <span>{lane.mode === "AUTO" ? "自动联动" : lane.mode === "MANUAL" ? "人工控制" : "设备离线"}</span>
          <span>剩余可分配 {lane.availableSlots}</span>
        </div>
        <div className="mt-2 flex items-center justify-between text-xs text-[var(--text-secondary)]">
          <span>{lane.ledMessage}</span>
          <span>{lane.lastSensorAt ? `采集 ${formatShortTime(lane.lastSensorAt)}` : "暂无采集"}</span>
        </div>
        <div className="mt-2 flex items-center justify-between text-xs text-[var(--text-secondary)]">
          <span>传感器 {sensorStatusLabel(lane.sensorStatus)}</span>
          <span>{formatPlateDisplay(lane.currentPlate) || "暂无当前车牌"}</span>
        </div>
      </div>

      <div className="flex gap-2 border-t border-[var(--border-soft)] bg-white px-4 py-3">
        <Link
          href={canOperate ? "/signals" : "/entries"}
          className="flex-1 rounded-sm border border-slate-200 bg-slate-50 px-4 py-1.5 text-center text-[11px] font-bold uppercase tracking-[0.18em] text-slate-700 transition-colors hover:bg-slate-100"
        >
          {canOperate ? "远程开闸" : "查看记录"}
        </Link>
        <Link
          href={canOperate ? "/dispatch" : "/entries"}
          className="flex items-center justify-center rounded-sm border border-slate-200 bg-slate-50 px-3 py-1.5 text-slate-700 transition-colors hover:bg-slate-100"
        >
          <Signal className="size-3" />
        </Link>
      </div>
    </article>
  );
}
