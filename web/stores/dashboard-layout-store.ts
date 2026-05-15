"use client";

import { create } from "zustand";

type DashboardLayoutState = {
  overviewExpanded: boolean;
  setOverviewExpanded: (expanded: boolean) => void;
  toggleOverviewExpanded: () => void;
};

export const useDashboardLayoutStore = create<DashboardLayoutState>()((set) => ({
  overviewExpanded: false,
  setOverviewExpanded: (overviewExpanded) => set({ overviewExpanded }),
  toggleOverviewExpanded: () => set((state) => ({ overviewExpanded: !state.overviewExpanded })),
}));
