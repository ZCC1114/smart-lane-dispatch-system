"use client";

import { ChevronLeft, ChevronRight } from "lucide-react";
import { FilterSelect } from "@/components/filter-select";

interface TablePaginationProps {
  page: number;
  pageSize: number;
  total: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (pageSize: number) => void;
}

const pageSizeOptions = [10, 20, 50, 100];

export function TablePagination({ page, pageSize, total, onPageChange, onPageSizeChange }: TablePaginationProps) {
  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  const currentPage = Math.min(Math.max(1, page), pageCount);
  const start = total === 0 ? 0 : (currentPage - 1) * pageSize + 1;
  const end = Math.min(total, currentPage * pageSize);

  return (
    <div className="flex flex-col gap-3 border-t border-[var(--border-soft)] bg-white px-5 py-4 text-sm text-[var(--text-secondary)] sm:flex-row sm:items-center sm:justify-between">
      <div className="flex items-center gap-3">
        <span>
          共 {total} 条，当前 {start}-{end} 条
        </span>
        <label className="flex items-center gap-2">
          <span>每页</span>
          <FilterSelect
            value={String(pageSize)}
            size="sm"
            className="w-[84px]"
            onChange={(value) => onPageSizeChange(Number(value))}
            options={pageSizeOptions.map((option) => ({ value: String(option), label: String(option) }))}
          />
          <span>条</span>
        </label>
      </div>

      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={() => onPageChange(currentPage - 1)}
          disabled={currentPage <= 1}
          className="inline-flex h-8 items-center gap-1 rounded-sm border border-[var(--border-soft)] px-3 text-[var(--text-secondary)] transition hover:border-[var(--border-strong)] hover:text-[var(--text-primary)] disabled:cursor-not-allowed disabled:opacity-45"
        >
          <ChevronLeft className="size-4" />
          上一页
        </button>
        <span className="min-w-[72px] text-center text-[var(--text-primary)]">
          {currentPage} / {pageCount}
        </span>
        <button
          type="button"
          onClick={() => onPageChange(currentPage + 1)}
          disabled={currentPage >= pageCount}
          className="inline-flex h-8 items-center gap-1 rounded-sm border border-[var(--border-soft)] px-3 text-[var(--text-secondary)] transition hover:border-[var(--border-strong)] hover:text-[var(--text-primary)] disabled:cursor-not-allowed disabled:opacity-45"
        >
          下一页
          <ChevronRight className="size-4" />
        </button>
      </div>
    </div>
  );
}
