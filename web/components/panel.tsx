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
    <section className={cn("panel-surface rounded-2xl p-6", className)}>
      <header className="mb-4 flex items-start justify-between gap-4">
        <div>
          {eyebrow ? (
            <p className="mb-1 text-xs font-medium text-[var(--text-muted)]">{eyebrow}</p>
          ) : null}
          <h3 className="text-xl font-semibold tracking-tight text-[var(--text-primary)]">{title}</h3>
        </div>
        {action}
      </header>
      {children}
    </section>
  );
}
