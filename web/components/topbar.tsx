"use client";

import { Bell, Search } from "lucide-react";
import { useEffect, useState } from "react";
import { useAuthStore } from "@/stores/auth-store";

function normalizeProfileText(value?: string) {
  if (!value) {
    return value;
  }

  if (value === "Supervisor Root") {
    return "系统超级管理员";
  }

  if (value === "System Control") {
    return "总控中心";
  }

  return value;
}

export function Topbar() {
  const [now, setNow] = useState(() => new Date());
  const user = useAuthStore((state) => state.user);
  const displayName = normalizeProfileText(user?.displayName);
  const station = normalizeProfileText(user?.station);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  return (
    <header className="sticky top-0 z-20 flex h-16 flex-wrap items-center justify-between gap-4 border-b border-[var(--border-soft)] bg-white/85 px-4 shadow-sm backdrop-blur sm:px-6">
      <div className="relative max-w-md min-w-[260px] flex-1">
        <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-slate-400" />
        <input
          type="text"
          placeholder="搜索车道、车牌或黑名单..."
          className="w-full rounded-sm border border-[var(--border-soft)] bg-slate-50 py-2 pl-10 pr-4 text-sm text-[var(--text-primary)] outline-none transition placeholder:text-slate-400 focus:border-blue-400"
        />
      </div>
      <div className="flex items-center gap-4">
        <button
          type="button"
          className="relative rounded-sm bg-slate-100 p-2 text-slate-500 transition-colors hover:bg-slate-200 hover:text-slate-900"
        >
          <Bell className="size-5" />
          <span className="absolute right-2 top-2 size-2 rounded-full border border-white bg-red-500" />
        </button>
        <div className="hidden h-8 w-px bg-[var(--border-soft)] sm:block" />
        <div className="flex items-center gap-3 pl-2">
          <div className="hidden text-right sm:block">
            <p className="text-sm font-semibold leading-none text-[var(--text-primary)]">{displayName}</p>
            <p className="mt-1 text-xs text-[var(--text-secondary)]">
              {station} · {now.toLocaleTimeString("zh-CN", { hour12: false })}
            </p>
          </div>
          <div className="flex size-10 items-center justify-center rounded-full border border-blue-200 bg-blue-50">
            <span className="text-sm font-semibold text-blue-700">{displayName?.slice(0, 1) ?? "管"}</span>
          </div>
        </div>
      </div>
    </header>
  );
}
