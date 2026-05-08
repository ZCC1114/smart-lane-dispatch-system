"use client";

import { useEffect, useRef, useState } from "react";
import { Check, ChevronDown, type LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils";

export interface FilterSelectOption {
  value: string;
  label: string;
}

export function FilterSelect({
  value,
  options,
  onChange,
  icon: Icon,
  placeholder,
  disabled = false,
  size = "md",
  className,
}: {
  value: string;
  options: FilterSelectOption[];
  onChange: (value: string) => void;
  icon?: LucideIcon;
  placeholder?: string;
  disabled?: boolean;
  size?: "sm" | "md";
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const selectedOption = options.find((option) => option.value === value);
  const selectedLabel = selectedOption?.label ?? placeholder ?? "请选择";

  useEffect(() => {
    function handlePointerDown(event: MouseEvent) {
      if (!containerRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    }

    function handleEscape(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setOpen(false);
      }
    }

    document.addEventListener("mousedown", handlePointerDown);
    document.addEventListener("keydown", handleEscape);
    return () => {
      document.removeEventListener("mousedown", handlePointerDown);
      document.removeEventListener("keydown", handleEscape);
    };
  }, []);

  const triggerClassName =
    size === "sm"
      ? "h-8 gap-2 rounded-sm px-3 text-sm"
      : "h-12 gap-3 rounded-sm px-4 text-sm";
  const iconClassName = size === "sm" ? "size-3.5" : "size-4";
  const menuOffsetClassName = size === "sm" ? "top-10" : "top-14";

  return (
    <div ref={containerRef} className={cn("relative", className)}>
      <button
        type="button"
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
        className={cn(
          "w-full border border-[var(--border-soft)] bg-white text-left text-[var(--text-primary)] outline-none transition hover:border-[var(--border-strong)] focus:border-sky-400/40 disabled:cursor-not-allowed disabled:opacity-60",
          "inline-flex items-center justify-between shadow-[inset_0_1px_0_rgba(255,255,255,0.85)]",
          triggerClassName,
        )}
      >
        <span className="min-w-0 flex-1">
          <span className="flex items-center gap-3">
            {Icon ? <Icon className={cn("shrink-0 text-[var(--text-muted)]", iconClassName)} /> : null}
            <span className="truncate">{selectedLabel}</span>
          </span>
        </span>
        <ChevronDown className={cn("shrink-0 text-[var(--text-muted)] transition", iconClassName, open ? "rotate-180" : "")} />
      </button>

      {open ? (
        <div
          className={cn(
            "absolute left-0 z-30 w-full overflow-hidden rounded-sm border border-[var(--border-soft)] bg-white/95 shadow-[0_18px_48px_rgba(15,23,42,0.18)] backdrop-blur-sm",
            menuOffsetClassName,
          )}
        >
          <div className="max-h-72 overflow-y-auto py-1.5">
            {options.map((option) => {
              const active = option.value === value;
              return (
                <button
                  key={`${option.value}-${option.label}`}
                  type="button"
                  onClick={() => {
                    onChange(option.value);
                    setOpen(false);
                  }}
                  className={cn(
                    "flex w-full items-center justify-between gap-3 px-4 py-2.5 text-left text-sm transition",
                    active
                      ? "bg-sky-50 text-sky-700"
                      : "text-[var(--text-primary)] hover:bg-slate-50 hover:text-[var(--text-primary)]",
                  )}
                >
                  <span className="truncate">{option.label}</span>
                  {active ? <Check className={cn("shrink-0 text-sky-600", iconClassName)} /> : null}
                </button>
              );
            })}
          </div>
        </div>
      ) : null}
    </div>
  );
}
