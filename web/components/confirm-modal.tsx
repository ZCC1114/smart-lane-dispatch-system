"use client";

export function ConfirmModal({
  open,
  title,
  description,
  confirmText,
  cancelText = "取消",
  onConfirm,
  onCancel,
  busy = false,
}: {
  open: boolean;
  title: string;
  description: string;
  confirmText: string;
  cancelText?: string;
  onConfirm: () => void;
  onCancel: () => void;
  busy?: boolean;
}) {
  if (!open) {
    return null;
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/18 p-4 backdrop-blur-sm">
      <div className="panel-surface w-full max-w-md rounded-sm p-6">
        <p className="text-[11px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">操作确认</p>
        <h2 className="mt-3 text-2xl font-semibold text-[var(--text-primary)]">{title}</h2>
        <p className="mt-3 text-sm leading-6 text-[var(--text-secondary)]">{description}</p>
        <div className="mt-6 flex justify-end gap-3">
          <button
            type="button"
            onClick={onCancel}
            className="rounded-sm border border-[var(--border-soft)] px-4 py-2 text-sm text-[var(--text-secondary)] transition hover:border-[var(--border-strong)] hover:text-[var(--text-primary)]"
          >
            {cancelText}
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={onConfirm}
            className="rounded-sm bg-[var(--brand)] px-4 py-2 text-sm font-semibold text-white transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-70"
          >
            {busy ? "处理中..." : confirmText}
          </button>
        </div>
      </div>
    </div>
  );
}
