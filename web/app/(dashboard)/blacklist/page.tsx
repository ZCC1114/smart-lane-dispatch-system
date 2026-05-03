"use client";

import { type FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { PencilLine, Plus, Search, Trash2 } from "lucide-react";
import { ConfirmModal } from "@/components/confirm-modal";
import { Panel } from "@/components/panel";
import { StatusBadge } from "@/components/status-badge";
import { api } from "@/lib/api";
import { canAccessBlacklist } from "@/lib/permissions";
import type { BlacklistPayload, BlacklistRecord } from "@/lib/types";
import { levelLabel } from "@/lib/utils";
import { useAuthStore } from "@/stores/auth-store";

const emptyForm: BlacklistPayload = {
  plate: "",
  reason: "",
  level: "MEDIUM",
  operator: "系统管理员",
  active: true,
};

export default function BlacklistPage() {
  const role = useAuthStore((state) => state.user?.role);
  const canManage = canAccessBlacklist(role);
  const queryClient = useQueryClient();
  const [query, setQuery] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [formState, setFormState] = useState<BlacklistPayload>(emptyForm);
  const [editingRecord, setEditingRecord] = useState<BlacklistRecord | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  const blacklistQuery = useQuery({
    queryKey: ["blacklist", searchQuery],
    queryFn: () => api.getBlacklist(searchQuery),
  });

  const saveMutation = useMutation({
    mutationFn: () =>
      editingRecord ? api.updateBlacklist(editingRecord.id, formState) : api.createBlacklist(formState),
    onSuccess: async () => {
      setFormState(emptyForm);
      setEditingRecord(null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["blacklist"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
      ]);
    },
  });

  const deleteMutation = useMutation({
    mutationFn: () => api.deleteBlacklist(deletingId!),
    onSuccess: async () => {
      setDeletingId(null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["blacklist"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
      ]);
    },
  });

  const records = blacklistQuery.data ?? [];

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSearchQuery(query.trim());
  }

  return (
    <>
      <ConfirmModal
        open={Boolean(deletingId)}
        title="删除黑名单记录"
        description="该操作会将车辆从内部黑名单库移除，后续同车牌入场将不再命中黑名单。"
        confirmText="确认删除"
        busy={deleteMutation.isPending}
        onCancel={() => setDeletingId(null)}
        onConfirm={() => deleteMutation.mutate()}
      />

      <div className="grid gap-6 xl:grid-cols-[1.25fr_0.75fr]">
        <Panel
          title="黑名单车辆库"
          eyebrow="车辆风控库"
          action={
            <form onSubmit={handleSearch} className="flex w-full max-w-lg items-center gap-3">
              <label className="relative min-w-0 flex-1">
                <Search className="pointer-events-none absolute left-4 top-1/2 size-4 -translate-y-1/2 text-[var(--text-muted)]" />
                <input
                  value={query}
                  onChange={(event) => setQuery(event.target.value)}
                  className="w-full rounded-sm border border-[var(--border-soft)] bg-white py-2.5 pl-11 pr-4 text-sm text-[var(--text-primary)] outline-none placeholder:text-[var(--text-muted)] focus:border-sky-400/40"
                  placeholder="搜索车牌、原因或操作员"
                />
              </label>
              <button
                type="submit"
                className="inline-flex shrink-0 items-center justify-center gap-2 rounded-sm bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-blue-500"
              >
                <Search className="size-4" />
                查询
              </button>
            </form>
          }
        >
          <div className="overflow-hidden rounded-sm border border-[var(--border-soft)]">
            <div className="grid grid-cols-[1.1fr_0.7fr_1.4fr_0.8fr_0.8fr] gap-3 bg-slate-100 px-5 py-4 text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">
              <span>车牌号码</span>
              <span>风险等级</span>
              <span>原因</span>
              <span>操作员</span>
              <span className="text-right">管理</span>
            </div>
            <div className="divide-y divide-[var(--border-soft)]">
              {records.map((record) => (
                <div key={record.id} className="grid grid-cols-[1.1fr_0.7fr_1.4fr_0.8fr_0.8fr] gap-3 px-5 py-4 text-sm">
                  <div>
                    <p className="font-mono font-semibold text-[var(--text-primary)]">{record.plate}</p>
                    <p className="mt-1 text-xs text-[var(--text-secondary)]">{record.effectiveDate}</p>
                  </div>
                  <div>
                    <StatusBadge value={record.level} kind="level" />
                  </div>
                  <p className="text-[var(--text-secondary)]">{record.reason}</p>
                  <p className="text-[var(--text-secondary)]">{record.operator}</p>
                  <div className="flex justify-end gap-2">
                    <button
                      type="button"
                      disabled={!canManage}
                      onClick={() => {
                        setEditingRecord(record);
                        setFormState({
                          plate: record.plate,
                          reason: record.reason,
                          level: record.level,
                          operator: record.operator,
                          active: record.active,
                        });
                      }}
                      className="rounded-sm border border-[var(--border-soft)] p-2 text-[var(--text-secondary)] transition hover:border-[var(--border-strong)] hover:text-[var(--text-primary)] disabled:opacity-40"
                    >
                      <PencilLine className="size-4" />
                    </button>
                    <button
                      type="button"
                      disabled={!canManage}
                      onClick={() => setDeletingId(record.id)}
                      className="rounded-sm border border-rose-200 p-2 text-rose-600 transition hover:bg-rose-50 disabled:opacity-40"
                    >
                      <Trash2 className="size-4" />
                    </button>
                  </div>
                </div>
              ))}
              {records.length === 0 ? (
                <div className="px-5 py-10 text-center text-sm text-[var(--text-secondary)]">没有匹配到黑名单记录。</div>
              ) : null}
            </div>
          </div>
        </Panel>

        <Panel
          title={editingRecord ? "编辑黑名单记录" : "新增黑名单记录"}
          eyebrow="维护能力"
          action={
            canManage ? (
              <button
                type="button"
                onClick={() => {
                  setEditingRecord(null);
                  setFormState(emptyForm);
                }}
                className="inline-flex items-center gap-2 rounded-sm border border-[var(--border-soft)] px-3 py-2 text-xs text-[var(--text-secondary)] transition hover:border-[var(--border-strong)] hover:text-[var(--text-primary)]"
              >
                <Plus className="size-3.5" />
                新建
              </button>
            ) : null
          }
        >
          <form
            className="space-y-4"
            onSubmit={(event) => {
              event.preventDefault();
              saveMutation.mutate();
            }}
          >
            {[
              { key: "plate", label: "车牌号码", type: "text" },
              { key: "reason", label: "封禁原因", type: "text" },
              { key: "operator", label: "操作员", type: "text" },
            ].map((field) => (
              <label key={field.key} className="block">
                <span className="mb-2 block text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">{field.label}</span>
                <input
                  type={field.type}
                  value={formState[field.key as keyof BlacklistPayload] as string}
                  disabled={!canManage}
                  onChange={(event) =>
                    setFormState((current) => ({
                      ...current,
                      [field.key]: event.target.value,
                    }))
                  }
                  className="w-full rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none focus:border-sky-400/40 disabled:opacity-60"
                />
              </label>
            ))}

            <label className="block">
              <span className="mb-2 block text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">风险等级</span>
              <select
                value={formState.level}
                disabled={!canManage}
                onChange={(event) =>
                  setFormState((current) => ({ ...current, level: event.target.value as BlacklistPayload["level"] }))
                }
                className="w-full rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none focus:border-sky-400/40 disabled:opacity-60"
              >
                <option value="LOW">{levelLabel("LOW")}</option>
                <option value="MEDIUM">{levelLabel("MEDIUM")}</option>
                <option value="HIGH">{levelLabel("HIGH")}</option>
                <option value="CRITICAL">{levelLabel("CRITICAL")}</option>
              </select>
            </label>

            <label className="flex items-center gap-3 rounded-sm border border-[var(--border-soft)] bg-slate-50 px-4 py-3">
              <input
                type="checkbox"
                checked={formState.active}
                disabled={!canManage}
                onChange={(event) => setFormState((current) => ({ ...current, active: event.target.checked }))}
              />
              <span className="text-sm text-[var(--text-secondary)]">记录保持激活，入场时用于黑名单命中核验</span>
            </label>

            <button
              type="submit"
              disabled={!canManage || saveMutation.isPending}
              className="w-full rounded-sm bg-[var(--brand)] px-4 py-3 text-sm font-semibold text-white transition hover:brightness-110 disabled:opacity-60"
            >
              {saveMutation.isPending ? "提交中..." : editingRecord ? "更新记录" : "新增记录"}
            </button>
          </form>
        </Panel>
      </div>
    </>
  );
}
