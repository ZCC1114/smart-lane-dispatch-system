"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { LockKeyhole, Power } from "lucide-react";
import { useState } from "react";
import { ConfirmModal } from "@/components/confirm-modal";
import { Panel } from "@/components/panel";
import { SignalStack } from "@/components/signal-stack";
import { api } from "@/lib/api";
import type { SignalState } from "@/lib/types";
import { canOperateSignals } from "@/lib/permissions";
import { cn, sensorStatusLabel, signalLabel } from "@/lib/utils";
import { useAuthStore } from "@/stores/auth-store";

const signalOptions: SignalState[] = ["RED", "GREEN"];
const laneTableHeaderClassName = "border-b border-slate-200 px-3 py-3 text-[12px] font-bold uppercase tracking-[0.18em] text-slate-600";
const laneTableCellClassName = "px-3 py-3 align-middle text-sm text-[var(--text-primary)]";

function signalDotClassName(signal: SignalState) {
  if (signal === "GREEN") {
    return "border-emerald-200 bg-emerald-500 shadow-[0_0_0_4px_rgba(16,185,129,0.16)]";
  }
  if (signal === "RED") {
    return "border-rose-200 bg-rose-500 shadow-[0_0_0_4px_rgba(244,63,94,0.16)]";
  }
  return "border-slate-300 bg-slate-400 shadow-[0_0_0_4px_rgba(148,163,184,0.14)]";
}

function SignalDot({ signal }: { signal: SignalState }) {
  return (
    <span
      className={cn("inline-block size-4 rounded-full border", signalDotClassName(signal))}
      title={signalLabel(signal)}
      aria-label={signalLabel(signal)}
    />
  );
}

export default function SignalsPage() {
  const role = useAuthStore((state) => state.user?.role);
  const readOnly = !canOperateSignals(role);
  const queryClient = useQueryClient();
  const [selectedLaneId, setSelectedLaneId] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<{
    laneId: string;
    entrySignal: SignalState;
    exitSignal: SignalState;
    reason: string;
  } | null>(null);

  const lanesQuery = useQuery({
    queryKey: ["lanes"],
    queryFn: api.getLanes,
  });
  const dispatchBoardQuery = useQuery({
    queryKey: ["dispatch-board"],
    queryFn: api.getDispatchBoard,
  });

  const selectedLane = lanesQuery.data?.find((lane) => lane.id === (selectedLaneId ?? lanesQuery.data?.[0]?.id)) ?? lanesQuery.data?.[0];
  const activeEntrySignalName = dispatchBoardQuery.data?.activeEntryLaneId
    ? `${dispatchBoardQuery.data.activeEntryLaneName ?? dispatchBoardQuery.data.activeEntryLaneId} 入口`
    : "未开启";
  const activeExitSignalName = dispatchBoardQuery.data?.activeExitLaneId
    ? `${dispatchBoardQuery.data.activeExitLaneName ?? dispatchBoardQuery.data.activeExitLaneId} 出口`
    : "未开启";
  const waitingAssignments = dispatchBoardQuery.data?.waitingAssignments.length ?? 0;

  const signalMutation = useMutation({
    mutationFn: () => api.updateSignal(pendingAction!),
    onSuccess: async () => {
      setPendingAction(null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["lanes"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
        queryClient.invalidateQueries({ queryKey: ["dispatch-board"] }),
      ]);
    },
  });

  if (lanesQuery.isLoading || !lanesQuery.data) {
    return <div className="rounded-sm border border-[var(--border-soft)] px-5 py-6 text-sm text-[var(--text-secondary)]">正在加载信号灯控制面板...</div>;
  }

  return (
    <>
      <ConfirmModal
        open={Boolean(pendingAction)}
        title="确认发送信号指令"
        description={`目标车道 ${selectedLane?.name ?? ""} 将更新对应方向放行游标，入口与出口互不抢占。`}
        confirmText="确认下发"
        busy={signalMutation.isPending}
        onCancel={() => setPendingAction(null)}
        onConfirm={() => signalMutation.mutate()}
      />

      <div className="space-y-6">
        <Panel
          title="全局调度状态"
          eyebrow="入口 / 出口放行游标"
        >
          <div className="grid gap-4 lg:grid-cols-4">
            {[
              {
                label: "入口放行",
                value: activeEntrySignalName,
                tone: "emerald",
              },
              {
                label: "出口放行",
                value: activeExitSignalName,
                tone: "emerald",
              },
              {
                label: "放行规则",
                value: "入口/出口各一",
                tone: "sky",
              },
              {
                label: "待入道预留",
                value: `${waitingAssignments} 辆`,
                tone: "slate",
              },
            ].map((item) => (
              <div
                key={item.label}
                className={`rounded-sm border px-5 py-4 ${
                  item.tone === "emerald"
                    ? "border-emerald-200 bg-emerald-50/70"
                    : item.tone === "sky"
                      ? "border-sky-200 bg-sky-50/75"
                      : "border-[var(--border-soft)] bg-[var(--bg-panel-soft)]"
                }`}
              >
                <p className="text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">{item.label}</p>
                <p className="mt-3 text-2xl font-semibold tracking-tight text-[var(--text-primary)]">{item.value}</p>
              </div>
            ))}
          </div>
        </Panel>

        <div className="grid gap-6 xl:grid-cols-[1.35fr_0.95fr]">
        <Panel
          title="车道总览"
          eyebrow="点击车道后在右侧执行信号控制"
        >
          <div className="overflow-x-auto rounded-sm border border-[var(--border-soft)] bg-white">
            <table className="min-w-[820px] w-full table-fixed border-collapse text-sm">
	              <colgroup>
	                <col className="w-[8%]" />
	                <col className="w-[18%]" />
	                <col className="w-[12%]" />
	                <col className="w-[12%]" />
	                <col className="w-[17%]" />
	                <col className="w-[16%]" />
	                <col className="w-[17%]" />
	              </colgroup>
              <thead className="bg-slate-50/85">
                <tr className="text-left">
                  {["序号", "车道", "入口灯", "出口灯", "车辆在场", "待入道预留", "传感"].map((header) => (
                    <th
                      key={header}
                      scope="col"
                      className={cn(
                        laneTableHeaderClassName,
                        header === "车道" ? "text-left" : "text-center",
                      )}
                    >
                      {header}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {lanesQuery.data.map((lane, index) => {
                  const selected = selectedLane?.id === lane.id;
                  return (
                    <tr
                      key={lane.id}
                      onClick={() => setSelectedLaneId(lane.id)}
                      className={cn(
                        "cursor-pointer border-b border-slate-200/80 transition last:border-b-0 hover:bg-slate-50/70",
                        selected && "bg-sky-50/85",
                      )}
                    >
                      <td className={cn(laneTableCellClassName, "text-center font-medium whitespace-nowrap")}>{index + 1}</td>
                      <td className={cn(laneTableCellClassName, "align-top pr-2")}>
                        <div>
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="text-base font-semibold text-[var(--text-primary)]">{lane.name}</span>
                          </div>
                          <p className="mt-1 text-xs text-[var(--text-secondary)]">{lane.code}</p>
                        </div>
                      </td>
                      <td className={cn(laneTableCellClassName, "text-center")}>
                        <SignalDot signal={lane.entrySignal} />
                      </td>
                      <td className={cn(laneTableCellClassName, "text-center")}>
                        <SignalDot signal={lane.exitSignal} />
                      </td>
                      <td className={cn(laneTableCellClassName, "text-center font-medium whitespace-nowrap")}>{lane.vehicleCount} / {lane.capacity}</td>
                      <td className={cn(laneTableCellClassName, "text-center font-medium whitespace-nowrap")}>{lane.reservedCount} 辆</td>
                      <td className={cn(laneTableCellClassName, "text-center font-medium whitespace-nowrap")}>{sensorStatusLabel(lane.sensorStatus)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {lanesQuery.data.length === 0 ? (
              <div className="px-4 py-8 text-center text-sm text-[var(--text-secondary)]">
                当前数据库没有车道数据，暂时无法下发信号控制。
              </div>
            ) : null}
          </div>
        </Panel>

        {selectedLane ? (
          <Panel
            title={`${selectedLane.name} 信号工作台`}
            eyebrow="当前选中车道"
            action={
              readOnly ? (
                <div className="inline-flex items-center gap-2 rounded-sm border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-700">
                  <LockKeyhole className="size-3.5" />
                  当前账号仅可查看
                </div>
              ) : null
            }
          >
            <div className="grid gap-6 xl:grid-cols-[0.88fr_1.12fr]">
              <div className="rounded-sm border border-[var(--border-soft)] bg-white p-6">
                <div className="flex items-center justify-between gap-3">
                  <div>
                    <p className="text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">当前信号态</p>
                    <p className="mt-2 text-lg font-semibold text-[var(--text-primary)]">入口 / 出口 实时状态</p>
                  </div>
                  <Power className="size-5 text-[var(--text-muted)]" />
                </div>
                <div className="mt-5">
                  <SignalStack entrySignal={selectedLane.entrySignal} exitSignal={selectedLane.exitSignal} />
                </div>
              </div>

              <div className="space-y-4">
                {[
	                  { key: "entrySignal", label: "入口信号", value: selectedLane.entrySignal },
	                  { key: "exitSignal", label: "出口信号", value: selectedLane.exitSignal },
	                ].map((field) => (
                  <div key={field.key} className="rounded-sm border border-[var(--border-soft)] bg-slate-50/70 p-5">
                    <div className="flex items-center justify-between gap-3">
                      <div>
                        <p className="text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">{field.label}</p>
                        <p className="mt-2 text-xl font-semibold text-[var(--text-primary)]">{signalLabel(field.value)}</p>
                      </div>
                      <Power className="size-5 text-[var(--text-muted)]" />
                    </div>
	                    <div className="mt-4 grid gap-3 md:grid-cols-2">
	                      {signalOptions.map((signal) => (
	                        <button
                          key={signal}
                          type="button"
                          disabled={readOnly}
	                          onClick={() =>
	                            setPendingAction({
	                              laneId: selectedLane.id,
	                              entrySignal: field.key === "entrySignal" ? signal : selectedLane.entrySignal,
	                              exitSignal: field.key === "exitSignal" ? signal : selectedLane.exitSignal,
	                              reason: `控制台切换${field.key === "entrySignal" ? "入口" : "出口"}放行游标：${selectedLane.name}${field.label}至${signal}`,
	                            })
	                          }
                          className="rounded-sm border border-[var(--border-soft)] px-4 py-2 text-sm text-[var(--text-secondary)] transition hover:border-[var(--border-strong)] hover:text-[var(--text-primary)] disabled:opacity-50"
                        >
                          {signalLabel(signal)}
                        </button>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </Panel>
        ) : null}
      </div>
      </div>
    </>
  );
}
