"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ShieldCheck } from "lucide-react";
import { Panel } from "@/components/panel";
import { StatusBadge } from "@/components/status-badge";
import { api } from "@/lib/api";
import { canResolveAlerts } from "@/lib/permissions";
import { alertStatusLabel, formatDateTime } from "@/lib/utils";
import { useAuthStore } from "@/stores/auth-store";

export default function AlertsPage() {
  const role = useAuthStore((state) => state.user?.role);
  const queryClient = useQueryClient();
  const canHandle = canResolveAlerts(role);

  const alertsQuery = useQuery({
    queryKey: ["alerts"],
    queryFn: api.getAlerts,
  });

  const resolveMutation = useMutation({
    mutationFn: (alertId: string) => api.resolveAlert(alertId),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["alerts"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
      ]);
    },
  });

  const alerts = alertsQuery.data ?? [];

  return (
    <Panel title="预警中心" eyebrow="黑名单与调度异常事件">
      <div className="space-y-4">
        {alerts.map((alert) => (
          <article key={alert.id} className="rounded-sm border border-[var(--border-soft)] bg-white p-5 shadow-sm">
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div>
                <div className="flex flex-wrap items-center gap-3">
                  <StatusBadge value={alert.level} kind="level" />
                  <span className="rounded-sm border border-[var(--border-soft)] px-3 py-1 text-[11px] uppercase tracking-[0.28em] text-[var(--text-secondary)]">
                    {alertStatusLabel(alert.status)}
                  </span>
                </div>
                <h2 className="mt-4 text-xl font-semibold text-[var(--text-primary)]">{alert.message}</h2>
                <p className="mt-3 text-sm text-[var(--text-secondary)]">
                  车道 {alert.laneName} ({alert.laneId}) · 车牌 {alert.plate ?? "无"}
                </p>
                <p className="mt-2 text-xs text-[var(--text-muted)]">{formatDateTime(alert.createdAt)}</p>
              </div>
              {canHandle && alert.status !== "RESOLVED" ? (
                <button
                  type="button"
                  disabled={resolveMutation.isPending}
                  onClick={() => resolveMutation.mutate(alert.id)}
                  className="inline-flex items-center gap-2 rounded-sm bg-emerald-500/12 px-4 py-2 text-sm font-medium text-emerald-700 transition hover:bg-emerald-500/18"
                >
                  <ShieldCheck className="size-4" />
                  标记已处理
                </button>
              ) : null}
            </div>
          </article>
        ))}

        {alerts.length === 0 ? (
          <div className="rounded-sm border border-[var(--border-soft)] px-5 py-10 text-center text-sm text-[var(--text-secondary)]">
            当前没有待处理预警。
          </div>
        ) : null}
      </div>
    </Panel>
  );
}
