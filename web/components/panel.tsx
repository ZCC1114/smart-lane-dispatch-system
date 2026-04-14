import { cn } from "@/lib/utils";

export function Panel({
  title,
  eyebrow,
  action,
  className,
  children,
}: {
  title: string;
  eyebrow?: string;
  action?: React.ReactNode;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <section className={cn("panel-surface rounded-sm p-6", className)}>
      <header className="mb-4 flex items-start justify-between gap-4">
        <div>
          {eyebrow ? (
            <p className="mb-1 text-[11px] font-bold uppercase tracking-[0.24em] text-[var(--text-muted)]">{eyebrow}</p>
          ) : null}
          <h3 className="text-lg font-semibold text-[var(--text-primary)]">{title}</h3>
        </div>
        {action}
      </header>
      {children}
    </section>
  );
}
