"use client";

import { type FormEvent, useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { PencilLine, Plus, Search, Trash2, Upload } from "lucide-react";
import { ConfirmModal } from "@/components/confirm-modal";
import { Panel } from "@/components/panel";
import { TablePagination } from "@/components/table-pagination";
import { api } from "@/lib/api";
import { canAccessWhitelist } from "@/lib/permissions";
import { cn, formatDateTime, formatPlateDisplay } from "@/lib/utils";
import type { WhitelistImportProgress, WhitelistImportResult, WhitelistPayload, WhitelistRecord } from "@/lib/types";
import { useAuthStore } from "@/stores/auth-store";

const EXCEL_ACCEPT = ".xls,.xlsx,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
const RUNNING_IMPORT_STATUSES = new Set<WhitelistImportProgress["status"]>(["WAITING", "PARSING", "WRITING", "CACHING"]);

type WhitelistFormState = Pick<WhitelistPayload, "plate">;
type WhitelistFormErrors = Partial<Record<keyof WhitelistFormState, string>>;

const emptyForm: WhitelistFormState = {
  plate: "",
};

function isExcelFile(file: File) {
  const lowerName = file.name.toLowerCase();
  return lowerName.endsWith(".xls") || lowerName.endsWith(".xlsx");
}

function formatFileSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function createImportJobId() {
  return globalThis.crypto?.randomUUID?.() ?? `whitelist-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function initialImportProgress(jobId: string): WhitelistImportProgress {
  return {
    jobId,
    status: "WAITING",
    percent: 0,
    totalRows: 0,
    validRows: 0,
    importedPlates: 0,
    createdCount: 0,
    updatedCount: 0,
    duplicateRows: 0,
    invalidRows: 0,
    message: "等待服务端接收文件",
    startedAt: null,
    finishedAt: null,
  };
}

function completeImportProgress(jobId: string, result: WhitelistImportResult): WhitelistImportProgress {
  return {
    jobId,
    status: "DONE",
    percent: 100,
    totalRows: result.totalRows,
    validRows: result.validRows,
    importedPlates: result.importedPlates,
    createdCount: result.createdCount,
    updatedCount: result.updatedCount,
    duplicateRows: result.duplicateRows,
    invalidRows: result.invalidRows,
    message: "导入完成",
    startedAt: null,
    finishedAt: new Date().toISOString(),
  };
}

function failedImportProgress(jobId: string, message: string): WhitelistImportProgress {
  return {
    ...initialImportProgress(jobId),
    status: "FAILED",
    message,
    finishedAt: new Date().toISOString(),
  };
}

export default function WhitelistPage() {
  const currentUser = useAuthStore((state) => state.user);
  const canManage = canAccessWhitelist(currentUser?.role);
  const operatorName = currentUser?.displayName || currentUser?.username || "系统管理员";
  const queryClient = useQueryClient();
  const [query, setQuery] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [importDialogOpen, setImportDialogOpen] = useState(false);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState("");
  const [importJobId, setImportJobId] = useState<string | null>(null);
  const [importProgress, setImportProgress] = useState<WhitelistImportProgress | null>(null);
  const [importResult, setImportResult] = useState<WhitelistImportResult | null>(null);
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingRecord, setEditingRecord] = useState<WhitelistRecord | null>(null);
  const [formState, setFormState] = useState<WhitelistFormState>(emptyForm);
  const [formErrors, setFormErrors] = useState<WhitelistFormErrors>({});
  const [deletingId, setDeletingId] = useState<string | null>(null);

  const whitelistQuery = useQuery({
    queryKey: ["whitelist", searchQuery, page, pageSize],
    queryFn: () => api.getWhitelist({ query: searchQuery, page, pageSize }),
  });

  const settingsQuery = useQuery({
    queryKey: ["whitelist-settings"],
    queryFn: () => api.getWhitelistSettings(),
  });

  const saveMutation = useMutation({
    mutationFn: () => {
      const payload: WhitelistPayload = {
        plate: formState.plate.trim(),
      };
      return editingRecord ? api.updateWhitelist(editingRecord.id, payload) : api.createWhitelist(payload);
    },
    onSuccess: async () => {
      setFormState(emptyForm);
      setFormErrors({});
      setEditingRecord(null);
      setEditorOpen(false);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["whitelist"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
      ]);
    },
  });

  const deleteMutation = useMutation({
    mutationFn: () => api.deleteWhitelist(deletingId!),
    onSuccess: async () => {
      setDeletingId(null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["whitelist"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
      ]);
    },
  });

  const importMutation = useMutation({
    mutationFn: ({ file, jobId }: { file: File; jobId: string }) => api.importWhitelist(file, operatorName, jobId),
    onSuccess: async (result, variables) => {
      setImportResult(result);
      setImportProgress(completeImportProgress(variables.jobId, result));
      setPage(1);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["whitelist"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
      ]);
    },
    onError: (error) => {
      if (importJobId) {
        setImportProgress(failedImportProgress(importJobId, error instanceof Error ? error.message : "白名单导入失败"));
      }
    },
  });

  const settingsMutation = useMutation({
    mutationFn: (filterEnabled: boolean) => api.updateWhitelistSettings(filterEnabled),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["whitelist-settings"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
      ]);
    },
  });

  const recordsPage = whitelistQuery.data;
  const records = recordsPage?.items ?? [];
  const totalRecords = recordsPage?.total ?? 0;
  const currentPage = recordsPage?.page ?? page;
  const pageStartIndex = (currentPage - 1) * pageSize;
  const filterEnabled = settingsQuery.data?.filterEnabled ?? false;
  const importLocked = importMutation.isPending || RUNNING_IMPORT_STATUSES.has(importProgress?.status ?? "DONE");
  const progressPercent = Math.max(0, Math.min(100, importProgress?.percent ?? 0));

  useEffect(() => {
    if (!importDialogOpen || !importJobId || !importLocked) {
      return undefined;
    }

    const activeJobId = importJobId;
    let cancelled = false;

    async function pollProgress() {
      try {
        const progress = await api.getWhitelistImportProgress(activeJobId);
        if (!cancelled) {
          setImportProgress(progress);
        }
      } catch {
        // Polling should be silent while import is in progress.
      }
    }

    pollProgress();
    const timer = window.setInterval(pollProgress, 500);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [importDialogOpen, importJobId, importLocked]);

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPage(1);
    setSearchQuery(query.trim());
  }

  function openImportDialog() {
    setImportDialogOpen(true);
    setSelectedFile(null);
    setFileError("");
    setImportJobId(null);
    setImportProgress(null);
    setImportResult(null);
    importMutation.reset();
  }

  function closeImportDialog() {
    if (importLocked) {
      return;
    }
    setImportDialogOpen(false);
    setSelectedFile(null);
    setFileError("");
    setImportJobId(null);
    setImportProgress(null);
    setImportResult(null);
    importMutation.reset();
  }

  function handleFileChange(file: File | undefined) {
    setFileError("");
    setImportProgress(null);
    setImportResult(null);
    importMutation.reset();
    if (!file) {
      setSelectedFile(null);
      return;
    }
    if (!isExcelFile(file)) {
      setSelectedFile(null);
      setFileError("只支持选择 .xls 或 .xlsx 格式的 Excel 文件");
      return;
    }
    setSelectedFile(file);
  }

  function startImport() {
    if (!selectedFile) {
      setFileError("请先选择 Excel 文件");
      return;
    }
    if (!isExcelFile(selectedFile)) {
      setFileError("只支持选择 .xls 或 .xlsx 格式的 Excel 文件");
      return;
    }
    const jobId = createImportJobId();
    setImportJobId(jobId);
    setImportResult(null);
    setImportProgress(initialImportProgress(jobId));
    importMutation.mutate({ file: selectedFile, jobId });
  }

  function openCreateEditor() {
    setEditingRecord(null);
    setFormState(emptyForm);
    setFormErrors({});
    saveMutation.reset();
    setEditorOpen(true);
  }

  function openEditEditor(record: WhitelistRecord) {
    setEditingRecord(record);
    setFormState({
      plate: record.plate,
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
    const nextErrors: WhitelistFormErrors = {};
    if (!formState.plate.trim()) {
      nextErrors.plate = "请输入车牌号码";
    }
    setFormErrors(nextErrors);
    return Object.keys(nextErrors).length === 0;
  }

  return (
    <>
      <ConfirmModal
        open={Boolean(deletingId)}
        title="删除白名单记录"
        description="该操作会将车辆移出白名单，之后该车辆不再通过白名单检查。"
        confirmText="确认删除"
        busy={deleteMutation.isPending}
        onCancel={() => setDeletingId(null)}
        onConfirm={() => deleteMutation.mutate()}
      />

      {editorOpen ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/18 p-4 backdrop-blur-sm">
          <div className="panel-surface w-full max-w-md rounded-sm p-6">
            <p className="text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">白名单维护</p>
            <h2 className="mt-3 text-2xl font-semibold text-[var(--text-primary)]">
              {editingRecord ? "编辑白名单记录" : "新增白名单记录"}
            </h2>
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
                  onChange={(event) => {
                    setFormState((current) => ({
                      ...current,
                      plate: event.target.value,
                    }));
                    setFormErrors((current) => ({ ...current, plate: undefined }));
                  }}
                  className="w-full rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm text-[var(--text-primary)] outline-none focus:border-sky-400/40 disabled:opacity-60"
                />
                {formErrors.plate ? <p className="mt-2 text-sm text-rose-600">{formErrors.plate}</p> : null}
              </label>

              {saveMutation.isError ? (
                <div className="rounded-sm border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
                  {saveMutation.error instanceof Error ? saveMutation.error.message : "白名单记录保存失败"}
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

      {importDialogOpen ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm">
          <div className="panel-surface w-full max-w-xl rounded-sm p-6 shadow-2xl">
            <div className="mb-5 flex items-start justify-between gap-4">
              <div>
                <p className="text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">白名单导入</p>
                <h2 className="mt-2 text-2xl font-semibold text-[var(--text-primary)]">导入白名单 Excel</h2>
              </div>
              <button
                type="button"
                disabled={importLocked}
                onClick={closeImportDialog}
                className="rounded-sm border border-[var(--border-soft)] px-3 py-1.5 text-sm text-[var(--text-secondary)] transition hover:border-[var(--border-strong)] hover:text-[var(--text-primary)] disabled:cursor-not-allowed disabled:opacity-45"
              >
                关闭
              </button>
            </div>

            <div className="space-y-4">
              <label
                className={cn(
                  "flex min-h-24 cursor-pointer flex-col items-center justify-center rounded-sm border border-dashed border-[var(--border-soft)] bg-slate-50 px-4 py-5 text-center transition",
                  importLocked
                    ? "cursor-not-allowed opacity-70"
                    : "hover:border-sky-300 hover:bg-sky-50/40",
                )}
              >
                <Upload className="mb-2 size-6 text-blue-500" />
                <span className="text-sm font-semibold text-[var(--text-primary)]">选择 Excel 文件</span>
                <span className="mt-1 text-xs text-[var(--text-muted)]">仅支持 .xls / .xlsx</span>
                <input
                  type="file"
                  accept={EXCEL_ACCEPT}
                  disabled={importLocked}
                  className="hidden"
                  onChange={(event) => {
                    handleFileChange(event.target.files?.[0]);
                    event.target.value = "";
                  }}
                />
              </label>

              {selectedFile ? (
                <div className="rounded-sm border border-[var(--border-soft)] bg-white px-4 py-3 text-sm">
                  <p className="font-semibold text-[var(--text-primary)]">{selectedFile.name}</p>
                  <p className="mt-1 text-xs text-[var(--text-secondary)]">{formatFileSize(selectedFile.size)}</p>
                </div>
              ) : null}

              {importProgress ? (
                <div className="rounded-sm border border-blue-100 bg-blue-50 px-4 py-3">
                  <div className="mb-2 flex items-center justify-between text-sm">
                    <span className="font-semibold text-blue-800">{importProgress.message}</span>
                    <span className="font-mono text-blue-700">{progressPercent}%</span>
                  </div>
                  <div className="h-2 overflow-hidden rounded-full bg-white">
                    <div className="h-full rounded-full bg-blue-600 transition-all duration-300" style={{ width: `${progressPercent}%` }} />
                  </div>
                  <div className="mt-3 grid grid-cols-2 gap-2 text-xs text-blue-800 sm:grid-cols-4">
                    <span>总行数 {importProgress.totalRows}</span>
                    <span>有效 {importProgress.validRows}</span>
                    <span>新增 {importProgress.createdCount}</span>
                    <span>更新 {importProgress.updatedCount}</span>
                    <span>去重 {importProgress.duplicateRows}</span>
                    <span>无效 {importProgress.invalidRows}</span>
                    <span>导入 {importProgress.importedPlates}</span>
                    <span>{importProgress.status}</span>
                  </div>
                </div>
              ) : null}

              {importResult ? (
                <div className="rounded-sm border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">
                  导入完成：有效 {importResult.validRows} 行，新增 {importResult.createdCount} 条，更新 {importResult.updatedCount} 条，重复 {importResult.duplicateRows} 行，无效 {importResult.invalidRows} 行。
                </div>
              ) : null}

              {fileError || importMutation.isError ? (
                <div className="rounded-sm border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
                  {fileError || (importMutation.error instanceof Error ? importMutation.error.message : "白名单导入失败")}
                </div>
              ) : null}
            </div>

            <div className="mt-6 flex justify-end gap-3">
              <button
                type="button"
                disabled={importLocked}
                onClick={closeImportDialog}
                className="rounded-sm border border-[var(--border-soft)] px-4 py-2 text-sm text-[var(--text-secondary)] transition hover:border-[var(--border-strong)] hover:text-[var(--text-primary)] disabled:cursor-not-allowed disabled:opacity-45"
              >
                {importResult ? "完成" : "取消"}
              </button>
              <button
                type="button"
                disabled={!selectedFile || Boolean(fileError) || importLocked}
                onClick={startImport}
                className="inline-flex items-center gap-2 rounded-sm bg-[var(--brand)] px-4 py-2 text-sm font-semibold text-white transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-60"
              >
                <Upload className="size-4" />
                {importLocked ? "导入中..." : "开始导入"}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      <Panel
        title="白名单车辆库"
        eyebrow="总入口准入过滤"
        action={
          <div className="flex flex-wrap items-center justify-end gap-2">
            <button
              type="button"
              disabled={!canManage}
              onClick={openCreateEditor}
              className="inline-flex items-center gap-2 rounded-sm bg-[var(--brand)] px-4 py-2.5 text-sm font-semibold text-white transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-60"
            >
              <Plus className="size-4" />
              新增白名单
            </button>
            <button
              type="button"
              disabled={!canManage || settingsMutation.isPending || settingsQuery.isLoading}
              onClick={() => settingsMutation.mutate(!filterEnabled)}
              className={cn(
                "inline-flex items-center gap-2 rounded-sm px-4 py-2.5 text-sm font-semibold transition disabled:cursor-not-allowed disabled:opacity-60",
                filterEnabled
                  ? "bg-emerald-600 text-white hover:bg-emerald-500"
                  : "border border-[var(--border-soft)] text-[var(--text-secondary)] hover:border-[var(--border-strong)] hover:text-[var(--text-primary)]",
              )}
            >
              {filterEnabled ? "白名单过滤已启用" : "白名单过滤未启用"}
            </button>
            <button
              type="button"
              disabled={!canManage}
              onClick={openImportDialog}
              className="inline-flex items-center gap-2 rounded-sm bg-[var(--brand)] px-4 py-2.5 text-sm font-semibold transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-60"
            >
              <Upload className="size-4" />
              导入白名单
            </button>
          </div>
        }
      >
        <div className="mb-4">
          <form onSubmit={handleSearch} className="flex min-w-0 items-center gap-3 xl:max-w-3xl">
            <label className="relative min-w-0 flex-1">
              <Search className="pointer-events-none absolute left-4 top-1/2 size-4 -translate-y-1/2 text-[var(--text-muted)]" />
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                className="w-full rounded-sm border border-[var(--border-soft)] bg-white py-2.5 pl-11 pr-4 text-sm text-[var(--text-primary)] outline-none placeholder:text-[var(--text-muted)] focus:border-sky-400/40"
                placeholder="搜索车牌或操作人"
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
        </div>

        {settingsMutation.isError ? (
          <div className="mb-4 rounded-sm border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
            {settingsMutation.error instanceof Error ? settingsMutation.error.message : "白名单过滤状态更新失败"}
          </div>
        ) : null}

        <div className="overflow-hidden rounded-sm border border-[var(--border-soft)]">
          <div className="grid grid-cols-[0.45fr_1fr_1fr_1fr_0.9fr_0.9fr_0.75fr] gap-3 bg-slate-100 px-5 py-4 text-[12px] font-bold uppercase tracking-[0.18em] text-slate-600">
            <span>序号</span>
            <span>车牌号码</span>
            <span>创建时间</span>
            <span>更新时间</span>
            <span>创建人</span>
            <span>更新人</span>
            <span className="text-right">管理</span>
          </div>
          <div className="divide-y divide-[var(--border-soft)]">
            {records.map((record, index) => (
              <div key={record.id} className="grid grid-cols-[0.45fr_1fr_1fr_1fr_0.9fr_0.9fr_0.75fr] gap-3 px-5 py-4 text-sm">
                <span className="font-mono text-[var(--text-secondary)]">{pageStartIndex + index + 1}</span>
                <span className="font-mono font-semibold text-[var(--text-primary)]">{formatPlateDisplay(record.plate) || record.plate}</span>
                <span className="text-[var(--text-secondary)]">{formatDateTime(record.createdAt)}</span>
                <span className="text-[var(--text-secondary)]">{formatDateTime(record.updatedAt)}</span>
                <span className="text-[var(--text-secondary)]">{record.createdBy}</span>
                <span className="text-[var(--text-secondary)]">{record.updatedBy}</span>
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
              </div>
            ))}
            {whitelistQuery.isLoading ? (
              <div className="px-5 py-10 text-center text-sm text-[var(--text-secondary)]">正在加载白名单记录...</div>
            ) : null}
            {!whitelistQuery.isLoading && records.length === 0 ? (
              <div className="px-5 py-10 text-center text-sm text-[var(--text-secondary)]">没有匹配到白名单记录。</div>
            ) : null}
          </div>
          <TablePagination
            page={currentPage}
            pageSize={pageSize}
            total={totalRecords}
            onPageChange={setPage}
            onPageSizeChange={(nextPageSize) => {
              setPageSize(nextPageSize);
              setPage(1);
            }}
          />
        </div>
      </Panel>
    </>
  );
}
