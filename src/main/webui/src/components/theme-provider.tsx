import { useEffect, useMemo, useState, type ReactNode } from "react"

import { ThemeContext, themeStorageKey, type Theme } from "@/lib/theme"

const media = window.matchMedia("(prefers-color-scheme: dark)")

function storedTheme(): Theme {
  try {
    const value = localStorage.getItem(themeStorageKey)
    return value === "light" || value === "dark" ? value : "system"
  } catch {
    return "system"
  }
}

/** Light/dark mode via the "dark" class on <html>; index.html applies the stored theme before React starts. */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<Theme>(storedTheme)
  const [systemDark, setSystemDark] = useState(media.matches)

  useEffect(() => {
    const onChange = (event: MediaQueryListEvent) => setSystemDark(event.matches)
    media.addEventListener("change", onChange)
    return () => media.removeEventListener("change", onChange)
  }, [])

  const resolvedTheme = theme === "system" ? (systemDark ? "dark" : "light") : theme

  useEffect(() => {
    document.documentElement.classList.toggle("dark", resolvedTheme === "dark")
    document.documentElement.style.colorScheme = resolvedTheme
  }, [resolvedTheme])

  const value = useMemo(
    () => ({
      theme,
      resolvedTheme,
      setTheme: (next: Theme) => {
        setThemeState(next)
        try {
          if (next === "system") {
            localStorage.removeItem(themeStorageKey)
          } else {
            localStorage.setItem(themeStorageKey, next)
          }
        } catch {
          // storage unavailable, keep the choice for this session only
        }
      },
    }),
    [theme, resolvedTheme],
  )

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}
