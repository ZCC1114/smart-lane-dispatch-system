import { cn, laneModeLabel, laneStatusLabel, levelLabel, logStatusLabel } from "@/lib/utils";
import type { BlacklistLevel, LaneMode, LaneStatus, LogStatus } from "@/lib/types";

export function StatusBadge({
  value,
  kind,
}: {
  value: LaneStatus | LaneMode | LogStatus | BlacklistLevel;
  kind: "lane" | "mode" | "log" | "level";
}) {
  const styles =
    kind === "lane"
      ? {
          OPEN: "border-emerald-500/20 bg-emerald-500/10 text-emerald-300",
          BUSY: "border-amber-500/20 bg-amber-500/10 text-amber-700",
          FULL: "border-rose-500/20 bg-rose-500/10 text-rose-700",
          OFFLINE: "border-slate-400/30 bg-slate-100 text-slate-600",
        }
      : kind === "mode"
        ? {
            AUTO: "border-sky-500/20 bg-sky-500/10 text-sky-700",
            MANUAL: "border-violet-500/20 bg-violet-500/10 text-violet-700",
            OFFLINE: "border-slate-400/30 bg-slate-100 text-slate-600",
          }
        : kind === "log"
          ? {
              PASSED: "border-emerald-500/20 bg-emerald-500/10 text-emerald-700",
              REJECTED: "border-rose-500/20 bg-rose-500/10 text-rose-700",
              MANUAL: "border-amber-500/20 bg-amber-500/10 text-amber-700",
            }
          : {
              LOW: "border-slate-400/30 bg-slate-100 text-slate-600",
              MEDIUM: "border-amber-500/20 bg-amber-500/10 text-amber-700",
              HIGH: "border-orange-500/20 bg-orange-500/10 text-orange-700",
              CRITICAL: "border-rose-500/20 bg-rose-500/10 text-rose-700",
            };

  const label =
    kind === "lane"
      ? laneStatusLabel(value as LaneStatus)
      : kind === "mode"
        ? laneModeLabel(value as LaneMode)
        : kind === "log"
          ? logStatusLabel(value as LogStatus)
          : levelLabel(value as BlacklistLevel);

  return (
    <span
      className={cn(
        "inline-flex items-center rounded-sm border px-2.5 py-1 text-[10px] font-bold uppercase tracking-[0.18em]",
        styles[value as keyof typeof styles],
      )}
    >
      {label}
    </span>
  );
}
