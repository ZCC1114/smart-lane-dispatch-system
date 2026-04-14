"use client";

import { useDeferredValue, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Download, Search } from "lucide-react";
import { Panel } from "@/components/panel";
import { StatusBadge } from "@/components/status-badge";
import { api } from "@/lib/api";
import { downloadCsv, formatDateTime, logSourceLabel } from "@/lib/utils";

export default function EntriesPage() {
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("");
  const [laneId, setLaneId] = useState("");
  const deferredQuery = useDeferredValue(query);

  const logsQuery = useQuery({
    queryKey: ["logs", deferredQuery, status, laneId],
    queryFn: () => api.getLogs({ query: deferredQuery, status, laneId }),
  });

  const logs = logsQuery.data ?? [];

  return (
    <Panel
      title="车辆入场历史流水"
      eyebrow="实时查看、条件检索与历史追溯"
      action={
        <button
          type="button"
          onClick={() =>
            downloadCsv(
              "entry-logs.csv",
              [
                ["流水ID", "车牌号码", "车道编号", "车道名称", "入场时间", "离场时间", "车辆类型", "通行状态", "来源", "操作员"],
                ...logs.map((log) => [
                  log.id,
                  log.plate,
                  log.laneId,
                  log.laneName,
                  log.entryTime,
                  log.exitTime ?? "",
                  log.vehicleType,
                  log.status,
                  logSourceLabel(log.source),
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
      <div className="grid gap-4 md:grid-cols-[1fr_180px_160px]">
        <label className="relative block">
          <Search className="pointer-events-none absolute left-4 top-1/2 size-4 -translate-y-1/2 text-[var(--text-muted)]" />
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            className="w-full rounded-sm border border-[var(--border-soft)] bg-white py-3 pl-11 pr-4 text-sm text-[var(--text-primary)] outline-none placeholder:text-[var(--text-muted)] focus:border-sky-400/40"
            placeholder="搜索流水号、车牌或车道"
          />
        </label>

        <select
          value={status}
          onChange={(event) => setStatus(event.target.value)}
          className="rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none focus:border-sky-400/40"
        >
          <option value="">全部状态</option>
          <option value="PASSED">正常放行</option>
          <option value="REJECTED">拦截拒绝</option>
          <option value="MANUAL">人工处理</option>
        </select>

        <input
          value={laneId}
          onChange={(event) => setLaneId(event.target.value)}
          className="rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none placeholder:text-[var(--text-muted)] focus:border-sky-400/40"
          placeholder="车道编号"
        />
      </div>

      <div className="mt-5 overflow-hidden rounded-sm border border-[var(--border-soft)]">
        <div className="grid grid-cols-[0.8fr_0.85fr_0.85fr_0.9fr_0.9fr_0.6fr_0.7fr_0.8fr] gap-3 bg-slate-100 px-5 py-4 text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">
          <span>流水 ID</span>
          <span>车牌号码</span>
          <span>通行车道</span>
          <span>入场时间</span>
          <span>离场时间</span>
          <span>车辆类型</span>
          <span>通行状态</span>
          <span>来源</span>
        </div>
        <div className="divide-y divide-[var(--border-soft)]">
          {logs.map((log) => (
            <div key={log.id} className="grid grid-cols-[0.8fr_0.85fr_0.85fr_0.9fr_0.9fr_0.6fr_0.7fr_0.8fr] gap-3 px-5 py-4 text-sm">
              <span className="font-mono text-[var(--text-secondary)]">{log.id}</span>
              <span className="font-mono font-semibold text-[var(--text-primary)]">{log.plate}</span>
              <div>
                <p className="text-[var(--text-primary)]">{log.laneName}</p>
                <p className="mt-1 text-xs text-[var(--text-secondary)]">{log.laneId}</p>
              </div>
              <span className="text-[var(--text-secondary)]">{formatDateTime(log.entryTime)}</span>
              <span className="text-[var(--text-secondary)]">{log.exitTime ? formatDateTime(log.exitTime) : "在场"}</span>
              <span className="text-[var(--text-secondary)]">{log.vehicleType}</span>
              <div>
                <StatusBadge value={log.status} kind="log" />
              </div>
              <span className="text-[var(--text-secondary)]">{logSourceLabel(log.source)}</span>
            </div>
          ))}
          {logs.length === 0 ? (
            <div className="px-5 py-10 text-center text-sm text-[var(--text-secondary)]">当前筛选条件下没有匹配到入场记录。</div>
          ) : null}
        </div>
      </div>
    </Panel>
  );
}
