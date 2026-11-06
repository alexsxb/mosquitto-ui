import { LogOutIcon } from "lucide-react"
import type { ReactNode } from "react"
import { useTranslation } from "react-i18next"

import { ApiError, SESSION_EXPIRED } from "@/api/client"
import { logout, useCurrentUser } from "@/auth/session"
import { Button } from "@/components/ui/button"
import { Spinner } from "@/components/ui/spinner"
import { LandingPage } from "@/pages/landing"

/** Renders children only for users with the admin role. Without a session this can only be the public start page. */
export function RequireAccess({ children }: { children: ReactNode }) {
  const { t } = useTranslation()
  const me = useCurrentUser()

  if (me.isPending) {
    return (
      <div className="flex h-svh items-center justify-center">
        <Spinner className="size-6" />
      </div>
    )
  }
  if (me.isError) {
    const error: unknown = me.error
    if (error instanceof ApiError && error.status === SESSION_EXPIRED) {
      return <LandingPage />
    }
    throw error
  }
  if (!me.data.authorized) {
    return (
      <div className="flex h-svh flex-col items-center justify-center gap-4 p-4 text-center">
        <h1 className="text-lg font-medium">{t("auth.noAccessTitle")}</h1>
        <p className="text-sm text-muted-foreground">{t("auth.noAccess", { name: me.data.username })}</p>
        <Button variant="outline" onClick={logout}>
          <LogOutIcon />
          {t("auth.logout")}
        </Button>
      </div>
    )
  }
  return children
}
