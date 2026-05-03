"use client";

import { useEffect } from "react";
import { usePathname, useRouter } from "next/navigation";
import { LiveUpdatesBridge } from "@/components/live-updates-bridge";
import { Sidebar } from "@/components/sidebar";
import { Topbar } from "@/components/topbar";
import { canViewPath } from "@/lib/permissions";
import { useAuthStore } from "@/stores/auth-store";

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const token = useAuthStore((state) => state.token);
  const user = useAuthStore((state) => state.user);
  const isOverviewPage = pathname === "/";

  useEffect(() => {
    if (!token) {
      router.replace("/login");
      return;
    }

    if (!canViewPath(user?.role, pathname)) {
      router.replace("/");
    }
  }, [pathname, router, token, user?.role]);

  if (!token) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <div className="border border-[var(--border-soft)] bg-[var(--bg-panel)] px-5 py-3 text-sm text-[var(--text-secondary)]">
          正在校验登录状态...
        </div>
      </div>
    );
  }

  return (
    <>
      <LiveUpdatesBridge />
      <div className="min-h-screen lg:flex">
        <Sidebar />
        <div className="flex min-h-screen flex-1 flex-col">
          {isOverviewPage ? null : <Topbar />}
          <main className={isOverviewPage ? "flex-1 overflow-hidden bg-[#06182d]" : "flex-1 bg-[var(--bg-canvas)] px-4 py-5 sm:px-6"}>{children}</main>
        </div>
      </div>
    </>
  );
}
