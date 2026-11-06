import { createContext, useContext } from "react"

export type Theme = "light" | "dark" | "system"

export const themeStorageKey = "mosquitto-ui.theme"

export interface ThemeState {
  theme: Theme
  resolvedTheme: "light" | "dark"
  setTheme: (theme: Theme) => void
}

export const ThemeContext = createContext<ThemeState | null>(null)

export function useTheme() {
  const state = useContext(ThemeContext)
  if (!state) {
    throw new Error("useTheme must be used within ThemeProvider")
  }
  return state
}
