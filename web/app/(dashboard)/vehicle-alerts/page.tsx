"use client";

import { type FormEvent, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Download, Filter, Search } from "lucide-react";
import { ConfirmModal } from "@/components/confirm-modal";
import { FilterSelect } from "@/components/filter-select";
import { Panel } from "@/components/panel";
import { TablePagination } from "@/components/table-pagination";
import { api } from "@/lib/api";
import type { ScreenEventType } from "@/lib/types";
import { cn, downloadCsv, formatDateTime, formatPlateDisplay, screenEventTypeLabel } from "@/lib/utils";

const eventTypes: Array<{ value: ScreenEventType; label: string }> = [
  { value: "blacklist", label: "黑名单" },
  { value: "wrong_lane", label: "走错车道" },
  { value: "not_entered", label: "未进车道" },
  { value: "other", label: "其他" },
];

function toApiDateTime(value: string) {
  return value ? new Date(value).toISOString() : undefined;
}

function todayRange() {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  const date = `${year}-${month}-${day}`;
  return {
    from: `${date}T00:00`,
    to: `${date}T23:59`,
  };
}

function eventTypeClass(type: string) {
  if (type === "blacklist") return "border-red-200 bg-red-50 text-red-700";
  if (type === "wrong_lane") return "border-amber-200 bg-amber-50 text-amber-700";
  if (type === "not_entered") return "border-sky-200 bg-sky-50 text-sky-700";
  return "border-slate-200 bg-slate-100 text-slate-700";
}

export default function VehicleAlertsPage() {
  const queryClient = useQueryClient();
  const defaultTimeRange = todayRange();
  const [type, setType] = useState("");
  const [occurredAtFrom, setOccurredAtFrom] = useState(defaultTimeRange.from);
  const [occurredAtTo, setOccurredAtTo] = useState(defaultTimeRange.to);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [selectedAlertIds, setSelectedAlertIds] = useState<string[]>([]);
  const [batchConfirmOpen, setBatchConfirmOpen] = useState(false);
  const [filters, setFilters] = useState({
    type: "",
    occurredAtFrom: defaultTimeRange.from,
    occurredAtTo: defaultTimeRange.to,
  });

  const alertsQuery = useQuery({
    queryKey: ["screen-events", filters],
    queryFn: () =>
      api.getScreenEvents({
        type: filters.type,
        occurredAtFrom: toApiDateTime(filters.occurredAtFrom),
        occurredAtTo: toApiDateTime(filters.occurredAtTo),
        includeHandled: "true",
      }),
    refetchInterval: 5000,
  });

  const alerts = useMemo(() => alertsQuery.data ?? [], [alertsQuery.data]);
  const selectedAlertSet = useMemo(() => new Set(selectedAlertIds), [selectedAlertIds]);
  const unhandledAlertIds = useMemo(() => new Set(alerts.filter((alert) => !alert.handled).map((alert) => alert.id)), [alerts]);
  const selectedUnhandledAlertIds = selectedAlertIds.filter((id) => unhandledAlertIds.has(id));
  const pageCount = Math.max(1, Math.ceil(alerts.length / pageSize));
  const currentPage = Math.min(page, pageCount);
  const pageStartIndex = (currentPage - 1) * pageSize;
  const pagedAlerts = alerts.slice((currentPage - 1) * pageSize, currentPage * pageSize);
  const pagedUnhandledAlertIds = pagedAlerts.filter((alert) => !alert.handled).map((alert) => alert.id);
  const currentPageAllSelected = pagedUnhandledAlertIds.length > 0 && pagedUnhandledAlertIds.every((id) => selectedAlertSet.has(id));

  const handleAlertMutation = useMutation({
    mutationFn: (id: string) => api.handleScreenEvent(id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["screen-events"] });
    },
  });

  const batchHandleMutation = useMutation({
    mutationFn: (ids: string[]) => api.handleScreenEvents(ids),
    onSuccess: async () => {
      setSelectedAlertIds([]);
      setBatchConfirmOpen(false);
      await queryClient.invalidateQueries({ queryKey: ["screen-events"] });
    },
  });

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPage(1);
    setSelectedAlertIds([]);
    setFilters({ type, occurredAtFrom, occurredAtTo });
  }

  function toggleAlertSelection(alertId: string) {
    setSelectedAlertIds((current) => (current.includes(alertId) ? current.filter((id) => id !== alertId) : [...current, alertId]));
  }

  function toggleCurrentPageSelection() {
    if (currentPageAllSelected) {
      setSelectedAlertIds((current) => current.filter((id) => !pagedUnhandledAlertIds.includes(id)));
      return;
    }
    setSelectedAlertIds((current) => Array.from(new Set([...current, ...pagedUnhandledAlertIds])));
  }

  return (
    <div className="space-y-5">
      <ConfirmModal
        open={batchConfirmOpen}
        title="批量处理告警"
        description={`确认将已选择的 ${selectedUnhandledAlertIds.length} 条告警标记为已处理？处理后将从大屏左侧告警框中移除。`}
        confirmText="确认处理"
        busy={batchHandleMutation.isPending}
        onConfirm={() => batchHandleMutation.mutate(selectedUnhandledAlertIds)}
        onCancel={() => setBatchConfirmOpen(false)}
      />
      <Panel
        title="车辆告警"
        eyebrow="大屏左侧告警统一列表"
        action={
          <div className="flex flex-wrap items-center justify-end gap-2">
            <button
              type="button"
              disabled={selectedUnhandledAlertIds.length === 0 || batchHandleMutation.isPending}
              onClick={() => setBatchConfirmOpen(true)}
              className="inline-flex items-center gap-2 rounded-sm bg-slate-950 px-3 py-2 text-xs font-semibold text-white transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-60"
            >
              <CheckCircle2 className="size-3.5" />
              批量处理
            </button>
            <button
              type="button"
              onClick={() =>
                downloadCsv(
                  "vehicle-alerts.csv",
                  [
                    ["序号", "类型", "车牌号码", "处理状态", "告警内容", "发生时间", "处理时间", "来源ID", "来源名称"],
                    ...alerts.map((alert, index) => [
                      String(index + 1),
                      screenEventTypeLabel(alert.type),
                      alert.plate,
                      alert.handled ? "已处理" : "未处理",
                      alert.message,
                      alert.occurredAt,
                      alert.handledAt ?? "",
                      alert.sourceId ?? "",
                      alert.sourceName ?? "",
                    ]),
                  ],
                )
              }
              className="inline-flex items-center gap-2 rounded-sm border border-[var(--border-soft)] px-3 py-2 text-xs text-[var(--text-secondary)] transition hover:border-[var(--border-strong)] hover:text-[var(--text-primary)]"
            >
              <Download className="size-3.5" />
              导出表格
            </button>
          </div>
        }
      >
        <form onSubmit={handleSearch} className="grid gap-4 md:grid-cols-[180px_220px_220px_120px_minmax(0,1fr)]">
          <FilterSelect
            value={type}
            onChange={setType}
            icon={Filter}
            options={[
              { value: "", label: "全部类型" },
              ...eventTypes.map((item) => ({ value: item.value, label: item.label })),
            ]}
          />

          <input
            type="datetime-local"
            value={occurredAtFrom}
            max={occurredAtTo || undefined}
            onChange={(event) => setOccurredAtFrom(event.target.value)}
            className="rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none focus:border-sky-400/40"
          />

          <input
            type="datetime-local"
            value={occurredAtTo}
            min={occurredAtFrom || undefined}
            onChange={(event) => setOccurredAtTo(event.target.value)}
            className="rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none focus:border-sky-400/40"
          />

          <button
            type="submit"
            className="inline-flex items-center justify-center gap-2 rounded-sm bg-blue-600 px-4 py-3 text-sm font-semibold text-white shadow-sm transition hover:bg-blue-500"
          >
            <Search className="size-4" />
            查询
          </button>

          <div className="flex items-center justify-end text-sm text-[var(--text-secondary)]">
            当前共 {alerts.length} 条告警，已选择 {selectedUnhandledAlertIds.length} 条
          </div>
        </form>

        <div className="mt-5 overflow-hidden rounded-sm border border-[var(--border-soft)]">
          <div className="grid grid-cols-[42px_0.45fr_0.7fr_0.8fr_0.75fr_minmax(0,1.45fr)_1fr_0.8fr_0.65fr] gap-3 bg-slate-100 px-5 py-4 text-[12px] font-bold uppercase tracking-[0.18em] text-slate-600">
            <span>
              <input
                type="checkbox"
                checked={currentPageAllSelected}
                disabled={pagedUnhandledAlertIds.length === 0}
                onChange={toggleCurrentPageSelection}
                className="size-4 rounded-sm border-slate-300 text-blue-600"
                aria-label="选择当前页未处理告警"
              />
            </span>
            <span>序号</span>
            <span>类型</span>
            <span>车牌号码</span>
            <span>处理状态</span>
            <span>告警内容</span>
            <span>发生时间</span>
            <span>来源</span>
            <span>操作</span>
          </div>
          <div className="divide-y divide-[var(--border-soft)]">
            {pagedAlerts.map((alert, index) => (
              <div key={alert.id} className="grid grid-cols-[42px_0.45fr_0.7fr_0.8fr_0.75fr_minmax(0,1.45fr)_1fr_0.8fr_0.65fr] gap-3 px-5 py-4 text-sm">
                <span>
                  <input
                    type="checkbox"
                    checked={selectedAlertSet.has(alert.id)}
                    disabled={alert.handled}
                    onChange={() => toggleAlertSelection(alert.id)}
                    className="size-4 rounded-sm border-slate-300 text-blue-600 disabled:opacity-40"
                    aria-label={`选择告警 ${alert.id}`}
                  />
                </span>
                <span className="font-mono text-[var(--text-secondary)]">{pageStartIndex + index + 1}</span>
                <span>
                  <span className={cn("inline-flex rounded-sm border px-2 py-1 text-xs font-semibold", eventTypeClass(alert.type))}>
                    {screenEventTypeLabel(alert.type)}
                  </span>
                </span>
                <span className="font-mono font-semibold text-[var(--text-primary)]">{formatPlateDisplay(alert.plate) || alert.plate}</span>
                <span>
                  <span
                    className={cn(
                      "inline-flex rounded-sm border px-2 py-1 text-xs font-semibold",
                      alert.handled ? "border-emerald-200 bg-emerald-50 text-emerald-700" : "border-amber-200 bg-amber-50 text-amber-700",
                    )}
                    title={alert.handledAt ? `处理时间：${formatDateTime(alert.handledAt)}` : undefined}
                  >
                    {alert.handled ? "已处理" : "未处理"}
                  </span>
                </span>
                <span className="text-[var(--text-primary)]">{alert.message}</span>
                <span className="text-[var(--text-secondary)]">{formatDateTime(alert.occurredAt)}</span>
                <span className="text-[var(--text-secondary)]">{alert.sourceName ?? alert.sourceId ?? "-"}</span>
                <span>
                  {alert.handled ? (
                    <span className="text-[var(--text-muted)]">-</span>
                  ) : (
                    <button
                      type="button"
                      disabled={handleAlertMutation.isPending}
                      onClick={() => handleAlertMutation.mutate(alert.id)}
                      className="rounded-sm border border-blue-200 bg-blue-50 px-3 py-1.5 text-xs font-semibold text-blue-700 transition hover:border-blue-300 hover:bg-blue-100 disabled:opacity-60"
                    >
                      处理
                    </button>
                  )}
                </span>
              </div>
            ))}
            {alertsQuery.isLoading ? (
              <div className="px-5 py-10 text-center text-sm text-[var(--text-secondary)]">正在加载车辆告警...</div>
            ) : null}
            {!alertsQuery.isLoading && alerts.length === 0 ? (
              <div className="px-5 py-10 text-center text-sm text-[var(--text-secondary)]">当前筛选条件下没有车辆告警。</div>
            ) : null}
          </div>
          <TablePagination
            page={currentPage}
            pageSize={pageSize}
            total={alerts.length}
            onPageChange={setPage}
            onPageSizeChange={(nextPageSize) => {
              setPageSize(nextPageSize);
              setPage(1);
            }}
          />
        </div>
      </Panel>
    </div>
  );
}
