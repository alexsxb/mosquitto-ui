import { MoonIcon, SunIcon } from "lucide-react"
import { useTheme } from "@/lib/theme"
import { useTranslation } from "react-i18next"

import { Button } from "@/components/ui/button"

export function ThemeToggle() {
  const { t } = useTranslation()
  const { resolvedTheme, setTheme } = useTheme()
  const dark = resolvedTheme === "dark"

  return (
    <Button variant="ghost" size="icon" aria-label={t("header.toggleTheme")} onClick={() => setTheme(dark ? "light" : "dark")}>
      {dark ? <SunIcon /> : <MoonIcon />}
    </Button>
  )
}
