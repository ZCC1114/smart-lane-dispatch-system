"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import {
  Activity,
  AlertTriangle,
  ClipboardPenLine,
  ChevronRight,
  History,
  LayoutDashboard,
  LogOut,
  Settings2,
  ShieldAlert,
} from "lucide-react";
import { useAuthStore } from "@/stores/auth-store";
import { canAccessBlacklist, canDispatch } from "@/lib/permissions";
import { cn } from "@/lib/utils";

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

const navItems = [
  { href: "/", label: "车道总览", icon: LayoutDashboard, visible: () => true },
  { href: "/signals", label: "信号灯控制", icon: Settings2, visible: canDispatch },
  { href: "/blacklist", label: "黑名单预警", icon: ShieldAlert, visible: canAccessBlacklist },
  { href: "/entries", label: "入场记录", icon: History, visible: () => true },
  { href: "/dispatch", label: "手动修正", icon: ClipboardPenLine, visible: canDispatch },
  { href: "/alerts", label: "预警中心", icon: AlertTriangle, visible: () => true },
];

export function Sidebar() {
  const pathname = usePathname();
  const router = useRouter();
  const user = useAuthStore((state) => state.user);
  const clearSession = useAuthStore((state) => state.clearSession);
  const displayName = normalizeProfileText(user?.displayName);
  const station = normalizeProfileText(user?.station);

  return (
    <aside className="hidden w-64 shrink-0 flex-col border-r border-[var(--border-soft)] bg-[var(--bg-panel-strong)] lg:flex">
      <div className="flex h-16 items-center border-b border-[var(--border-soft)] px-6">
        <Activity className="mr-3 size-6 text-blue-500" />
        <h1 className="text-lg font-bold tracking-tight text-[var(--text-primary)]">
          智行调度 <span className="text-blue-500">v2.1</span>
        </h1>
      </div>

      <nav className="flex-1 py-4">
        {navItems
          .filter((item) => item.visible(user?.role))
          .map((item) => {
            const active = pathname === item.href;
            return (
              <Link
                key={item.href}
                href={item.href}
                className={cn(
                  "group flex items-center border-l-4 px-6 py-3 text-sm transition-colors duration-200",
                  active
                    ? "border-blue-500 bg-blue-50 text-blue-700"
                    : "border-transparent text-slate-600 hover:bg-slate-100 hover:text-slate-900",
                )}
              >
                <item.icon className="size-4" />
                <span className="font-medium">{item.label}</span>
                <ChevronRight className={cn("ml-auto size-3 transition-opacity", active ? "opacity-100" : "opacity-0 group-hover:opacity-100")} />
              </Link>
            );
          })}
      </nav>

      <div className="border-t border-[var(--border-soft)] p-4">
        <div className="mb-3 px-2">
          <p className="text-sm font-semibold text-[var(--text-primary)]">{displayName ?? "未登录"}</p>
          <p className="mt-1 text-xs text-[var(--text-secondary)]">{station ?? "-"}</p>
        </div>
        <button
          type="button"
          onClick={() => {
            clearSession();
            router.replace("/login");
          }}
          className="flex w-full items-center px-4 py-2 text-sm text-[var(--text-secondary)] transition-colors hover:text-red-500"
        >
          <LogOut className="mr-3 size-4" />
          退出系统
        </button>
      </div>
    </aside>
  );
}
