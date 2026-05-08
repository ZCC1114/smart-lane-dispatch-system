"use client";

import { type FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { PencilLine, Plus, Search, Trash2 } from "lucide-react";
import { ConfirmModal } from "@/components/confirm-modal";
import { FilterSelect } from "@/components/filter-select";
import { Panel } from "@/components/panel";
import { StatusBadge } from "@/components/status-badge";
import { api } from "@/lib/api";
import { canAccessBlacklist } from "@/lib/permissions";
import type { BlacklistPayload, BlacklistRecord } from "@/lib/types";
import { formatDateTime, formatPlateDisplay, levelLabel } from "@/lib/utils";
import { useAuthStore } from "@/stores/auth-store";

type BlacklistFormState = Pick<BlacklistPayload, "plate" | "reason" | "level">;
type BlacklistFormErrors = Partial<Record<keyof BlacklistFormState, string>>;

const emptyForm: BlacklistFormState = {
  plate: "",
  reason: "",
  level: "LOW",
};

export default function BlacklistPage() {
  const currentUser = useAuthStore((state) => state.user);
  const role = currentUser?.role;
  const canManage = canAccessBlacklist(role);
  const queryClient = useQueryClient();
  const [query, setQuery] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [formState, setFormState] = useState<BlacklistFormState>(emptyForm);
  const [formErrors, setFormErrors] = useState<BlacklistFormErrors>({});
  const [editingRecord, setEditingRecord] = useState<BlacklistRecord | null>(null);
  const [editorOpen, setEditorOpen] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const operatorName = currentUser?.displayName || currentUser?.username || "系统管理员";

  const blacklistQuery = useQuery({
    queryKey: ["blacklist", searchQuery],
    queryFn: () => api.getBlacklist(searchQuery),
  });

  const saveMutation = useMutation({
    mutationFn: () => {
      const payload: BlacklistPayload = {
        plate: formState.plate.trim(),
        reason: formState.reason.trim(),
        level: formState.level,
        operator: operatorName,
        active: editingRecord?.active ?? true,
      };
      return editingRecord ? api.updateBlacklist(editingRecord.id, payload) : api.createBlacklist(payload);
    },
    onSuccess: async () => {
      setFormState(emptyForm);
      setFormErrors({});
      setEditingRecord(null);
      setEditorOpen(false);
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

  function openCreateEditor() {
    setEditingRecord(null);
    setFormState(emptyForm);
    setFormErrors({});
    saveMutation.reset();
    setEditorOpen(true);
  }

  function openEditEditor(record: BlacklistRecord) {
    setEditingRecord(record);
    setFormState({
      plate: record.plate,
      reason: record.reason,
      level: record.level,
    });
    setFormErrors({});
    saveMutation.reset();
    setEditorOpen(true);
  }

  function closeEditor() {
    setEditorOpen(false);
    setEditingRecord(null);
    setFormState(emptyForm);
    setFormErrors({});
    saveMutation.reset();
  }

  function validateForm() {
    const nextErrors: BlacklistFormErrors = {};
    if (!formState.plate.trim()) {
      nextErrors.plate = "请输入车牌号码";
    }
    if (!formState.reason.trim()) {
      nextErrors.reason = "请输入封禁原因";
    }
    setFormErrors(nextErrors);
    return Object.keys(nextErrors).length === 0;
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

      {editorOpen ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/18 p-4 backdrop-blur-sm">
          <div className="panel-surface w-full max-w-lg rounded-sm p-6">
            <p className="text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">黑名单维护</p>
            <h2 className="mt-3 text-2xl font-semibold text-[var(--text-primary)]">
              {editingRecord ? "编辑黑名单记录" : "新增黑名单记录"}
            </h2>
            <p className="mt-3 text-sm leading-6 text-[var(--text-secondary)]">只需填写车牌号码、封禁原因和风险等级。</p>

            <form
              className="mt-6 space-y-4"
              onSubmit={(event) => {
                event.preventDefault();
                if (!validateForm()) {
                  return;
                }
                saveMutation.mutate();
              }}
            >
              <label className="block">
                <span className="mb-2 block text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">车牌号码</span>
                <input
                  type="text"
                  required
                  value={formState.plate}
                  disabled={!canManage}
                  onChange={(event) =>
                    {
                      setFormState((current) => ({
                        ...current,
                        plate: event.target.value,
                      }));
                      setFormErrors((current) => ({ ...current, plate: undefined }));
                    }
                  }
                  className="w-full rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none focus:border-sky-400/40 disabled:opacity-60"
                />
                {formErrors.plate ? <p className="mt-2 text-sm text-rose-600">{formErrors.plate}</p> : null}
              </label>

              <label className="block">
                <span className="mb-2 block text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">封禁原因</span>
                <textarea
                  required
                  value={formState.reason}
                  disabled={!canManage}
                  rows={4}
                  onChange={(event) =>
                    {
                      setFormState((current) => ({
                        ...current,
                        reason: event.target.value,
                      }));
                      setFormErrors((current) => ({ ...current, reason: undefined }));
                    }
                  }
                  className="w-full resize-none rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none focus:border-sky-400/40 disabled:opacity-60"
                />
                {formErrors.reason ? <p className="mt-2 text-sm text-rose-600">{formErrors.reason}</p> : null}
              </label>

              <label className="block">
                <span className="mb-2 block text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">风险等级</span>
                <FilterSelect
                  value={formState.level}
                  disabled={!canManage}
                  onChange={(nextLevel) =>
                    setFormState((current) => ({ ...current, level: nextLevel as BlacklistPayload["level"] }))
                  }
                  options={[
                    { value: "LOW", label: levelLabel("LOW") },
                    { value: "MEDIUM", label: levelLabel("MEDIUM") },
                    { value: "HIGH", label: levelLabel("HIGH") },
                    { value: "CRITICAL", label: levelLabel("CRITICAL") },
                  ]}
                />
              </label>

              {saveMutation.isError ? (
                <div className="rounded-sm border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
                  {saveMutation.error instanceof Error ? saveMutation.error.message : "新增黑名单记录失败"}
                </div>
              ) : null}

              <div className="flex justify-end gap-3">
                <button
                  type="button"
                  onClick={closeEditor}
                  className="rounded-sm border border-[var(--border-soft)] px-4 py-2 text-sm text-[var(--text-secondary)] transition hover:border-[var(--border-strong)] hover:text-[var(--text-primary)]"
                >
                  取消
                </button>
                <button
                  type="submit"
                  disabled={!canManage || saveMutation.isPending}
                  className="rounded-sm bg-[var(--brand)] px-4 py-2 text-sm font-semibold text-white transition hover:brightness-110 disabled:opacity-60"
                >
                  {saveMutation.isPending ? "提交中..." : editingRecord ? "更新记录" : "新增记录"}
                </button>
              </div>
            </form>
          </div>
        </div>
      ) : null}

      <div>
        <Panel
          title="黑名单车辆库"
          eyebrow="车辆风控库"
          action={
            <div className="flex w-full flex-col gap-3 xl:max-w-4xl xl:flex-row xl:items-center xl:justify-end">
              <form onSubmit={handleSearch} className="flex w-full items-center gap-3 xl:max-w-lg">
                <label className="relative min-w-0 flex-1">
                  <Search className="pointer-events-none absolute left-4 top-1/2 size-4 -translate-y-1/2 text-[var(--text-muted)]" />
                  <input
                    value={query}
                    onChange={(event) => setQuery(event.target.value)}
                    className="w-full rounded-sm border border-[var(--border-soft)] bg-white py-2.5 pl-11 pr-4 text-sm text-[var(--text-primary)] outline-none placeholder:text-[var(--text-muted)] focus:border-sky-400/40"
                    placeholder="搜索车牌、原因"
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
              {canManage ? (
                <button
                  type="button"
                  onClick={openCreateEditor}
                  className="inline-flex shrink-0 items-center justify-center gap-2 rounded-sm bg-[var(--brand)] px-4 py-2.5 text-sm font-semibold text-white transition hover:brightness-110"
                >
                  <Plus className="size-4" />
                  新增黑名单
                </button>
              ) : null}
            </div>
          }
        >
          <div className="overflow-hidden rounded-sm border border-[var(--border-soft)]">
            <div className="grid grid-cols-[0.45fr_0.95fr_0.7fr_1.2fr_0.8fr_0.8fr_1fr] gap-3 bg-slate-100 px-5 py-4 text-[12px] font-bold uppercase tracking-[0.18em] text-slate-600">
              <span>序号</span>
              <span>车牌号码</span>
              <span>风险等级</span>
              <span>原因</span>
              <span>操作员</span>
              <span className="text-right">管理</span>
              <span>时间</span>
            </div>
            <div className="divide-y divide-[var(--border-soft)]">
              {records.map((record, index) => (
                <div key={record.id} className="grid grid-cols-[0.45fr_0.95fr_0.7fr_1.2fr_0.8fr_0.8fr_1fr] gap-3 px-5 py-4 text-sm">
                  <span className="font-mono text-[var(--text-secondary)]">{index + 1}</span>
                  <span className="font-mono font-semibold text-[var(--text-primary)]">{formatPlateDisplay(record.plate) || record.plate}</span>
                  <div>
                    <StatusBadge value={record.level} kind="level" />
                  </div>
                  <p className="text-[var(--text-secondary)]">{record.reason}</p>
                  <p className="text-[var(--text-secondary)]">{record.operator}</p>
                  <div className="flex justify-end gap-2">
                    <button
                      type="button"
                      disabled={!canManage}
                      onClick={() => openEditEditor(record)}
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
                  <span className="text-[var(--text-secondary)]">{formatDateTime(record.effectiveDate)}</span>
                </div>
              ))}
              {records.length === 0 ? (
                <div className="px-5 py-10 text-center text-sm text-[var(--text-secondary)]">没有匹配到黑名单记录。</div>
              ) : null}
            </div>
          </div>
        </Panel>
      </div>
    </>
  );
}
