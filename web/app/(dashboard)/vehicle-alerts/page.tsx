"use client";

import { type FormEvent, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Download, Filter, Search } from "lucide-react";
import { ConfirmModal } from "@/components/confirm-modal";
import { FilterSelect } from "@/components/filter-select";
import { Panel } from "@/components/panel";
import { TablePagination } from "@/components/table-pagination";
import { api } from "@/lib/api";
import type { PageResult, ScreenEvent, ScreenEventType } from "@/lib/types";
import { cn, downloadCsv, formatDateTime, formatPlateDisplay, screenEventTypeLabel } from "@/lib/utils";

const eventTypes: Array<{ value: ScreenEventType; label: string }> = [
  { value: "blacklist", label: "黑名单" },
  { value: "not_whitelisted", label: "非白名单" },
  { value: "wrong_lane", label: "走错车道" },
  { value: "not_entered", label: "未进车道" },
  { value: "other", label: "其他" },
];

type AlertHandledStatus = "" | "handled" | "unhandled";

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
  if (type === "not_whitelisted") return "border-orange-200 bg-orange-50 text-orange-700";
  if (type === "wrong_lane") return "border-amber-200 bg-amber-50 text-amber-700";
  if (type === "not_entered") return "border-sky-200 bg-sky-50 text-sky-700";
  return "border-slate-200 bg-slate-100 text-slate-700";
}

function normalizePlateQuery(value: string) {
  return value.replace(/[·\s]/g, "").toUpperCase();
}

export default function VehicleAlertsPage() {
  const queryClient = useQueryClient();
  const defaultTimeRange = todayRange();
  const [query, setQuery] = useState("");
  const [type, setType] = useState("");
  const [handledStatus, setHandledStatus] = useState<AlertHandledStatus>("");
  const [occurredAtFrom, setOccurredAtFrom] = useState(defaultTimeRange.from);
  const [occurredAtTo, setOccurredAtTo] = useState(defaultTimeRange.to);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [batchConfirmOpen, setBatchConfirmOpen] = useState(false);
  const [filters, setFilters] = useState({
    query: "",
    type: "",
    handledStatus: "" as AlertHandledStatus,
    occurredAtFrom: defaultTimeRange.from,
    occurredAtTo: defaultTimeRange.to,
  });

  const alertsQuery = useQuery({
    queryKey: ["screen-events", filters, page, pageSize],
    queryFn: () =>
      api.getScreenEvents({
        query: filters.query,
        type: filters.type,
        handled: filters.handledStatus === "" ? undefined : filters.handledStatus === "handled" ? "true" : "false",
        occurredAtFrom: toApiDateTime(filters.occurredAtFrom),
        occurredAtTo: toApiDateTime(filters.occurredAtTo),
        includeHandled: "true",
        page,
        pageSize,
      }),
    refetchInterval: 5000,
  });

  const alertsPage = alertsQuery.data;
  const alerts = useMemo(() => alertsPage?.items ?? [], [alertsPage?.items]);
  const totalAlerts = alertsPage?.total ?? 0;
  const currentPage = alertsPage?.page ?? page;
  const pageStartIndex = (currentPage - 1) * pageSize;

  function markAlertsHandledInCache(ids: string[]) {
    const handledIds = new Set(ids);
    const handledAt = new Date().toISOString();
    queryClient.setQueriesData<PageResult<ScreenEvent>>({ queryKey: ["screen-events"] }, (current) => {
      if (!current) {
        return current;
      }
      return {
        ...current,
        items: current.items.map((alert) => (handledIds.has(alert.id) ? { ...alert, handled: true, handledAt } : alert)),
      };
    });
    if (filters.handledStatus === "unhandled") {
      queryClient.setQueryData<PageResult<ScreenEvent>>(["screen-events", filters, page, pageSize], (current) => {
        if (!current) {
          return current;
        }
        const nextItems = current.items.filter((alert) => !handledIds.has(alert.id));
        const removedCount = current.items.length - nextItems.length;
        return {
          ...current,
          total: Math.max(0, current.total - removedCount),
          items: nextItems,
        };
      });
    }
  }

  function matchesCurrentBatchFilters(alert: ScreenEvent) {
    const normalizedQuery = normalizePlateQuery(filters.query);
    if (normalizedQuery && !normalizePlateQuery(alert.plate ?? "").includes(normalizedQuery)) {
      return false;
    }
    if (filters.type && alert.type !== filters.type) {
      return false;
    }
    if (!alert.occurredAt) {
      return true;
    }
    const occurredAtMs = new Date(alert.occurredAt).getTime();
    const occurredAtFromMs = filters.occurredAtFrom ? new Date(filters.occurredAtFrom).getTime() : undefined;
    const occurredAtToMs = filters.occurredAtTo ? new Date(filters.occurredAtTo).getTime() : undefined;
    if (occurredAtFromMs !== undefined && occurredAtMs < occurredAtFromMs) {
      return false;
    }
    if (occurredAtToMs !== undefined && occurredAtMs > occurredAtToMs) {
      return false;
    }
    return true;
  }

  function markFilteredUnhandledAlertsHandledInCache() {
    const handledAt = new Date().toISOString();
    queryClient.setQueryData<PageResult<ScreenEvent>>(["screen-events", filters, page, pageSize], (current) => {
      if (!current) {
        return current;
      }
      if (filters.handledStatus === "unhandled") {
        return {
          ...current,
          total: 0,
          items: [],
        };
      }
      return {
        ...current,
        items: current.items.map((alert) =>
          !alert.handled && matchesCurrentBatchFilters(alert) ? { ...alert, handled: true, handledAt } : alert,
        ),
      };
    });
  }

  const handleAlertMutation = useMutation({
    mutationFn: (id: string) => api.handleScreenEvent(id),
    onSuccess: async (_result, id) => {
      markAlertsHandledInCache([id]);
      await queryClient.refetchQueries({ queryKey: ["screen-events"], type: "active" });
    },
  });

  const batchHandleMutation = useMutation({
    mutationFn: () =>
      api.handleUnhandledScreenEvents({
        query: filters.query,
        type: filters.type,
        occurredAtFrom: toApiDateTime(filters.occurredAtFrom),
        occurredAtTo: toApiDateTime(filters.occurredAtTo),
      }),
    onSuccess: async () => {
      markFilteredUnhandledAlertsHandledInCache();
      setBatchConfirmOpen(false);
      await queryClient.invalidateQueries({ queryKey: ["screen-events"] });
      await alertsQuery.refetch();
    },
  });

  const exportAlertsMutation = useMutation({
    mutationFn: () =>
      api.getScreenEventsExport({
        query: filters.query,
        type: filters.type,
        handled: filters.handledStatus === "" ? undefined : filters.handledStatus === "handled" ? "true" : "false",
        occurredAtFrom: toApiDateTime(filters.occurredAtFrom),
        occurredAtTo: toApiDateTime(filters.occurredAtTo),
      }),
    onSuccess: (exportAlerts) => {
      downloadCsv(
        "vehicle-alerts.csv",
        [
          ["序号", "类型", "车牌号码", "处理状态", "告警内容", "发生时间", "处理时间", "来源ID", "来源名称"],
          ...exportAlerts.map((alert, index) => [
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
      );
    },
  });

  const canHandleAllUnhandled = filters.handledStatus !== "handled" && !batchHandleMutation.isPending;

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPage(1);
    setFilters({ query, type, handledStatus, occurredAtFrom, occurredAtTo });
    void queryClient.invalidateQueries({ queryKey: ["screen-events"] });
  }

  function handlePageChange(nextPage: number) {
    setPage(nextPage);
  }

  function handlePageSizeChange(nextPageSize: number) {
    setPageSize(nextPageSize);
    setPage(1);
  }

  return (
    <div className="space-y-5">
      <ConfirmModal
        open={batchConfirmOpen}
        title="全部处理告警"
        description="确认将当前筛选范围内的所有未处理告警标记为已处理？处理后将从大屏左侧告警框中移除。"
        confirmText="全部处理"
        busy={batchHandleMutation.isPending}
        onConfirm={() => batchHandleMutation.mutate()}
        onCancel={() => setBatchConfirmOpen(false)}
      />
      <Panel
        title="车辆告警"
        eyebrow="大屏左侧告警统一列表"
        action={
          <div className="flex flex-wrap items-center justify-end gap-2">
            <button
              type="button"
              disabled={!canHandleAllUnhandled}
              onClick={() => setBatchConfirmOpen(true)}
              className="inline-flex items-center gap-2 rounded-sm bg-slate-950 px-3 py-2 text-xs font-semibold text-white transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-60"
            >
              <CheckCircle2 className="size-3.5" />
              全部处理
            </button>
            <button
              type="button"
              disabled={exportAlertsMutation.isPending}
              onClick={() => exportAlertsMutation.mutate()}
              className="inline-flex items-center gap-2 rounded-sm border border-[var(--border-soft)] px-3 py-2 text-xs text-[var(--text-secondary)] transition hover:border-[var(--border-strong)] hover:text-[var(--text-primary)] disabled:cursor-not-allowed disabled:opacity-60"
            >
              <Download className="size-3.5" />
              {exportAlertsMutation.isPending ? "正在导出" : "导出全部"}
            </button>
          </div>
        }
      >
        <form
          onSubmit={handleSearch}
          className="flex flex-wrap items-center gap-3"
        >
          <label className="relative block w-full sm:w-[180px] sm:flex-none">
            <Search className="pointer-events-none absolute left-4 top-1/2 size-4 -translate-y-1/2 text-[var(--text-muted)]" />
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              className="w-full rounded-sm border border-[var(--border-soft)] bg-white py-3 pl-11 pr-4 text-sm text-[var(--text-primary)] outline-none placeholder:text-[var(--text-muted)] focus:border-sky-400/40"
              placeholder="模糊搜索车牌号"
            />
          </label>

          <FilterSelect
            className="w-full sm:w-[140px] sm:flex-none"
            value={type}
            onChange={setType}
            icon={Filter}
            options={[
              { value: "", label: "全部类型" },
              ...eventTypes.map((item) => ({ value: item.value, label: item.label })),
            ]}
          />

          <FilterSelect
            className="w-full sm:w-[140px] sm:flex-none"
            value={handledStatus}
            onChange={(value) => setHandledStatus(value as AlertHandledStatus)}
            icon={CheckCircle2}
            options={[
              { value: "", label: "全部状态" },
              { value: "unhandled", label: "未处理" },
              { value: "handled", label: "已处理" },
            ]}
          />

          <input
            type="datetime-local"
            value={occurredAtFrom}
            max={occurredAtTo || undefined}
            onChange={(event) => setOccurredAtFrom(event.target.value)}
            className="w-full rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none focus:border-sky-400/40 sm:w-[200px] sm:flex-none"
          />

          <input
            type="datetime-local"
            value={occurredAtTo}
            min={occurredAtFrom || undefined}
            onChange={(event) => setOccurredAtTo(event.target.value)}
            className="w-full rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none focus:border-sky-400/40 sm:w-[200px] sm:flex-none"
          />

          <button
            type="submit"
            className="inline-flex w-full items-center justify-center gap-2 rounded-sm bg-blue-600 px-4 py-3 text-sm font-semibold text-white shadow-sm transition hover:bg-blue-500 sm:w-[88px] sm:flex-none"
          >
            <Search className="size-4" />
            查询
          </button>

          <div className="ml-auto flex min-w-[130px] items-center justify-end whitespace-nowrap text-sm text-[var(--text-secondary)]">
            当前共 {totalAlerts} 条告警
          </div>
        </form>

        <div className="mt-5 overflow-hidden rounded-sm border border-[var(--border-soft)]">
          <div className="grid grid-cols-[0.45fr_0.7fr_0.8fr_0.75fr_minmax(0,1.45fr)_1fr_0.8fr_0.65fr] gap-3 bg-slate-100 px-5 py-4 text-[12px] font-bold uppercase tracking-[0.18em] text-slate-600">
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
            {alerts.map((alert, index) => (
              <div key={alert.id} className="grid grid-cols-[0.45fr_0.7fr_0.8fr_0.75fr_minmax(0,1.45fr)_1fr_0.8fr_0.65fr] gap-3 px-5 py-4 text-sm">
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
            total={totalAlerts}
            onPageChange={handlePageChange}
            onPageSizeChange={handlePageSizeChange}
          />
        </div>
      </Panel>
    </div>
  );
}
