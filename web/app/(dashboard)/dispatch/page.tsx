"use client";

import { FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ConfirmModal } from "@/components/confirm-modal";
import { Panel } from "@/components/panel";
import { api } from "@/lib/api";
import { canDispatch } from "@/lib/permissions";
import type { DispatchCommandType, ManualDispatchRequest } from "@/lib/types";
import { useAuthStore } from "@/stores/auth-store";

const commandTypes: Array<{ value: DispatchCommandType; label: string }> = [
  { value: "FORCE_OPEN_GATE", label: "强制开闸" },
  { value: "MANUAL_ENTRY", label: "手动入场" },
  { value: "PLATE_CORRECTION", label: "车牌更正" },
  { value: "TEMP_ALLOW", label: "临时放行" },
  { value: "CORRECT_COUNT", label: "修正车辆数" },
  { value: "SET_PRIORITY", label: "设置优先车道" },
];

const initialForm: ManualDispatchRequest = {
  laneId: "",
  commandType: "MANUAL_ENTRY",
  plate: "",
  vehicleType: "社会车辆",
  reason: "",
  correctedVehicleCount: 0,
  markPriority: false,
};

export default function DispatchPage() {
  const role = useAuthStore((state) => state.user?.role);
  const disabled = !canDispatch(role);
  const queryClient = useQueryClient();
  const lanesQuery = useQuery({
    queryKey: ["lanes"],
    queryFn: api.getLanes,
  });
  const [form, setForm] = useState<ManualDispatchRequest>(initialForm);
  const [confirmOpen, setConfirmOpen] = useState(false);

  const dispatchMutation = useMutation({
    mutationFn: () => api.dispatch(form),
    onSuccess: async () => {
      setConfirmOpen(false);
      setForm((current) => ({ ...initialForm, laneId: current.laneId }));
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["logs"] }),
        queryClient.invalidateQueries({ queryKey: ["lanes"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
        queryClient.invalidateQueries({ queryKey: ["alerts"] }),
      ]);
    },
  });

  const lanes = lanesQuery.data ?? [];

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setConfirmOpen(true);
  }

  return (
    <>
      <ConfirmModal
        open={confirmOpen}
        title="提交人工调度指令"
        description="系统会写入人工操作记录，并根据指令类型刷新车道状态、通行记录和预警中心数据。"
        confirmText="确认提交"
        busy={dispatchMutation.isPending}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={() => dispatchMutation.mutate()}
      />

      <div className="grid gap-6 xl:grid-cols-[1.08fr_0.92fr]">
        <Panel title="手动调度与数据修正" eyebrow="人工干预能力">
          <form className="space-y-5" onSubmit={handleSubmit}>
            <div className="grid gap-4 md:grid-cols-2">
              <label className="block">
                <span className="mb-2 block text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">目标车道</span>
                <select
                  value={form.laneId}
                  disabled={disabled}
                  onChange={(event) => setForm((current) => ({ ...current, laneId: event.target.value }))}
                  className="w-full rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none focus:border-sky-400/40 disabled:opacity-60"
                  required
                >
                  <option value="">请选择车道</option>
                  {lanes.map((lane) => (
                    <option key={lane.id} value={lane.id}>
                      {lane.name}
                    </option>
                  ))}
                </select>
                {lanes.length === 0 ? (
                  <p className="mt-2 text-xs text-[var(--text-secondary)]">当前数据库没有车道记录，暂时无法提交人工调度。</p>
                ) : null}
              </label>

              <label className="block">
                <span className="mb-2 block text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">指令类型</span>
                <select
                  value={form.commandType}
                  disabled={disabled}
                  onChange={(event) =>
                    setForm((current) => ({
                      ...current,
                      commandType: event.target.value as DispatchCommandType,
                    }))
                  }
                  className="w-full rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none focus:border-sky-400/40 disabled:opacity-60"
                >
                  {commandTypes.map((command) => (
                    <option key={command.value} value={command.value}>
                      {command.label}
                    </option>
                  ))}
                </select>
              </label>
            </div>

            <div className="grid gap-4 md:grid-cols-2">
              <label className="block">
                <span className="mb-2 block text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">车牌号码</span>
                <input
                  value={form.plate ?? ""}
                  disabled={disabled}
                  onChange={(event) => setForm((current) => ({ ...current, plate: event.target.value.toUpperCase() }))}
                  className="w-full rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm font-mono text-[var(--text-primary)] outline-none placeholder:text-[var(--text-muted)] focus:border-sky-400/40 disabled:opacity-60"
                  placeholder="例: 沪A12345"
                />
              </label>

              <label className="block">
                <span className="mb-2 block text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">车辆类型</span>
                <input
                  value={form.vehicleType ?? ""}
                  disabled={disabled}
                  onChange={(event) => setForm((current) => ({ ...current, vehicleType: event.target.value }))}
                  className="w-full rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none placeholder:text-[var(--text-muted)] focus:border-sky-400/40 disabled:opacity-60"
                  placeholder="社会车辆 / 货运 / 特种车"
                />
              </label>
            </div>

            <label className="block">
              <span className="mb-2 block text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">调度原因说明</span>
              <textarea
                value={form.reason}
                disabled={disabled}
                onChange={(event) => setForm((current) => ({ ...current, reason: event.target.value }))}
                className="min-h-32 w-full rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none placeholder:text-[var(--text-muted)] focus:border-sky-400/40 disabled:opacity-60"
                placeholder="请输入详细的人工干预原因，系统会保留指令记录。"
                required
              />
            </label>

            <div className="grid gap-4 md:grid-cols-2">
              <label className="block">
                <span className="mb-2 block text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">修正车辆数</span>
                <input
                  type="number"
                  min={0}
                  value={form.correctedVehicleCount ?? 0}
                  disabled={disabled}
                  onChange={(event) =>
                    setForm((current) => ({
                      ...current,
                      correctedVehicleCount: Number(event.target.value),
                    }))
                  }
                  className="w-full rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none focus:border-sky-400/40 disabled:opacity-60"
                />
              </label>

              <label className="flex items-center gap-3 rounded-sm border border-[var(--border-soft)] bg-slate-50 px-4 py-3">
                <input
                  type="checkbox"
                  checked={form.markPriority ?? false}
                  disabled={disabled}
                  onChange={(event) => setForm((current) => ({ ...current, markPriority: event.target.checked }))}
                />
                <span className="text-sm text-[var(--text-secondary)]">设置为下一轮优先放行车道</span>
              </label>
            </div>

            <button
              type="submit"
              disabled={disabled || dispatchMutation.isPending || lanes.length === 0}
              className="w-full rounded-sm bg-[var(--brand)] px-4 py-3 text-sm font-semibold text-white transition hover:brightness-110 disabled:opacity-60"
            >
              {dispatchMutation.isPending ? "提交中..." : "提交调度请求"}
            </button>
          </form>
        </Panel>

        <Panel title="操作说明" eyebrow="二次确认机制">
          <div className="space-y-4 text-sm leading-7 text-[var(--text-secondary)]">
            <p>1. 所有人工指令在提交前都会进行二次确认，并记录执行人、目标车道、指令内容和时间。</p>
            <p>2. 修正车辆数与优先放行会立即影响自动调度逻辑，相关车道 LED 与信号灯状态会自动刷新。</p>
            <p>3. 若输入车牌命中黑名单，系统会在提交成功后立即生成高优先级预警。</p>
            <p>4. 只读账号无法进入此页面；调度员与管理员均可提交，但黑名单维护仅管理员可执行。</p>
          </div>
        </Panel>
      </div>
    </>
  );
}
