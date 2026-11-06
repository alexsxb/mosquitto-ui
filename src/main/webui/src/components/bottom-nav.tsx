import { EllipsisIcon } from "lucide-react"
import { useEffect, useState } from "react"
import { useTranslation } from "react-i18next"
import { NavLink, useLocation } from "react-router"

import { barItems, isActive, MORE_PATH } from "@/components/nav"
import { cn } from "@/lib/utils"

/**
 * The floating bar on a phone instead of the sidebar: three pages and "More", within reach of the thumb, as a pill above the
 * content and clear of the gesture bar. While the keyboard is open it makes room.
 */
export function BottomNav() {
  const { t } = useTranslation()
  const { pathname } = useLocation()
  const typing = useTyping()
  if (typing) {
    return null
  }
  // every page that is not in the bar lives under "More"
  const onBar = barItems.some((item) => isActive(item, pathname))
  const linkClass = (active: boolean) =>
    cn(
      "relative flex min-w-0 flex-1 flex-col items-center gap-0.5 rounded-full px-0.5 py-1.5 text-[11px] font-medium outline-none focus-visible:ring-2 focus-visible:ring-ring",
      active ? "bg-primary/10 text-primary" : "text-muted-foreground",
    )

  return (
    <nav aria-label={t("nav.overview")} className="fixed inset-x-0 bottom-[max(0.75rem,env(safe-area-inset-bottom))] z-30 mx-auto w-[calc(100%-1.5rem)] max-w-sm">
      <div className="flex items-stretch justify-around rounded-full border bg-background/85 px-1.5 py-1 shadow-lg backdrop-blur-md">
        {barItems.map((item) => {
          const active = isActive(item, pathname)
          return (
            <NavLink key={item.to} to={item.to} className={linkClass(active)} aria-current={active ? "page" : undefined}>
              <item.icon className="size-6" aria-hidden />
              <span className="max-w-full truncate">{t(item.titleKey)}</span>
            </NavLink>
          )
        })}
        <NavLink to={MORE_PATH} className={linkClass(!onBar)} aria-current={!onBar ? "page" : undefined}>
          <EllipsisIcon className="size-6" aria-hidden />
          <span className="max-w-full truncate">{t("nav.more")}</span>
        </NavLink>
      </div>
    </nav>
  )
}

/** On a device with a finger a text field has the focus: the keyboard is open */
function useTyping(): boolean {
  const [typing, setTyping] = useState(false)
  useEffect(() => {
    if (!(window.matchMedia?.("(pointer: coarse)").matches ?? false)) {
      return
    }
    const isText = (el: EventTarget | null) => {
      if (!(el instanceof HTMLElement)) {
        return false
      }
      if (el.isContentEditable || el instanceof HTMLTextAreaElement) {
        return true
      }
      return el instanceof HTMLInputElement && !["checkbox", "radio", "button", "submit", "range", "file", "color"].includes(el.type)
    }
    const onIn = (e: FocusEvent) => isText(e.target) && setTyping(true)
    const onOut = (e: FocusEvent) => isText(e.target) && !isText(e.relatedTarget) && setTyping(false)
    document.addEventListener("focusin", onIn)
    document.addEventListener("focusout", onOut)
    return () => {
      document.removeEventListener("focusin", onIn)
      document.removeEventListener("focusout", onOut)
    }
  }, [])
  return typing
}
