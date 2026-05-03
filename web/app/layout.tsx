import type { Metadata } from "next";
import { AppProviders } from "@/providers/app-providers";
import "./globals.css";

export const metadata: Metadata = {
  title: "出租车智能调度系统",
  description: "出租车蓄车道与放行调度管理系统",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN" className="h-full">
      <body className="min-h-full bg-[var(--bg-canvas)] text-[var(--text-primary)] antialiased">
        <AppProviders>{children}</AppProviders>
      </body>
    </html>
  );
}
