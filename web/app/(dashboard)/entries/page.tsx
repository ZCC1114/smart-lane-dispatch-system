"use client";

import { type FormEvent, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Download, Search } from "lucide-react";
import { FilterSelect } from "@/components/filter-select";
import { Panel } from "@/components/panel";
import { StatusBadge } from "@/components/status-badge";
import { TablePagination } from "@/components/table-pagination";
import { api } from "@/lib/api";
import { downloadCsv, formatDateTime, formatPlateDisplay } from "@/lib/utils";

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

export default function EntriesPage() {
  const defaultTimeRange = todayRange();
  const [query, setQuery] = useState("");
  const [laneId, setLaneId] = useState("");
  const [entryTimeFrom, setEntryTimeFrom] = useState(defaultTimeRange.from);
  const [entryTimeTo, setEntryTimeTo] = useState(defaultTimeRange.to);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [filters, setFilters] = useState({
    query: "",
    laneId: "",
    entryTimeFrom: defaultTimeRange.from,
    entryTimeTo: defaultTimeRange.to,
  });

  const lanesQuery = useQuery({
    queryKey: ["lanes"],
    queryFn: api.getLanes,
  });

  const logsQuery = useQuery({
    queryKey: ["logs", filters],
    queryFn: () =>
      api.getLogs({
        query: filters.query,
        laneId: filters.laneId,
        entryTimeFrom: toApiDateTime(filters.entryTimeFrom),
        entryTimeTo: toApiDateTime(filters.entryTimeTo),
      }),
  });
  const lanes = lanesQuery.data ?? [];
  const logs = logsQuery.data ?? [];
  const pageCount = Math.max(1, Math.ceil(logs.length / pageSize));
  const currentPage = Math.min(page, pageCount);
  const pageStartIndex = (currentPage - 1) * pageSize;
  const pagedLogs = logs.slice((currentPage - 1) * pageSize, currentPage * pageSize);
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
    setFilters({ query, laneId, entryTimeFrom, entryTimeTo });
  }

  return (
    <div className="space-y-5">
<Panel
        title="车辆进出流水"
        eyebrow="实际入道与出场记录"
        action={
          <button
            type="button"
            onClick={() =>
              downloadCsv(
                "traffic-logs.csv",
                [
                  ["序号", "车牌号码", "实际入道车道编号", "实际入道车道", "分配车道编号", "分配车道", "入场时间", "离场时间", "车辆类型", "通行状态", "操作员"],
                  ...logs.map((log, index) => [
                    String(index + 1),
                    log.plate,
                    log.laneId ?? "",
                    log.laneName ?? "",
                    log.assignedLaneId ?? "",
                    log.assignedLaneName ?? "",
                    log.entryTime,
                    log.exitTime ?? "",
                    log.vehicleType,
                    log.status,
                    log.operator,
                  ]),
                ],
              )
            }
            className="inline-flex items-center gap-2 rounded-sm border border-[var(--border-soft)] px-3 py-2 text-xs text-[var(--text-secondary)] transition hover:border-[var(--border-strong)] hover:text-[var(--text-primary)]"
          >
            <Download className="size-3.5" />
            导出表格
          </button>
        }
      >
        <form onSubmit={handleSearch} className="grid gap-4 md:grid-cols-2 xl:grid-cols-[minmax(0,1.1fr)_180px_220px_220px_220px_120px]">
          <label className="relative block">
            <Search className="pointer-events-none absolute left-4 top-1/2 size-4 -translate-y-1/2 text-[var(--text-muted)]" />
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              className="w-full rounded-sm border border-[var(--border-soft)] bg-white py-3 pl-11 pr-4 text-sm text-[var(--text-primary)] outline-none placeholder:text-[var(--text-muted)] focus:border-sky-400/40"
              placeholder="模糊搜索车牌号"
            />
          </label>

          <FilterSelect value={laneId} options={laneOptions} onChange={setLaneId} />

          <input
            type="datetime-local"
            value={entryTimeFrom}
            max={entryTimeTo || undefined}
            onChange={(event) => setEntryTimeFrom(event.target.value)}
            className="rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none placeholder:text-[var(--text-muted)] focus:border-sky-400/40"
            placeholder="入场开始时间"
          />

          <input
            type="datetime-local"
            value={entryTimeTo}
            min={entryTimeFrom || undefined}
            onChange={(event) => setEntryTimeTo(event.target.value)}
            className="rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none placeholder:text-[var(--text-muted)] focus:border-sky-400/40"
            placeholder="入场结束时间"
          />

          <button
            type="submit"
            className="inline-flex items-center justify-center gap-2 rounded-sm bg-blue-600 px-4 py-3 text-sm font-semibold text-white shadow-sm transition hover:bg-blue-500"
          >
            <Search className="size-4" />
            查询
          </button>
        </form>

        <div className="mt-5 rounded-sm border border-[var(--border-soft)]">
          <div className="overflow-hidden rounded-t-sm">
            <div className="grid grid-cols-[0.45fr_0.9fr_0.95fr_0.95fr_1fr_1fr_0.65fr_0.75fr] gap-3 bg-slate-100 px-5 py-4 text-[12px] font-bold uppercase tracking-[0.18em] text-slate-600">
              <span>序号</span>
              <span>车牌号码</span>
              <span>实际入道车道</span>
              <span>分配车道</span>
              <span>入场时间</span>
              <span>出场时间</span>
              <span>车辆类型</span>
              <span>通行状态</span>
            </div>
            <div className="divide-y divide-[var(--border-soft)]">
              {pagedLogs.map((log, index) => (
                <div key={log.id} className="grid grid-cols-[0.45fr_0.9fr_0.95fr_0.95fr_1fr_1fr_0.65fr_0.75fr] gap-3 px-5 py-4 text-sm">
                  <span className="font-mono text-[var(--text-secondary)]">{pageStartIndex + index + 1}</span>
                  <span className="font-mono font-semibold text-[var(--text-primary)]">{formatPlateDisplay(log.plate) || log.plate}</span>
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
                  <div>
                    <StatusBadge value={log.status} kind="log" />
                  </div>
                </div>
              ))}
              {logs.length === 0 ? (
                <div className="px-5 py-10 text-center text-sm text-[var(--text-secondary)]">当前筛选条件下没有匹配到车辆流水。</div>
              ) : null}
            </div>
          </div>
          <TablePagination
            page={currentPage}
            pageSize={pageSize}
            total={logs.length}
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
