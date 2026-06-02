"use client";

import { create } from "zustand";
import { createJSONStorage, persist } from "zustand/middleware";
import type { AuthResponse, UserSession } from "@/lib/types";

type AuthState = {
  token: string | null;
  expiresAt: string | null;
  user: UserSession | null;
  hydrated: boolean;
  setSession: (payload: AuthResponse) => void;
  clearSession: () => void;
  markHydrated: () => void;
};

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      expiresAt: null,
      user: null,
      hydrated: false,
      setSession: ({ token, expiresAt, user }) => {
        set({ token, expiresAt, user });
      },
      clearSession: () => {
        set({ token: null, expiresAt: null, user: null });
      },
      markHydrated: () => {
        set({ hydrated: true });
      },
    }),
    {
      name: "smart-lane-auth-v2",
      storage: createJSONStorage(() => localStorage),
      partialize: (state) => ({
        token: state.token,
        expiresAt: state.expiresAt,
        user: state.user,
      }),
      onRehydrateStorage: () => (state) => {
        state?.markHydrated();
      },
    },
  ),
);
