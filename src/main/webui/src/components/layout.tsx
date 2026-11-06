import { useEffect } from "react"
import { useTranslation } from "react-i18next"
import { NavLink, Outlet, useLocation } from "react-router"

import { AppSidebar } from "@/components/app-sidebar"
import { BottomNav } from "@/components/bottom-nav"
import { BrokerAlert } from "@/components/broker-alert"
import { LanguageSwitcher } from "@/components/language-switcher"
import { Logo } from "@/components/logo"
import { isActive, MORE_PATH, navItems } from "@/components/nav"
import { ThemeToggle } from "@/components/theme-toggle"
import { Separator } from "@/components/ui/separator"
import { SidebarInset, SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar"
import { useIsMobile } from "@/hooks/use-mobile"
import { cn } from "@/lib/utils"

function usePageTitle() {
  const { t } = useTranslation()
  const { pathname } = useLocation()
  if (pathname === MORE_PATH) {
    return t("nav.more")
  }
  const match = navItems.find((item) => isActive(item, pathname))
  return match ? t(match.titleKey) : ""
}

export function Layout() {
  const title = usePageTitle()
  // on a phone the floating bar at the bottom replaces the sidebar; language and theme move to "More"
  const phone = useIsMobile()

  useEffect(() => {
    document.title = title ? `${title} · mosquitto-ui` : "mosquitto-ui"
  }, [title])

  return (
    <SidebarProvider>
      {!phone && <AppSidebar />}
      <SidebarInset className="min-w-0">
        <header className="sticky top-0 z-10 flex h-14 shrink-0 items-center gap-2 border-b bg-background/80 px-4 backdrop-blur">
          {phone ? (
            <NavLink to="/" aria-label="mosquitto-ui" className="-ml-1 mr-1">
              <Logo className="size-7" />
            </NavLink>
          ) : (
            <>
              <SidebarTrigger className="-ml-1" />
              <Separator orientation="vertical" className="mr-2 data-vertical:h-4 data-vertical:self-center" />
            </>
          )}
          <h1 className="text-sm font-medium">{title}</h1>
          {!phone && (
            <div className="ml-auto flex items-center gap-1">
              <LanguageSwitcher />
              <ThemeToggle />
            </div>
          )}
        </header>
        {/* room at the bottom on a phone, or the end of the page would lie under the bar */}
        <main className={cn("min-w-0 flex-1 p-4 md:p-6", phone && "pb-28")}>
          <BrokerAlert />
          <Outlet />
        </main>
        {phone && <BottomNav />}
      </SidebarInset>
    </SidebarProvider>
  )
}
