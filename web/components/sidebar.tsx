"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import {
  Activity,
  ClipboardPenLine,
  ChevronRight,
  History,
  LayoutDashboard,
  LogOut,
  Settings2,
  Shield,
  TriangleAlert,
} from "lucide-react";
import { useAuthStore } from "@/stores/auth-store";
import { useDashboardLayoutStore } from "@/stores/dashboard-layout-store";
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
  { href: "/blacklist", label: "黑名单管理", icon: Shield, visible: canAccessBlacklist },
  { href: "/entries", label: "车辆流水", icon: History, visible: () => true },
  { href: "/vehicle-alerts", label: "车辆告警", icon: TriangleAlert, visible: () => true },
  { href: "/dispatch", label: "调度设置", icon: ClipboardPenLine, visible: canDispatch },
];

export function Sidebar() {
  const pathname = usePathname();
  const router = useRouter();
  const user = useAuthStore((state) => state.user);
  const clearSession = useAuthStore((state) => state.clearSession);
  const overviewExpanded = useDashboardLayoutStore((state) => state.overviewExpanded);
  const displayName = normalizeProfileText(user?.displayName);
  const station = normalizeProfileText(user?.station);
  const collapsed = pathname === "/" && overviewExpanded;

  return (
    <aside
      className={cn(
        "hidden shrink-0 overflow-hidden bg-[var(--bg-panel-strong)] transition-[width,opacity,border-color] duration-300 lg:flex lg:flex-col",
        collapsed ? "w-0 border-r-0 opacity-0" : "w-64 border-r border-[var(--border-soft)] opacity-100",
      )}
      aria-hidden={collapsed}
    >
      <div className="flex h-16 items-center border-b border-[var(--border-soft)] px-6">
        <Activity className="mr-3 size-6 text-blue-500" />
        <div>
          <h1 className="text-[15px] font-semibold tracking-tight text-[var(--text-primary)]">出租车智能调度系统</h1>
          <p className="mt-0.5 text-xs text-[var(--text-muted)]">调度控制台</p>
        </div>
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
