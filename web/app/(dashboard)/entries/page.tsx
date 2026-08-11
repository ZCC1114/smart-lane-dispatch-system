"use client";

import { type FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, PencilLine, Search } from "lucide-react";
import { FilterSelect } from "@/components/filter-select";
import { Panel } from "@/components/panel";
import { StatusBadge } from "@/components/status-badge";
import { TablePagination } from "@/components/table-pagination";
import { api } from "@/lib/api";
import { canOperateSignals } from "@/lib/permissions";
import type { EntryLog } from "@/lib/types";
import { downloadCsv, formatDateTime, formatPlateDisplay, screenEventTypeLabel } from "@/lib/utils";
import { useAuthStore } from "@/stores/auth-store";

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

const ALARM_TYPE_OPTIONS = [
  { value: "", label: "全部告警类型" },
  { value: "blacklist", label: "黑名单" },
  { value: "not_whitelisted", label: "非白名单" },
  { value: "wrong_lane", label: "走错车道" },
  { value: "not_entered", label: "未进车道" },
  { value: "none", label: "无告警" },
];

export default function EntriesPage() {
  const role = useAuthStore((state) => state.user?.role);
  const canCorrectPlate = canOperateSignals(role);
  const queryClient = useQueryClient();
  const defaultTimeRange = todayRange();
  const [query, setQuery] = useState("");
  const [laneId, setLaneId] = useState("");
  const [alarmType, setAlarmType] = useState("");
  const [entryTimeFrom, setEntryTimeFrom] = useState(defaultTimeRange.from);
  const [entryTimeTo, setEntryTimeTo] = useState(defaultTimeRange.to);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState("");
  const [editingLog, setEditingLog] = useState<EntryLog | null>(null);
  const [correctedPlate, setCorrectedPlate] = useState("");
  const [closeExistingActiveRecord, setCloseExistingActiveRecord] = useState(false);
  const [plateCorrectionError, setPlateCorrectionError] = useState("");
  const [filters, setFilters] = useState({
    query: "",
    laneId: "",
    alarmType: "",
    entryTimeFrom: defaultTimeRange.from,
    entryTimeTo: defaultTimeRange.to,
  });

  const lanesQuery = useQuery({
    queryKey: ["lanes"],
    queryFn: api.getLanes,
  });

  const logsQuery = useQuery({
    queryKey: ["logs", filters, page, pageSize],
    queryFn: () =>
      api.getLogs({
        query: filters.query,
        laneId: filters.laneId,
        alarmType: filters.alarmType,
        entryTimeFrom: toApiDateTime(filters.entryTimeFrom),
        entryTimeTo: toApiDateTime(filters.entryTimeTo),
        page,
        pageSize,
      }),
  });

  const plateCorrectionMutation = useMutation({
    mutationFn: () =>
      api.correctEntryLogPlate(editingLog!.id, {
        plate: correctedPlate.trim(),
        closeExistingActiveRecord,
      }),
    onSuccess: async () => {
      setEditingLog(null);
      setCorrectedPlate("");
      setCloseExistingActiveRecord(false);
      setPlateCorrectionError("");
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["logs"] }),
        queryClient.invalidateQueries({ queryKey: ["lanes"] }),
        queryClient.invalidateQueries({ queryKey: ["dispatch-board"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
      ]);
    },
    onError: (error) => {
      setPlateCorrectionError(error instanceof Error ? error.message : "修改车牌失败");
    },
  });

  const lanes = lanesQuery.data ?? [];
  const logsPage = logsQuery.data;
  const logs = logsPage?.items ?? [];
  const totalLogs = logsPage?.total ?? 0;
  const currentPage = logsPage?.page ?? page;
  const pageStartIndex = (currentPage - 1) * pageSize;

  const laneOptions = [
    { value: "", label: "全部车道" },
    ...lanes.map((lane) => ({
      value: lane.id,
      label: `${lane.code} · ${lane.name}`,
    })),
  ];

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPage(1);
    setFilters({
      query,
      laneId,
      alarmType,
      entryTimeFrom,
      entryTimeTo,
    });
    setExportError("");
  }

  async function handleExport() {
    try {
      setExporting(true);
      setExportError("");
      const exportItems = await api.getLogsExport({
        query: filters.query,
        laneId: filters.laneId,
        alarmType: filters.alarmType,
        entryTimeFrom: toApiDateTime(filters.entryTimeFrom),
        entryTimeTo: toApiDateTime(filters.entryTimeTo),
      });

      downloadCsv(
        "traffic-logs.csv",
        [
          [
            "序号",
            "车牌号码",
            "实际入道车道编号",
            "实际入道车道",
            "分配车道编号",
            "分配车道",
            "总入口抓拍时间",
            "离场时间",
            "车辆类型",
            "告警类型",
            "通行状态",
            "操作员",
          ],
          ...exportItems.map((log, index) => [
            String(index + 1),
            log.plate,
            log.laneId ?? "",
            log.laneName ?? "",
            log.assignedLaneId ?? "",
            log.assignedLaneName ?? "",
            log.entryTime,
            log.exitTime ?? "",
            log.vehicleType,
            log.alarmType ? screenEventTypeLabel(log.alarmType) : "",
            log.status,
            log.operator,
          ]),
        ],
      );
    } catch (error) {
      setExportError(error instanceof Error ? error.message : "车辆流水导出失败");
    } finally {
      setExporting(false);
    }
  }

  function openPlateCorrection(log: EntryLog) {
    setEditingLog(log);
    setCorrectedPlate(log.plate);
    setCloseExistingActiveRecord(false);
    setPlateCorrectionError("");
    plateCorrectionMutation.reset();
  }

  function submitPlateCorrection() {
    const normalizedPlate = correctedPlate.replace(/[·\s]/g, "").toUpperCase();
    const previousPlate = editingLog?.plate.replace(/[·\s]/g, "").toUpperCase();
    if (!normalizedPlate) {
      setPlateCorrectionError("请输入正确车牌号");
      return;
    }
    if (normalizedPlate === previousPlate) {
      setPlateCorrectionError("车牌号没有发生变化");
      return;
    }
    setPlateCorrectionError("");
    plateCorrectionMutation.mutate();
  }

  return (
    <>
      {editingLog ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/18 p-4 backdrop-blur-sm">
          <form
            onSubmit={(event) => {
              event.preventDefault();
              submitPlateCorrection();
            }}
            className="panel-surface w-full max-w-md rounded-sm p-6"
          >
            <p className="text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">车辆校正</p>
            <h2 className="mt-3 text-2xl font-semibold text-[var(--text-primary)]">修改在场车牌</h2>
            <p className="mt-3 text-sm leading-6 text-[var(--text-secondary)]">
              {editingLog.laneName ?? editingLog.laneId} · 原车牌 {formatPlateDisplay(editingLog.plate) || editingLog.plate}
            </p>
            <p className="mt-2 text-xs leading-5 text-[var(--text-muted)]">
              保存后将同步修改车辆流水、调度记录和车道当前车牌，不改变车辆数和进出时间。
            </p>
            <label className="mt-5 block text-sm font-semibold text-[var(--text-primary)]" htmlFor="corrected-plate-input">
              正确车牌号
            </label>
            <input
              id="corrected-plate-input"
              autoFocus
              type="text"
              value={correctedPlate}
              onChange={(event) => {
                setCorrectedPlate(event.target.value);
                setPlateCorrectionError("");
              }}
              className="mt-2 w-full rounded-sm border border-[var(--border-soft)] px-3 py-2 text-sm text-[var(--text-primary)] outline-none transition focus:border-[var(--brand)] focus:ring-2 focus:ring-blue-100"
            />
            <label className="mt-4 flex items-start gap-3 rounded-sm border border-amber-200 bg-amber-50 px-3 py-3 text-sm text-amber-900">
              <input
                type="checkbox"
                checked={closeExistingActiveRecord}
                onChange={(event) => {
                  setCloseExistingActiveRecord(event.target.checked);
                  setPlateCorrectionError("");
                }}
                className="mt-0.5 size-4 shrink-0 rounded border-amber-300 text-amber-700 focus:ring-amber-200"
              />
              <span>
                <span className="block font-semibold">关闭正确车牌的旧记录后修改</span>
                <span className="mt-1 block text-xs leading-5 text-amber-800">
                  仅在正确车牌也因出口地感错位而遗留未出场记录时勾选，不会再次扣减旧车道车辆数。
                </span>
              </span>
            </label>
            {plateCorrectionError ? <p className="mt-3 text-sm text-rose-600">{plateCorrectionError}</p> : null}
            <div className="mt-6 flex justify-end gap-3">
              <button
                type="button"
                disabled={plateCorrectionMutation.isPending}
                onClick={() => {
                  setEditingLog(null);
                  setCorrectedPlate("");
                  setCloseExistingActiveRecord(false);
                  setPlateCorrectionError("");
                }}
                className="rounded-sm border border-[var(--border-soft)] px-4 py-2 text-sm text-[var(--text-secondary)] transition hover:border-[var(--border-strong)] hover:text-[var(--text-primary)] disabled:opacity-60"
              >
                取消
              </button>
              <button
                type="submit"
                disabled={plateCorrectionMutation.isPending}
                className="rounded-sm bg-[var(--brand)] px-4 py-2 text-sm font-semibold text-white transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-70"
              >
                {plateCorrectionMutation.isPending ? "处理中..." : "确认修改"}
              </button>
            </div>
          </form>
        </div>
      ) : null}

      <div className="space-y-5">
        {exportError ? <div className="rounded-sm border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">导出失败：{exportError}</div> : null}
        <Panel
        title="车辆进出流水"
        eyebrow="实际入道与出场记录"
        action={
          <button
            type="button"
            disabled={exporting}
            onClick={handleExport}
            className="inline-flex items-center gap-2 rounded-sm border border-[var(--border-soft)] px-3 py-2 text-xs text-[var(--text-secondary)] transition hover:border-[var(--border-strong)] hover:text-[var(--text-primary)] disabled:cursor-not-allowed disabled:opacity-60"
          >
            <Download className="size-3.5" />
            {exporting ? "导出中..." : "导出全部"}
          </button>
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

          <FilterSelect className="w-full sm:w-[130px] sm:flex-none" value={laneId} options={laneOptions} onChange={setLaneId} />
          <FilterSelect className="w-full sm:w-[160px] sm:flex-none" value={alarmType} options={ALARM_TYPE_OPTIONS} onChange={setAlarmType} />

          <input
            type="datetime-local"
            value={entryTimeFrom}
            max={entryTimeTo || undefined}
            onChange={(event) => setEntryTimeFrom(event.target.value)}
            className="w-full rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none placeholder:text-[var(--text-muted)] focus:border-sky-400/40 sm:w-[200px] sm:flex-none"
            placeholder="入场开始时间"
          />

          <input
            type="datetime-local"
            value={entryTimeTo}
            min={entryTimeFrom || undefined}
            onChange={(event) => setEntryTimeTo(event.target.value)}
            className="w-full rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none placeholder:text-[var(--text-muted)] focus:border-sky-400/40 sm:w-[200px] sm:flex-none"
            placeholder="入场结束时间"
          />

          <button
            type="submit"
            className="inline-flex w-full items-center justify-center gap-2 rounded-sm bg-blue-600 px-4 py-3 text-sm font-semibold text-white shadow-sm transition hover:bg-blue-500 sm:w-[88px] sm:flex-none"
          >
            <Search className="size-4" />
            查询
          </button>
        </form>

        <div className="mt-5 rounded-sm border border-[var(--border-soft)]">
          <div className="overflow-hidden rounded-t-sm">
            <div className="grid grid-cols-[0.45fr_0.9fr_0.95fr_0.95fr_1fr_1fr_0.65fr_0.75fr_0.75fr] gap-3 bg-slate-100 px-5 py-4 text-[12px] font-bold uppercase tracking-[0.18em] text-slate-600">
              <span>序号</span>
              <span>车牌号码</span>
              <span>实际入道车道</span>
              <span>分配车道</span>
              <span>总入口抓拍时间</span>
              <span>出场时间</span>
              <span>车辆类型</span>
              <span>告警类型</span>
              <span>通行状态</span>
            </div>
            <div className="divide-y divide-[var(--border-soft)]">
              {logs.map((log, index) => (
                <div key={log.id} className="grid grid-cols-[0.45fr_0.9fr_0.95fr_0.95fr_1fr_1fr_0.65fr_0.75fr_0.75fr] gap-3 px-5 py-4 text-sm">
                  <span className="font-mono text-[var(--text-secondary)]">{pageStartIndex + index + 1}</span>
                  <span className="inline-flex items-center gap-2 font-mono font-semibold text-[var(--text-primary)]">
                    <span>{formatPlateDisplay(log.plate) || log.plate}</span>
                    {canCorrectPlate && !log.exitTime && log.laneId && log.id.startsWith("LOG-") ? (
                      <button
                        type="button"
                        title="修改在场车牌"
                        aria-label={`修改车牌 ${log.plate}`}
                        onClick={() => openPlateCorrection(log)}
                        className="inline-flex size-7 items-center justify-center rounded-sm border border-sky-200 bg-sky-50 text-sky-700 transition hover:border-sky-300 hover:bg-sky-100"
                      >
                        <PencilLine className="size-3.5" />
                      </button>
                    ) : null}
                  </span>
                  <span className="inline-flex items-center gap-2 text-[var(--text-primary)]">
                    <span>{log.laneName ?? "--"}</span>
                    {log.laneId ? <span className="text-xs text-[var(--text-secondary)]">{log.laneId}</span> : null}
                  </span>
                  <span className="inline-flex items-center gap-2 text-[var(--text-primary)]">
                    <span>{log.assignedLaneName ?? "--"}</span>
                    {log.assignedLaneId ? <span className="text-xs text-[var(--text-secondary)]">{log.assignedLaneId}</span> : null}
                  </span>
                  <span className="text-[var(--text-secondary)]">{formatDateTime(log.entryTime)}</span>
                  <span className="text-[var(--text-secondary)]">{log.exitTime ? formatDateTime(log.exitTime) : "在场"}</span>
                  <span className="text-[var(--text-secondary)]">{log.vehicleType}</span>
                  <span className="text-[var(--text-secondary)]">{log.alarmType ? screenEventTypeLabel(log.alarmType) : "--"}</span>
                  <div>
                    <StatusBadge value={log.status} kind="log" />
                  </div>
                </div>
              ))}
              {logsQuery.isLoading ? (
                <div className="px-5 py-10 text-center text-sm text-[var(--text-secondary)]">正在加载车辆流水...</div>
              ) : null}
              {!logsQuery.isLoading && logs.length === 0 ? (
                <div className="px-5 py-10 text-center text-sm text-[var(--text-secondary)]">当前筛选条件下没有匹配到车辆流水。</div>
              ) : null}
            </div>
          </div>
          <TablePagination
            page={currentPage}
            pageSize={pageSize}
            total={totalLogs}
            onPageChange={setPage}
            onPageSizeChange={(nextPageSize) => {
              setPageSize(nextPageSize);
              setPage(1);
            }}
          />
        </div>
        </Panel>
      </div>
    </>
  );
}
