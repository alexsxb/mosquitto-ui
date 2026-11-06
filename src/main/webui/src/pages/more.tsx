import { BookOpenIcon, ChevronRightIcon, LogOutIcon, MonitorIcon, MoonIcon, SunIcon } from "lucide-react"
import { useTranslation } from "react-i18next"
import { Link } from "react-router"

import { $api } from "@/api/client"
import { handbookUrl, logout, useCurrentUser } from "@/auth/session"
import { barItems, visibleGroups } from "@/components/nav"
import { Button } from "@/components/ui/button"
import { languages } from "@/i18n"
import { useTheme, type Theme } from "@/lib/theme"

const THEMES: { value: Theme; icon: typeof SunIcon }[] = [
  { value: "light", icon: SunIcon },
  { value: "dark", icon: MoonIcon },
  { value: "system", icon: MonitorIcon },
]

const heading = "text-xs font-medium tracking-wide text-muted-foreground uppercase"

/**
 * "More" on a phone: every page that is not in the bar, in the groups of the sidebar. Below them language, appearance and
 * logout, which sit in the header and the user menu of the sidebar on a computer.
 */
export function MorePage() {
  const { t, i18n } = useTranslation()
  const { theme, setTheme } = useTheme()
  const me = useCurrentUser()
  const system = $api.useQuery("get", "/api/system/info", {}, { staleTime: Infinity })
  const groups = visibleGroups()
    .map((group) => ({ ...group, items: group.items.filter((item) => !barItems.includes(item)) }))
    .filter((group) => group.items.length > 0)
  const user = me.data

  return (
    <div className="mx-auto grid max-w-xl gap-6">
      {groups.map((group) => (
        <section key={group.titleKey} className="grid gap-1.5">
          <h2 className={heading}>{t(group.titleKey)}</h2>
          <ul className="divide-y rounded-xl border">
            {group.items.map((item) => (
              <li key={item.to}>
                <Link to={item.to} className="flex min-h-12 items-center gap-3 px-3 py-2 hover:bg-muted">
                  <item.icon className="size-5 shrink-0 text-muted-foreground" aria-hidden />
                  <span className="min-w-0 flex-1 truncate">{t(item.titleKey)}</span>
                  <ChevronRightIcon className="size-4 text-muted-foreground" aria-hidden />
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ))}
      <section className="grid gap-1.5">
        <h2 className={heading}>{t("more.account")}</h2>
        <div className="grid gap-3 rounded-xl border p-3">
          {user && (
            <div className="min-w-0">
              <div className="truncate font-medium">{user.name || user.username}</div>
              {user.email && <div className="truncate text-xs text-muted-foreground">{user.email}</div>}
            </div>
          )}
          <div className="grid grid-cols-3 gap-1" role="group" aria-label={t("header.language")}>
            {languages.map((lng) => (
              <Button
                key={lng}
                variant={i18n.resolvedLanguage === lng ? "secondary" : "outline"}
                aria-pressed={i18n.resolvedLanguage === lng}
                onClick={() => void i18n.changeLanguage(lng)}
              >
                {t(`languages.${lng}`)}
              </Button>
            ))}
          </div>
          <div className="grid grid-cols-3 gap-1" role="group" aria-label={t("more.appearance")}>
            {THEMES.map(({ value, icon: Icon }) => (
              <Button
                key={value}
                variant={theme === value ? "secondary" : "outline"}
                aria-pressed={theme === value}
                className="h-auto flex-col gap-1 py-2 text-xs"
                onClick={() => setTheme(value)}
              >
                <Icon /> {t(`more.${value}`)}
              </Button>
            ))}
          </div>
          <Button variant="outline" asChild>
            <a href={handbookUrl(i18n.resolvedLanguage)} target="_blank" rel="noopener">
              <BookOpenIcon /> {t("auth.handbook")}
            </a>
          </Button>
          <Button variant="outline" onClick={logout}>
            <LogOutIcon /> {t("auth.logout")}
          </Button>
          {system.data && <p className="text-xs text-muted-foreground tabular-nums">{t("more.version", { version: system.data.version })}</p>}
        </div>
      </section>
    </div>
  )
}
