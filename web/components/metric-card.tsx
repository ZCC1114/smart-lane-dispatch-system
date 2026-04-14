import type { LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils";

export function MetricCard({
  icon: Icon,
  label,
  value,
  hint,
  accentClassName,
}: {
  icon: LucideIcon;
  label: string;
  value: string;
  hint: string;
  accentClassName: string;
}) {
  return (
    <div className="panel-surface rounded-sm p-5">
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-[11px] font-bold uppercase tracking-[0.24em] text-[var(--text-muted)]">{label}</p>
          <p className="mt-3 font-mono text-4xl leading-none text-[var(--text-primary)]">{value}</p>
          <p className="mt-2 text-xs font-semibold text-[var(--text-secondary)]">{hint}</p>
        </div>
        <div className={cn("rounded-sm p-3", accentClassName)}>
          <Icon className="size-5" />
        </div>
      </div>
    </div>
  );
}
