import type { SignalState } from "@/lib/types";
import { cn, signalLabel } from "@/lib/utils";

const lightColor: Record<SignalState, string> = {
  RED: "bg-rose-500 shadow-[0_0_18px_rgba(244,63,94,0.5)]",
  GREEN: "bg-emerald-500 shadow-[0_0_18px_rgba(16,185,129,0.45)]",
  OFFLINE: "bg-slate-700",
};

export function SignalStack({
  entrySignal,
  exitSignal,
  compact = false,
}: {
  entrySignal: SignalState;
  exitSignal: SignalState;
  compact?: boolean;
}) {
  return (
    <div className={cn("flex gap-4", compact ? "items-center" : "flex-col md:flex-row")}>
      {[
        { label: "入口", value: entrySignal },
        { label: "出口", value: exitSignal },
      ].map((item) => (
        <div key={item.label} className="rounded-sm border border-[var(--border-soft)] bg-[var(--bg-panel-strong)] p-3 shadow-sm">
          <p className="mb-2 text-[10px] font-semibold uppercase tracking-[0.28em] text-[var(--text-muted)]">{item.label}</p>
          <div className={cn("flex rounded-sm border border-slate-200 bg-slate-50 p-3", compact ? "gap-2" : "flex-col gap-3")}>
            {(["RED", "GREEN"] as SignalState[]).map((signal) => (
              <div
                key={signal}
                className={cn(
                  "size-5 rounded-full border border-white/10 transition-all",
                  item.value === signal ? lightColor[signal] : "bg-slate-200",
                )}
              />
            ))}
          </div>
          <p className="mt-3 text-xs text-[var(--text-secondary)]">{signalLabel(item.value)}</p>
        </div>
      ))}
    </div>
  );
}
