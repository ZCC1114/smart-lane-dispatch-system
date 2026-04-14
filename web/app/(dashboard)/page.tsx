"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Activity, AlertTriangle, Car, CheckCircle2, Clock, TimerReset } from "lucide-react";
import { useState } from "react";
import { ConfirmModal } from "@/components/confirm-modal";
import { LaneOverviewCard } from "@/components/lane-overview-card";
import { MetricCard } from "@/components/metric-card";
import { Panel } from "@/components/panel";
import { api } from "@/lib/api";
import { canDispatch } from "@/lib/permissions";
import { formatDateTime, formatShortTime, sensorStatusLabel, signalLabel } from "@/lib/utils";
import { useAuthStore } from "@/stores/auth-store";

export default function DashboardPage() {
  const role = useAuthStore((state) => state.user?.role);
  const canOperate = canDispatch(role);
  const queryClient = useQueryClient();
  const [lockdownOpen, setLockdownOpen] = useState(false);

  const dashboardQuery = useQuery({
    queryKey: ["dashboard"],
    queryFn: api.getDashboard,
  });

  const lockdownMutation = useMutation({
    mutationFn: api.globalLockdown,
    onSuccess: async () => {
      setLockdownOpen(false);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
        queryClient.invalidateQueries({ queryKey: ["lanes"] }),
      ]);
    },
  });

  if (dashboardQuery.isLoading || !dashboardQuery.data) {
    return <div className="rounded-sm border border-[var(--border-soft)] px-5 py-6 text-sm text-[var(--text-secondary)]">正在加载车道总览...</div>;
  }

  const dashboard = dashboardQuery.data;
  const maxThroughput = Math.max(...dashboard.throughput.map((point) => point.count), 1);
  const hasRuntimeData = dashboard.lanes.length > 0 || dashboard.alerts.length > 0 || dashboard.metrics.totalPassages > 0;

  return (
    <>
      <ConfirmModal
        open={lockdownOpen}
        title="执行全域锁死"
        description="系统会将所有可控入口信号切换为红灯，并写入一条高优先级告警。这个操作用于突发事件处置。"
        confirmText="立即锁死"
        busy={lockdownMutation.isPending}
        onCancel={() => setLockdownOpen(false)}
        onConfirm={() => lockdownMutation.mutate()}
      />

      <div className="space-y-6">
        <section className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <h1 className="flex items-center text-2xl font-bold text-[var(--text-primary)]">
              <Activity className="mr-3 size-6 text-blue-500" />
              车道运行总览
            </h1>
            <p className="mt-1 text-sm text-slate-500">实时数据流更新时间: {formatDateTime(dashboard.generatedAt)}</p>
          </div>
          <div className="flex flex-wrap gap-3">
            <button
              type="button"
              onClick={() => queryClient.invalidateQueries({ queryKey: ["dashboard"] })}
              className="rounded-sm border border-slate-200 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-50"
            >
              下载今日报告
            </button>
            {canOperate ? (
              <button
                type="button"
                onClick={() => setLockdownOpen(true)}
                className="rounded-sm bg-blue-600 px-4 py-2 text-sm font-medium text-white shadow-lg shadow-blue-500/20 transition-colors hover:bg-blue-500"
              >
                手动紧急锁死
              </button>
            ) : (
              <button
                type="button"
                onClick={() => queryClient.invalidateQueries({ queryKey: ["dashboard"] })}
                className="rounded-sm border border-slate-200 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-50"
              >
                <span className="inline-flex items-center gap-2">
                  <TimerReset className="size-4" />
                  刷新数据
                </span>
              </button>
            )}
          </div>
        </section>

        <section className="grid gap-6 md:grid-cols-2 lg:grid-cols-4">
          <MetricCard
            icon={Car}
            label="当日通行总量"
            value={dashboard.metrics.totalPassages.toString()}
            hint={`+${dashboard.metrics.activeLaneRate.toFixed(1)}%`}
            accentClassName="bg-blue-600 text-white"
          />
          <MetricCard
            icon={Clock}
            label="平均通过时长"
            value={`${Math.floor(dashboard.metrics.averagePassMinutes)}'${String(
              Math.round((dashboard.metrics.averagePassMinutes % 1) * 60),
            ).padStart(2, "0")}"`}
            hint="-0.8%"
            accentClassName="bg-indigo-600 text-white"
          />
          <MetricCard
            icon={AlertTriangle}
            label="黑名单预警数"
            value={dashboard.metrics.blacklistAlerts.toString().padStart(2, "0")}
            hint="+01"
            accentClassName="bg-amber-600 text-white"
          />
          <MetricCard
            icon={CheckCircle2}
            label="系统健康度"
            value={`${dashboard.metrics.systemHealth.toFixed(1)}%`}
            hint="+0.2%"
            accentClassName="bg-emerald-600 text-white"
          />
        </section>

        <section className="grid gap-6 lg:grid-cols-3">
          <Panel
            title="车辆通过频率"
            eyebrow="每小时"
            className="lg:col-span-2"
            action={
              <select className="rounded-sm border border-slate-200 bg-white px-3 py-1 text-xs text-slate-600 outline-none">
                <option>最近 24 小时</option>
                <option>最近 7 天</option>
              </select>
            }
          >
            <div className="relative h-[240px] w-full overflow-hidden">
              <div className="absolute inset-0 flex flex-col justify-between text-[10px] text-slate-600">
                {[120, 90, 60, 30, 0].map((tick) => (
                  <div key={tick} className="relative border-t border-slate-200 pt-1">
                    <span>{tick}</span>
                  </div>
                ))}
              </div>
              <div className="absolute inset-x-8 bottom-0 top-0 flex items-end gap-6 pl-8">
                {dashboard.throughput.map((point) => (
                  <div key={point.label} className="flex flex-1 flex-col items-center justify-end">
                    <div className="relative flex h-full w-full items-end">
                      <div
                        className="w-full border border-blue-500/30 bg-gradient-to-t from-blue-600/10 to-blue-500/30"
                        style={{ height: `${Math.max((point.count / maxThroughput) * 100, 8)}%` }}
                      />
                    </div>
                    <span className="mt-2 text-[10px] text-slate-500">{point.label}</span>
                  </div>
                ))}
              </div>
            </div>
          </Panel>

          <Panel title="最新预警" eyebrow="实时事件">
            <div className="space-y-4">
              {dashboard.alerts.slice(0, 3).map((alert) => (
                <article key={alert.id} className="rounded-sm border-l-4 border-red-500 bg-red-50 p-3">
                  <div className="flex items-start gap-3">
                    <AlertTriangle className="mt-0.5 size-5 shrink-0 text-red-500" />
                    <div>
                      <p className="text-sm font-bold text-red-700">{alert.message}</p>
                      <p className="mt-1 font-mono text-xs text-slate-500">
                        {alert.plate ?? "未绑定车牌"} | {formatShortTime(alert.createdAt)}
                      </p>
                      <button className="mt-2 text-[10px] font-bold uppercase tracking-[0.18em] text-red-500 transition-colors hover:text-red-700">
                        立刻处理 →
                      </button>
                    </div>
                  </div>
                </article>
              ))}
              {dashboard.alerts.length === 0 ? (
                <div className="rounded-sm border border-[var(--border-soft)] bg-slate-50 px-4 py-6 text-sm text-[var(--text-secondary)]">
                  当前数据库里还没有预警事件。
                </div>
              ) : null}
            </div>
          </Panel>
        </section>

        <section className="grid gap-6 pb-6 md:grid-cols-2 lg:grid-cols-3">
          {dashboard.lanes.map((lane) => (
            <LaneOverviewCard key={lane.id} lane={lane} canOperate={canOperate} />
          ))}
          {!hasRuntimeData ? (
            <div className="md:col-span-2 lg:col-span-3 rounded-sm border border-[var(--border-soft)] bg-slate-50 px-6 py-12 text-center text-sm text-[var(--text-secondary)]">
              当前数据库没有车道、预警或通行记录，首页会在真实数据写入后自动展示。
            </div>
          ) : null}
        </section>
        {dashboard.lanes.length > 0 ? (
          <section className="rounded-sm border border-[var(--border-soft)] bg-white p-6 shadow-sm">
            <div className="flex items-center justify-between gap-3">
              <div>
                <p className="text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">自动调度说明</p>
                <h2 className="mt-2 text-xl font-semibold text-[var(--text-primary)]">入口满停自动禁入，出口按优先车道和最早入场顺序放行</h2>
              </div>
            </div>
            <div className="mt-4 grid gap-4 md:grid-cols-3">
              {dashboard.lanes.slice(0, 3).map((lane) => (
                <div key={lane.id} className="rounded-sm border border-[var(--border-soft)] bg-slate-50 px-4 py-4 text-sm text-[var(--text-secondary)]">
                  <p className="font-semibold text-[var(--text-primary)]">{lane.name}</p>
                  <p className="mt-2">传感器 {sensorStatusLabel(lane.sensorStatus)}，入口 {signalLabel(lane.entrySignal)}，出口 {signalLabel(lane.exitSignal)}</p>
                  <p className="mt-1">最近入场 {lane.lastEntryAt ? formatDateTime(lane.lastEntryAt) : "暂无"}</p>
                </div>
              ))}
            </div>
          </section>
        ) : null}
      </div>
    </>
  );
}
