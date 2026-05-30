import type { Metadata } from "next";
import { AppProviders } from "@/providers/app-providers";
import "./globals.css";

export const metadata: Metadata = {
  title: "无锡硕放机场出租车蓄车池排队管理系统",
  description: "无锡硕放机场出租车蓄车池排队与放行调度管理系统",
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
