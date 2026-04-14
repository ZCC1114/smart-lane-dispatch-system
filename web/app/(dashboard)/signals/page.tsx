"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Power, RefreshCcw, ShieldAlert } from "lucide-react";
import { useState } from "react";
import { ConfirmModal } from "@/components/confirm-modal";
import { Panel } from "@/components/panel";
import { SignalStack } from "@/components/signal-stack";
import { StatusBadge } from "@/components/status-badge";
import { api } from "@/lib/api";
import type { LaneMode, SignalState } from "@/lib/types";
import { canOperateSignals } from "@/lib/permissions";
import { formatDateTime, laneStatusLabel, ledStatusLabel, sensorStatusLabel, signalLabel } from "@/lib/utils";
import { useAuthStore } from "@/stores/auth-store";

const signalOptions: SignalState[] = ["RED", "YELLOW", "GREEN"];

export default function SignalsPage() {
  const role = useAuthStore((state) => state.user?.role);
  const readOnly = !canOperateSignals(role);
  const queryClient = useQueryClient();
  const [selectedLaneId, setSelectedLaneId] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<{
    laneId: string;
    entrySignal: SignalState;
    exitSignal: SignalState;
    mode: LaneMode;
    reason: string;
  } | null>(null);

  const lanesQuery = useQuery({
    queryKey: ["lanes"],
    queryFn: api.getLanes,
  });

  const selectedLane = lanesQuery.data?.find((lane) => lane.id === (selectedLaneId ?? lanesQuery.data?.[0]?.id)) ?? lanesQuery.data?.[0];

  const signalMutation = useMutation({
    mutationFn: () => api.updateSignal(pendingAction!),
    onSuccess: async () => {
      setPendingAction(null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["lanes"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
        queryClient.invalidateQueries({ queryKey: ["alerts"] }),
      ]);
    },
  });

  const restoreMutation = useMutation({
    mutationFn: api.restoreAutoControl,
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["lanes"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
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
        description={`目标车道 ${selectedLane?.name ?? ""} 将切换为手动覆盖模式，并按照指定入口/出口信号下发控制命令。`}
        confirmText="确认下发"
        busy={signalMutation.isPending}
        onCancel={() => setPendingAction(null)}
        onConfirm={() => signalMutation.mutate()}
      />

      <div className="grid gap-6 xl:grid-cols-[0.9fr_1.1fr]">
        <Panel
          title="车道列表"
          eyebrow="控制对象"
          action={
            <button
              type="button"
              disabled={readOnly || restoreMutation.isPending}
              onClick={() => restoreMutation.mutate()}
              className="inline-flex items-center gap-2 rounded-sm border border-[var(--border-soft)] px-3 py-2 text-xs text-[var(--text-secondary)] transition hover:border-[var(--border-strong)] hover:text-[var(--text-primary)] disabled:opacity-50"
            >
              <RefreshCcw className="size-3.5" />
              恢复全域自动
            </button>
          }
        >
          <div className="space-y-3">
            {lanesQuery.data.map((lane) => (
              <button
                key={lane.id}
                type="button"
                onClick={() => setSelectedLaneId(lane.id)}
                className={`w-full rounded-sm border p-4 text-left transition ${
                  selectedLane?.id === lane.id
                    ? "border-sky-500/30 bg-sky-50"
                    : "border-[var(--border-soft)] bg-white hover:border-[var(--border-strong)]"
                }`}
              >
                <div className="flex items-center justify-between gap-3">
                  <div>
                    <p className="font-semibold text-[var(--text-primary)]">{lane.name}</p>
                    <p className="mt-1 text-sm text-[var(--text-secondary)]">{lane.zone} · {lane.code}</p>
                  </div>
                  <StatusBadge value={lane.mode} kind="mode" />
                </div>
              </button>
            ))}
            {lanesQuery.data.length === 0 ? (
              <div className="rounded-sm border border-[var(--border-soft)] bg-slate-50 px-4 py-8 text-center text-sm text-[var(--text-secondary)]">
                当前数据库没有车道数据，暂时无法下发信号控制。
              </div>
            ) : null}
          </div>
        </Panel>

        {selectedLane ? (
          <Panel
            title={selectedLane.name}
            eyebrow="信号联动控制"
            action={
              readOnly ? (
                <div className="inline-flex items-center gap-2 rounded-sm border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-700">
                  <ShieldAlert className="size-3.5" />
                  当前账号仅可查看
                </div>
              ) : null
            }
          >
            <div className="grid gap-6 xl:grid-cols-[0.78fr_1.22fr]">
              <div className="rounded-sm border border-[var(--border-soft)] bg-white p-6">
                <p className="text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">当前信号态</p>
                <div className="mt-5">
                  <SignalStack entrySignal={selectedLane.entrySignal} exitSignal={selectedLane.exitSignal} />
                </div>
                <div className="mt-6 grid gap-3 text-sm text-[var(--text-secondary)]">
                  <p>车辆在场 {selectedLane.vehicleCount} / {selectedLane.capacity}</p>
                  <p>信息屏状态 {ledStatusLabel(selectedLane.ledStatus)}</p>
                  <p>传感状态 {sensorStatusLabel(selectedLane.sensorStatus)}</p>
                  <p>最近采集 {selectedLane.lastSensorAt ? formatDateTime(selectedLane.lastSensorAt) : "暂无"}</p>
                  <p>最近识别 {selectedLane.lastEntryAt ? `${selectedLane.lastEntryPlate ?? "未识别车牌"} · ${formatDateTime(selectedLane.lastEntryAt)}` : "暂无"}</p>
                  <p>当前车道状态 <span className="text-[var(--text-primary)]">{laneStatusLabel(selectedLane.status)}</span></p>
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
                    <div className="mt-4 grid gap-3 md:grid-cols-3">
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
                              mode: "MANUAL",
                              reason: `控制台手动切换${field.label}至${signal}`,
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
    </>
  );
}
