"use client";

import { FormEvent, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, GripVertical, RotateCcw, Save } from "lucide-react";
import { Panel } from "@/components/panel";
import { api } from "@/lib/api";
import { canDispatch } from "@/lib/permissions";
import type { DispatchConfig, LaneSnapshot } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useAuthStore } from "@/stores/auth-store";

const emptyLanes: LaneSnapshot[] = [];
const initialDispatchConfig: DispatchConfig = {
  entryLaneOrder: "1-11",
  entryDispatchEnabled: false,
  exitDispatchEnabled: false,
  activeEntryLaneId: null,
  activeExitLaneId: null,
  assignmentReserveMinutes: 2,
};

type DispatchOrderKey = "entryLaneOrder";
type OrderDraft = Partial<Record<DispatchOrderKey, string[]>>;
type DraggingLane = { orderKey: DispatchOrderKey; laneId: string } | null;
type SuccessNotice = { title: string; description: string } | null;

export default function DispatchPage() {
  const role = useAuthStore((state) => state.user?.role);
  const disabled = !canDispatch(role);
  const queryClient = useQueryClient();
  const lanesQuery = useQuery({
    queryKey: ["lanes"],
    queryFn: api.getLanes,
  });
  const dispatchConfigQuery = useQuery({
    queryKey: ["dispatch-config"],
    queryFn: api.getDispatchConfig,
  });
  const [configDraft, setConfigDraft] = useState<OrderDraft>({});
  const [reserveMinutesDraftOverride, setReserveMinutesDraftOverride] = useState<string | null>(null);
  const [capacityDraft, setCapacityDraft] = useState<Record<string, number>>({});
  const [selectedLaneIds, setSelectedLaneIds] = useState<string[]>([]);
  const [bulkCapacityValue, setBulkCapacityValue] = useState<string>("");
  const [bulkCapacityEditorOpen, setBulkCapacityEditorOpen] = useState(false);
  const [draggingLane, setDraggingLane] = useState<DraggingLane>(null);
  const [successNotice, setSuccessNotice] = useState<SuccessNotice>(null);

  const lanes = lanesQuery.data ?? emptyLanes;
  const currentConfig = dispatchConfigQuery.data ?? initialDispatchConfig;
  const entryOrder = configDraft.entryLaneOrder ?? resolveLaneOrder(currentConfig.entryLaneOrder, lanes);
  const selectedLaneSet = useMemo(() => new Set(selectedLaneIds), [selectedLaneIds]);
  const allLaneIds = useMemo(() => lanes.map((lane) => lane.id), [lanes]);
  const allSelected = lanes.length > 0 && selectedLaneIds.length === lanes.length;
  const selectedLaneNames = useMemo(
    () => lanes.filter((lane) => selectedLaneSet.has(lane.id)).map((lane) => lane.name),
    [lanes, selectedLaneSet],
  );
  const reserveMinutesDraft = reserveMinutesDraftOverride ?? String(currentConfig.assignmentReserveMinutes ?? 2);
  const parsedReserveMinutes = Number(reserveMinutesDraft);
  const reserveMinutesValid = Number.isInteger(parsedReserveMinutes) && parsedReserveMinutes >= 1 && parsedReserveMinutes <= 60;
  const configForm: DispatchConfig = {
    entryLaneOrder: buildOrderConfig(entryOrder, lanes),
    entryDispatchEnabled: currentConfig.entryDispatchEnabled,
    exitDispatchEnabled: currentConfig.exitDispatchEnabled,
    assignmentReserveMinutes: reserveMinutesValid ? parsedReserveMinutes : currentConfig.assignmentReserveMinutes,
  };
  const changedCapacityEntries = useMemo(
    () =>
      lanes.flatMap((lane) => {
        const draftCapacity = capacityDraft[lane.id];
        if (draftCapacity === undefined || draftCapacity === lane.capacity) {
          return [];
        }
        return [
          {
            laneId: lane.id,
            capacity: draftCapacity,
            valid: Number.isInteger(draftCapacity) && draftCapacity >= 1,
          },
        ];
      }),
    [capacityDraft, lanes],
  );
  const invalidCapacityEntries = changedCapacityEntries.filter((entry) => !entry.valid);

  async function saveLaneCapacities(entries: Array<{ laneId: string; capacity: number }>) {
    const updatedLanes: LaneSnapshot[] = [];
    for (const entry of entries) {
      updatedLanes.push(await api.updateLaneCapacity(entry.laneId, entry.capacity));
    }
    return updatedLanes;
  }

  function clearSavedCapacityDraft(updatedLanes: LaneSnapshot[]) {
    setCapacityDraft((current) => {
      const next = { ...current };
      updatedLanes.forEach((lane) => {
        delete next[lane.id];
      });
      return next;
    });
  }

  function clearBulkCapacityEditor() {
    setSelectedLaneIds([]);
    setBulkCapacityEditorOpen(false);
    setBulkCapacityValue("");
  }

  async function handleSettingsSaveSuccess(nextConfig: DispatchConfig, updatedLanes: LaneSnapshot[]) {
    setConfigDraft({});
    setReserveMinutesDraftOverride(null);
    clearSavedCapacityDraft(updatedLanes);
    queryClient.setQueryData(["dispatch-config"], nextConfig);
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["dispatch-config"] }),
      queryClient.invalidateQueries({ queryKey: ["dispatch-board"] }),
      queryClient.invalidateQueries({ queryKey: ["lanes"] }),
      queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
    ]);
  }

  const configMutation = useMutation({
    mutationFn: async () => {
      const nextConfig = await api.updateDispatchConfig(configForm);
      const capacityEntries = changedCapacityEntries
        .filter((entry) => entry.valid)
        .map((entry) => ({ laneId: entry.laneId, capacity: entry.capacity }));
      const updatedLanes = capacityEntries.length > 0 ? await saveLaneCapacities(capacityEntries) : [];
      return { nextConfig, updatedLanes };
    },
    onSuccess: async ({ nextConfig, updatedLanes }) => {
      await handleSettingsSaveSuccess(nextConfig, updatedLanes);
      setSuccessNotice({
        title: "保存成功",
        description:
          updatedLanes.length > 0
            ? `入口开放顺序、未进车道判定时间和 ${updatedLanes.length} 条车道容量已更新。`
            : "入口开放顺序和未进车道判定时间已更新。",
      });
    },
  });
  const dailyResetMutation = useMutation({
    mutationFn: api.dailyReset,
    onSuccess: async (nextConfig) => {
      queryClient.setQueryData(["dispatch-config"], nextConfig);
      setReserveMinutesDraftOverride(null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["dispatch-config"] }),
        queryClient.invalidateQueries({ queryKey: ["dispatch-board"] }),
        queryClient.invalidateQueries({ queryKey: ["lanes"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
        queryClient.invalidateQueries({ queryKey: ["logs"] }),
        queryClient.invalidateQueries({ queryKey: ["screen-events"] }),
      ]);
      setSuccessNotice({
        title: "日清完成",
        description: "上一班次数据已清空，首条入口车道已重新打开，出口放行等待切换。",
      });
    },
  });

  const capacityMutationPending = configMutation.isPending;

  function handleConfigSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!reserveMinutesValid || invalidCapacityEntries.length > 0) {
      return;
    }
    configMutation.mutate();
  }

  function updateLaneOrder(orderKey: DispatchOrderKey, updater: (currentOrder: string[]) => string[]) {
    setConfigDraft((current) => {
      const currentOrder = current[orderKey] ?? entryOrder;
      return {
        ...current,
        [orderKey]: updater(currentOrder),
      };
    });
  }

  function handleDropLane(orderKey: DispatchOrderKey, targetLaneId: string) {
    if (!draggingLane || draggingLane.orderKey !== orderKey) {
      return;
    }
    updateLaneOrder(orderKey, (currentOrder) => reorderLane(currentOrder, draggingLane.laneId, targetLaneId));
    setDraggingLane(null);
  }

  function toggleLaneSelection(laneId: string) {
    setSelectedLaneIds((current) => (current.includes(laneId) ? current.filter((id) => id !== laneId) : [...current, laneId]));
  }

  function openBulkCapacityEditor() {
    if (disabled || selectedLaneIds.length === 0 || capacityMutationPending) {
      return;
    }
    setBulkCapacityValue("");
    setBulkCapacityEditorOpen(true);
  }

  function closeBulkCapacityEditor() {
    if (configMutation.isPending) {
      return;
    }
    setBulkCapacityEditorOpen(false);
    setBulkCapacityValue("");
  }

  function handleBulkCapacitySubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const parsedCapacity = Number(bulkCapacityValue);
    if (!Number.isInteger(parsedCapacity) || parsedCapacity < 1 || selectedLaneIds.length === 0) {
      return;
    }
    setCapacityDraft((current) => {
      const next = { ...current };
      selectedLaneIds.forEach((laneId) => {
        next[laneId] = parsedCapacity;
      });
      return next;
    });
    clearBulkCapacityEditor();
  }

  return (
    <div className="space-y-5">
      {successNotice ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/35 px-4">
          <div className="w-full max-w-sm rounded-2xl bg-white p-5 shadow-2xl">
            <h2 className="text-base font-semibold text-slate-900">{successNotice.title}</h2>
            <p className="mt-2 text-sm leading-6 text-slate-500">{successNotice.description}</p>
            <div className="mt-5 flex justify-end">
              <button
                type="button"
                onClick={() => setSuccessNotice(null)}
                className="rounded-xl bg-slate-950 px-4 py-2 text-sm font-semibold text-white transition hover:brightness-110"
              >
                确认
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {bulkCapacityEditorOpen ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/35 px-4">
          <form className="w-full max-w-sm rounded-2xl bg-white p-5 shadow-2xl" onSubmit={handleBulkCapacitySubmit}>
            <h2 className="text-base font-semibold text-slate-900">批量修改车道容量</h2>
            <p className="mt-1 text-sm text-slate-500">
              已选 {selectedLaneIds.length} 条车道
              {selectedLaneNames.length > 0 ? `：${selectedLaneNames.join("、")}` : ""}
            </p>
            <label className="mt-4 block">
              <span className="mb-2 block text-xs font-medium text-slate-500">车道容量</span>
              <input
                type="number"
                min={1}
                step={1}
                value={bulkCapacityValue}
                onChange={(event) => setBulkCapacityValue(event.target.value)}
                className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm text-slate-900 outline-none focus:border-blue-400"
                autoFocus
              />
            </label>
            <div className="mt-5 grid grid-cols-2 gap-3">
              <button
                type="button"
                disabled={configMutation.isPending}
                onClick={closeBulkCapacityEditor}
                className="rounded-xl border border-slate-200 px-3 py-2 text-sm font-semibold text-slate-600 transition hover:bg-slate-50 disabled:opacity-60"
              >
                取消
              </button>
              <button
                type="submit"
                disabled={configMutation.isPending || !Number.isInteger(Number(bulkCapacityValue)) || Number(bulkCapacityValue) < 1}
                className="rounded-xl bg-slate-950 px-3 py-2 text-sm font-semibold text-white transition hover:brightness-110 disabled:opacity-60"
              >
                应用到列表
              </button>
            </div>
          </form>
        </div>
      ) : null}

      <section className="panel-surface rounded-2xl p-6">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <p className="text-sm font-medium text-[var(--text-muted)]">调度设置</p>
            <h1 className="mt-1 text-2xl font-semibold tracking-tight text-[var(--text-primary)]">日清、入口顺序与车道容量</h1>
          </div>
          <button
            type="button"
            disabled={disabled || dailyResetMutation.isPending}
            onClick={() => dailyResetMutation.mutate()}
            className="inline-flex items-center justify-center gap-2 rounded-2xl bg-slate-950 px-4 py-3 text-sm font-semibold text-white transition hover:brightness-110 disabled:opacity-60"
          >
            <RotateCcw className="size-4" />
            {dailyResetMutation.isPending ? "日清中..." : "清空车道并重启"}
          </button>
        </div>
      </section>

      <form id="entry-order-form" className="grid items-start gap-5 xl:grid-cols-[360px_minmax(0,1fr)]" onSubmit={handleConfigSubmit}>
        <Panel title="未进车道判定时间" eyebrow="告警规则" className="h-full">
          <div className="space-y-4">
            <p className="text-sm leading-6 text-[var(--text-secondary)]">
              总入口抓拍并分配车道后，超过该时间仍未被任何车道入口摄像头识别，即生成未进车道告警。
            </p>
            <label className="block">
              <span className="mb-2 block text-xs font-medium text-[var(--text-muted)]">判定时间</span>
              <span className="relative block">
                <input
                  type="number"
                  min={1}
                  max={60}
                  step={1}
                  value={reserveMinutesDraft}
                  disabled={disabled || dispatchConfigQuery.isLoading || configMutation.isPending}
                  onChange={(event) => setReserveMinutesDraftOverride(event.target.value)}
                  className="h-12 w-full rounded-xl border border-[var(--border-soft)] bg-white px-3 pr-12 text-center text-sm font-semibold tabular-nums text-[var(--text-primary)] outline-none focus:border-sky-400/40 disabled:opacity-60"
                />
                <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-xs text-[var(--text-secondary)]">分钟</span>
              </span>
            </label>
            {!reserveMinutesValid ? <p className="text-xs text-rose-600">请输入 1 到 60 之间的整数分钟数。</p> : null}
          </div>
        </Panel>

        <Panel
          title="入口开放顺序与车道容量"
          eyebrow="统一设置"
          className="flex h-full flex-col"
          action={
            <div className="flex flex-wrap items-center justify-end gap-2">
              <button
                type="button"
                disabled={disabled || selectedLaneIds.length === 0 || capacityMutationPending}
                onClick={openBulkCapacityEditor}
                className="inline-flex items-center gap-2 rounded-xl border border-[var(--border-soft)] bg-white px-3 py-2 text-xs font-semibold text-[var(--text-secondary)] transition hover:border-[var(--border-strong)] hover:text-[var(--text-primary)] disabled:opacity-60"
              >
                批量填写容量
              </button>
              <button
                type="submit"
                form="entry-order-form"
                disabled={
                  disabled ||
                  configMutation.isPending ||
                  dispatchConfigQuery.isLoading ||
                  lanes.length === 0 ||
                  !reserveMinutesValid ||
                  invalidCapacityEntries.length > 0
                }
                className="inline-flex items-center justify-center gap-2 rounded-xl bg-slate-950 px-4 py-3 text-sm font-semibold text-white transition hover:brightness-110 disabled:opacity-60"
              >
                <Save className="size-4" />
                {configMutation.isPending ? "保存中..." : "保存设置"}
              </button>
            </div>
          }
        >
          <div className="flex min-h-0 flex-1 flex-col space-y-4">
            <LaneOrderCapacityEditor
              orderKey="entryLaneOrder"
              lanes={lanes}
              order={entryOrder}
              disabled={disabled || dispatchConfigQuery.isLoading || configMutation.isPending}
              draggingLane={draggingLane}
              generatedValue={configForm.entryLaneOrder}
              selectedLaneSet={selectedLaneSet}
              allSelected={allSelected}
              capacityDraft={capacityDraft}
              capacityMutationPending={capacityMutationPending}
              onDragStart={(orderKey, laneId) => setDraggingLane({ orderKey, laneId })}
              onDragEnd={() => setDraggingLane(null)}
              onDropLane={handleDropLane}
              onMoveLane={(laneId, direction) =>
                updateLaneOrder("entryLaneOrder", (currentOrder) => moveLaneByDirection(currentOrder, laneId, direction))
              }
              onToggleLaneSelection={toggleLaneSelection}
              onToggleAllLaneSelection={() => setSelectedLaneIds(allSelected ? [] : allLaneIds)}
              onCapacityChange={(laneId, capacity) =>
                setCapacityDraft((current) => ({
                  ...current,
                  [laneId]: capacity,
                }))
              }
            />

            {invalidCapacityEntries.length > 0 && changedCapacityEntries.length > 0 ? (
              <p className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
                有未保存的容量输入无效，先修正后再提交。
              </p>
            ) : null}

            {configMutation.isError ? (
              <p className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
                {configMutation.error instanceof Error ? configMutation.error.message : "设置保存失败"}
              </p>
            ) : null}
          </div>
        </Panel>
      </form>
    </div>
  );
}

function LaneOrderCapacityEditor({
  orderKey,
  lanes,
  order,
  disabled,
  draggingLane,
  generatedValue,
  selectedLaneSet,
  allSelected,
  capacityDraft,
  capacityMutationPending,
  onDragStart,
  onDragEnd,
  onDropLane,
  onMoveLane,
  onToggleLaneSelection,
  onToggleAllLaneSelection,
  onCapacityChange,
}: {
  orderKey: DispatchOrderKey;
  lanes: LaneSnapshot[];
  order: string[];
  disabled: boolean;
  draggingLane: DraggingLane;
  generatedValue: string;
  selectedLaneSet: Set<string>;
  allSelected: boolean;
  capacityDraft: Record<string, number>;
  capacityMutationPending: boolean;
  onDragStart: (orderKey: DispatchOrderKey, laneId: string) => void;
  onDragEnd: () => void;
  onDropLane: (orderKey: DispatchOrderKey, laneId: string) => void;
  onMoveLane: (laneId: string, direction: -1 | 1) => void;
  onToggleLaneSelection: (laneId: string) => void;
  onToggleAllLaneSelection: () => void;
  onCapacityChange: (laneId: string, capacity: number) => void;
}) {
  const lanesById = new Map(lanes.map((lane) => [lane.id, lane]));
  const orderedLanes = order.map((laneId) => lanesById.get(laneId)).filter((lane): lane is LaneSnapshot => Boolean(lane));

  return (
    <section className="flex min-h-0 flex-1 flex-col rounded-2xl border border-[var(--border-soft)] bg-slate-50/70 p-4">
      <div className="mb-3 flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="text-xs font-medium text-[var(--text-muted)]">车道选择</p>
          <div className="mt-2 flex flex-wrap items-center gap-2">
            <button
              type="button"
              disabled={disabled || lanes.length === 0 || capacityMutationPending}
              onClick={onToggleAllLaneSelection}
              className="inline-flex items-center gap-2 rounded-xl border border-[var(--border-soft)] bg-white px-3 py-2 text-xs font-semibold text-[var(--text-secondary)] transition hover:border-[var(--border-strong)] hover:text-[var(--text-primary)] disabled:opacity-60"
            >
              {allSelected ? "取消全选" : "全选车道"}
            </button>
          </div>
        </div>
        <div className="ml-auto max-w-xl rounded-xl border border-[var(--border-soft)] bg-white/70 px-4 py-3 text-right">
          <p className="text-xs font-medium text-[var(--text-muted)]">入口开放顺序</p>
          <p className="mt-1 break-all font-mono text-xs leading-5 text-[var(--text-secondary)]">{generatedValue || "未生成"}</p>
        </div>
        <GripVertical className="mt-6 size-5 shrink-0 text-[var(--text-muted)]" />
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto pr-1">
        <div className="space-y-2">
          {orderedLanes.map((lane, index) => {
            const dragging = draggingLane?.orderKey === orderKey && draggingLane.laneId === lane.id;
            const draftCapacity = capacityDraft[lane.id] ?? lane.capacity;
            const changed = draftCapacity !== lane.capacity;
            const invalid = !Number.isInteger(draftCapacity) || draftCapacity < 1;
            return (
              <div
                key={lane.id}
                draggable={!disabled}
                onDragStart={(event) => {
                  event.dataTransfer.effectAllowed = "move";
                  event.dataTransfer.setData("text/plain", lane.id);
                  onDragStart(orderKey, lane.id);
                }}
                onDragEnd={onDragEnd}
                onDragOver={(event) => {
                  if (!disabled) {
                    event.preventDefault();
                    event.dataTransfer.dropEffect = "move";
                  }
                }}
                onDrop={(event) => {
                  event.preventDefault();
                  onDropLane(orderKey, lane.id);
                }}
                className={cn(
                  "grid items-center gap-3 rounded-2xl border bg-white px-3 py-3 transition lg:grid-cols-[auto_76px_minmax(0,1fr)_150px_auto]",
                  dragging ? "border-sky-400 opacity-60" : changed ? "border-slate-300" : "border-[var(--border-soft)]",
                  invalid ? "border-rose-300 bg-rose-50/60" : "",
                  selectedLaneSet.has(lane.id) ? "ring-2 ring-sky-100" : "",
                  disabled ? "opacity-60" : "cursor-grab active:cursor-grabbing",
                )}
              >
                <label className="flex items-center justify-center">
                  <input
                    type="checkbox"
                    checked={selectedLaneSet.has(lane.id)}
                    onChange={() => onToggleLaneSelection(lane.id)}
                    disabled={disabled || capacityMutationPending}
                    aria-label={`选择${lane.name}`}
                    className="size-4 rounded border-slate-300 text-slate-900 focus:ring-sky-200"
                  />
                </label>

                <div className="flex h-12 flex-col items-center justify-center rounded-xl border border-slate-200 bg-slate-50 text-center">
                  <span className="text-[10px] font-medium text-slate-500">顺位</span>
                  <span className="mt-1 font-mono text-sm font-bold text-slate-900">{String(index + 1).padStart(2, "0")}</span>
                </div>

                <div className="min-w-0">
                  <p className="truncate text-sm font-semibold text-[var(--text-primary)]">{lane.name}</p>
                  <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-[var(--text-secondary)]">
                    <span className="font-mono">{lane.code}</span>
                    <span>当前容量 {lane.capacity}</span>
                    {changed ? <span className="font-semibold text-sky-700">待保存</span> : null}
                  </div>
                </div>

                <div className="space-y-2">
                  <input
                    type="number"
                    min={1}
                    value={Number.isFinite(draftCapacity) ? draftCapacity : ""}
                    aria-label={`${lane.name}可停车辆总数`}
                    placeholder="容量"
                    disabled={disabled || capacityMutationPending}
                    onChange={(event) => onCapacityChange(lane.id, Number(event.target.value))}
                    className="h-11 w-full rounded-xl border border-[var(--border-soft)] bg-white px-3 text-center text-sm font-medium tabular-nums text-[var(--text-primary)] outline-none focus:border-sky-400/40 disabled:opacity-60"
                  />
                  {invalid ? <p className="text-xs text-rose-600">请输入大于 0 的整数</p> : null}
                </div>

                <div className="flex items-center gap-1">
                  <button
                    type="button"
                    disabled={disabled || index === 0}
                    onClick={() => onMoveLane(lane.id, -1)}
                    className="inline-flex size-8 items-center justify-center rounded-xl border border-[var(--border-soft)] text-[var(--text-secondary)] transition hover:border-[var(--border-strong)] hover:text-[var(--text-primary)] disabled:opacity-40"
                    aria-label={`${lane.name} 上移`}
                  >
                    <ArrowUp className="size-3.5" />
                  </button>
                  <button
                    type="button"
                    disabled={disabled || index === orderedLanes.length - 1}
                    onClick={() => onMoveLane(lane.id, 1)}
                    className="inline-flex size-8 items-center justify-center rounded-xl border border-[var(--border-soft)] text-[var(--text-secondary)] transition hover:border-[var(--border-strong)] hover:text-[var(--text-primary)] disabled:opacity-40"
                    aria-label={`${lane.name} 下移`}
                  >
                    <ArrowDown className="size-3.5" />
                  </button>
                  <GripVertical className="size-4 text-[var(--text-muted)]" />
                </div>
              </div>
            );
          })}

          {orderedLanes.length === 0 ? (
            <div className="rounded-xl border border-[var(--border-soft)] bg-white px-4 py-8 text-center text-sm text-[var(--text-secondary)]">
              当前没有车道数据
            </div>
          ) : null}
        </div>
      </div>
    </section>
  );
}

function resolveLaneOrder(configValue: string, lanes: LaneSnapshot[]) {
  if (lanes.length === 0) {
    return [];
  }
  const resolved: string[] = [];
  const used = new Set<string>();

  for (const token of parseLaneOrder(configValue)) {
    const lane = lanes.find((candidate) => laneMatchesToken(candidate, token));
    if (lane && !used.has(lane.id)) {
      resolved.push(lane.id);
      used.add(lane.id);
    }
  }

  for (const lane of lanes) {
    if (!used.has(lane.id)) {
      resolved.push(lane.id);
    }
  }

  return resolved;
}

function parseLaneOrder(configValue: string) {
  const trimmed = configValue.trim();
  const rangeMatch = trimmed.match(/^(\d+)\s*-\s*(\d+)$/);
  if (rangeMatch) {
    const start = Number(rangeMatch[1]);
    const end = Number(rangeMatch[2]);
    const step = start <= end ? 1 : -1;
    const tokens: string[] = [];
    for (let value = start; value !== end + step; value += step) {
      tokens.push(String(value));
    }
    return tokens;
  }
  return trimmed
    .split(/[,，;；\s]+/)
    .map(normalizeOrderToken)
    .filter(Boolean);
}

function laneMatchesToken(lane: LaneSnapshot, token: string) {
  const normalizedToken = normalizeOrderToken(token);
  if (
    normalizedToken === normalizeOrderToken(lane.id) ||
    normalizedToken === normalizeOrderToken(lane.code) ||
    normalizedToken === normalizeOrderToken(lane.name)
  ) {
    return true;
  }
  if (/^\d+$/.test(normalizedToken)) {
    return laneNaturalNumber(lane) === Number(normalizedToken);
  }
  return false;
}

function laneNaturalNumber(lane: LaneSnapshot) {
  const source = `${lane.code} ${lane.name} ${lane.id}`;
  const match = source.match(/\d+/);
  return match ? Number(match[0]) : null;
}

function normalizeOrderToken(value: string) {
  return value.replaceAll(/\s+/g, "").toUpperCase();
}

function buildOrderConfig(order: string[], lanes: LaneSnapshot[]) {
  const lanesById = new Map(lanes.map((lane) => [lane.id, lane]));
  return order.map((laneId) => lanesById.get(laneId)?.code ?? laneId).join(",");
}

function reorderLane(order: string[], sourceLaneId: string, targetLaneId: string) {
  if (sourceLaneId === targetLaneId) {
    return order;
  }
  const nextOrder = [...order];
  const sourceIndex = nextOrder.indexOf(sourceLaneId);
  const targetIndex = nextOrder.indexOf(targetLaneId);
  if (sourceIndex < 0 || targetIndex < 0) {
    return order;
  }
  const [source] = nextOrder.splice(sourceIndex, 1);
  nextOrder.splice(targetIndex, 0, source);
  return nextOrder;
}

function moveLaneByDirection(order: string[], laneId: string, direction: -1 | 1) {
  const index = order.indexOf(laneId);
  const targetIndex = index + direction;
  if (index < 0 || targetIndex < 0 || targetIndex >= order.length) {
    return order;
  }
  const nextOrder = [...order];
  [nextOrder[index], nextOrder[targetIndex]] = [nextOrder[targetIndex], nextOrder[index]];
  return nextOrder;
}
