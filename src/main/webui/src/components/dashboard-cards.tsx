import { LogInIcon, LogOutIcon } from "lucide-react"
import type { ReactNode } from "react"
import { useTranslation } from "react-i18next"
import { Link } from "react-router"

import type { Schemas } from "@/api/client"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { formatShort } from "@/lib/format"

const MAX_ACTIVITY = 8

function Row({ title, detail, aside, to }: { title: ReactNode; detail?: string; aside: ReactNode; to?: string }) {
  return (
    <li className="flex items-start justify-between gap-3 text-sm">
      <div className="min-w-0">
        <div className="truncate">{to ? <Link to={to} className="hover:underline">{title}</Link> : title}</div>
        {detail && <div className="truncate text-xs text-muted-foreground">{detail}</div>}
      </div>
      <div className="shrink-0 text-right text-xs text-muted-foreground">{aside}</div>
    </li>
  )
}

/** Only the last connection of each client is known: what happened before that is gone, and short ones go unseen. */
export function ActivityCard({ clients }: { clients?: Schemas["ClientView"][] }) {
  const { t, i18n } = useTranslation()
  const events = (clients ?? [])
    .flatMap((client) => {
      const login = client.lastLogin
      if (!login || client.locked) {
        return []
      }
      const online = client.connections.length > 0
      return [{ name: client.textname || client.username, online, address: login.address, time: online ? login.connectedAt : login.lastSeen }]
    })
    .sort((a, b) => Date.parse(b.time) - Date.parse(a.time))
    .slice(0, MAX_ACTIVITY)

  return (
    <Card className="min-w-0">
      <CardHeader>
        <CardTitle>{t("dashboard.activity")}</CardTitle>
      </CardHeader>
      <CardContent>
        {events.length === 0 && <p className="text-sm text-muted-foreground">{t("dashboard.activityEmpty")}</p>}
        <ul className="space-y-2">
          {events.map((event) => (
            <Row
              key={event.name}
              title={
                <span className="flex items-center gap-1.5">
                  {event.online ? <LogInIcon className="size-3.5 shrink-0 text-primary" /> : <LogOutIcon className="size-3.5 shrink-0 text-muted-foreground" />}
                  <span className="truncate">{event.name}</span>
                </span>
              }
              detail={`${t(event.online ? "dashboard.activityConnected" : "dashboard.activityDisconnected")}, ${event.address}`}
              aside={formatShort(event.time, i18n.language)}
            />
          ))}
        </ul>
      </CardContent>
    </Card>
  )
}

export function UpcomingCard({ health, certificate }: { health?: Schemas["JobHealth"][]; certificate?: Schemas["CertificateView"] }) {
  const { t, i18n } = useTranslation()
  const info = certificate?.certificate
  const autoRenew = certificate?.sets.find((set) => set.active)?.settings.autoRenew
  const items = (health ?? []).flatMap((job) =>
    job.nextRun
      ? [
          {
            key: `backup-${job.job}`,
            to: "/backups",
            title: t("dashboard.nextBackup", { job: job.job }),
            detail: job.lastSuccess ? t("dashboard.lastSuccess", { date: formatShort(job.lastSuccess, i18n.language) }) : t("dashboard.neverSucceeded"),
            time: job.nextRun,
          },
        ]
      : [],
  )
  if (info) {
    items.push({
      key: "certificate",
      to: "/certificate",
      title: t(autoRenew ? "dashboard.renewal" : "dashboard.renewalManual"),
      detail: autoRenew ? info.subject : t("dashboard.renewalManualHint"),
      time: autoRenew ? info.renewalFrom : info.notAfter,
    })
  }
  if (items.length === 0) {
    return null
  }
  items.sort((a, b) => Date.parse(a.time) - Date.parse(b.time))

  return (
    <Card className="min-w-0">
      <CardHeader>
        <CardTitle>{t("dashboard.upcoming")}</CardTitle>
      </CardHeader>
      <CardContent>
        <ul className="space-y-2">
          {items.map((item) => (
            <Row key={item.key} to={item.to} title={item.title} detail={item.detail} aside={formatShort(item.time, i18n.language)} />
          ))}
        </ul>
      </CardContent>
    </Card>
  )
}

export function BridgesCard({ bridges }: { bridges?: Schemas["BridgeView"][] }) {
  const { t, i18n } = useTranslation()
  if (!bridges || bridges.length === 0) {
    return null
  }

  return (
    <Card className="min-w-0">
      <CardHeader>
        <CardTitle>{t("nav.bridges")}</CardTitle>
      </CardHeader>
      <CardContent>
        <ul className="space-y-2">
          {bridges.map((view) => (
            <Row
              key={view.bridge.name}
              to="/bridges"
              title={view.bridge.name}
              detail={view.stateSince ? t("dashboard.since", { date: formatShort(view.stateSince, i18n.language) }) : view.bridge.address}
              aside={
                view.bridge.disabled ? (
                  <Badge variant="outline">{t("dashboard.bridgeDisabled")}</Badge>
                ) : view.connected === false ? (
                  <Badge variant="destructive">{t("dashboard.bridgeDown")}</Badge>
                ) : view.connected ? (
                  <Badge>{t("dashboard.bridgeUp")}</Badge>
                ) : (
                  "–"
                )
              }
            />
          ))}
        </ul>
      </CardContent>
    </Card>
  )
}
