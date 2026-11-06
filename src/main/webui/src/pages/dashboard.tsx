import { InfoIcon, TriangleAlertIcon } from "lucide-react"
import { useState } from "react"
import { useTranslation } from "react-i18next"
import { Link } from "react-router"

import { $api, type Schemas } from "@/api/client"
import { ActivityCard, BridgesCard, UpcomingCard } from "@/components/dashboard-cards"
import { LineChart } from "@/components/line-chart"
import { ListenersCard } from "@/components/listeners-card"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { useBrokerStatus } from "@/lib/broker"
import { formatBytes, formatDateTime } from "@/lib/format"

/** Without a page that shows what is behind the number, there is nothing to link to. */
function CountCard({ to, label, count }: { to?: string; label: string; count?: number | string }) {
  const card = (
    <Card className={to && "hover:bg-muted/50"}>
      <CardHeader>
        <CardDescription>{label}</CardDescription>
        <CardTitle className="text-2xl tabular-nums">{count ?? "–"}</CardTitle>
      </CardHeader>
    </Card>
  )
  return to ? <Link to={to}>{card}</Link> : card
}

const REFRESH_MS = 10000
const HISTORY_REFRESH_MS = 60000
const CHART_HEIGHT = 120
/** Three points missed: the application or the broker was away. */
const HISTORY_GAP_MS = 15 * 60 * 1000
const SECONDS_PER_DAY = 86400
const SECONDS_PER_HOUR = 3600
const SECONDS_PER_MINUTE = 60

type Sample = Schemas["StatsSample"]

interface HistoryChartProps {
  label: string
  value: string
  samples: Sample[]
  read: (sample: Sample) => number | null | undefined
  format: (value: number) => string
  binary?: boolean
  unit?: string
}

function HistoryChart({ label, value, samples, read, format, binary, unit }: HistoryChartProps) {
  const { i18n } = useTranslation()
  const series = samples.flatMap((sample): [number, number][] => {
    const reading = read(sample)
    return reading == null ? [] : [[Date.parse(sample.time), reading]]
  })

  return (
    <div className="min-w-0 space-y-1">
      <div className="flex items-baseline justify-between gap-2 text-sm">
        <span className="text-muted-foreground">{label}</span>
        <span className="font-semibold whitespace-nowrap">{value}</span>
      </div>
      {series.length > 0 && <LineChart series={series} label={label} locale={i18n.resolvedLanguage} height={CHART_HEIGHT} zeroBased maxGap={HISTORY_GAP_MS} binary={binary} format={format} unit={unit} />}
    </div>
  )
}

function BrokerHistory({ stats }: { stats?: Schemas["BrokerStatsView"] }) {
  const { t, i18n } = useTranslation()
  const [hours, setHours] = useState("24")
  const history = $api.useQuery(
    "get",
    "/api/broker/stats/history",
    { params: { query: { hours: Number(hours) } } },
    { refetchInterval: HISTORY_REFRESH_MS, placeholderData: (previous) => previous },
  )
  const samples = history.data ?? []
  const number = (value: number) => new Intl.NumberFormat(i18n.language, { maximumFractionDigits: 2 }).format(value)
  const bytes = (value: number) => formatBytes(value, i18n.language)
  const rate = (value: number | null | undefined) => (value == null ? "–" : `${number(value)} /s`)
  const byteRate = (value: number | null | undefined) => (value == null ? "–" : `${bytes(value)}/s`)

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("dashboard.history")}</CardTitle>
        <CardAction>
          <Tabs value={hours} onValueChange={setHours}>
            <TabsList>
              <TabsTrigger value="24">{t("dashboard.lastDay")}</TabsTrigger>
              <TabsTrigger value="168">{t("dashboard.lastWeek")}</TabsTrigger>
            </TabsList>
          </Tabs>
        </CardAction>
      </CardHeader>
      <CardContent className="space-y-4">
        {history.isSuccess && samples.length === 0 && <p className="text-sm text-muted-foreground">{t("dashboard.historyEmpty")}</p>}
        <div className="grid gap-x-6 gap-y-4 sm:grid-cols-2 lg:grid-cols-3">
          <HistoryChart label={t("dashboard.connections")} value={stats?.connections == null ? "–" : number(stats.connections)} samples={samples} read={(sample) => sample.connections} format={number} />
          <HistoryChart label={t("dashboard.messagesIn")} value={rate(stats?.messagesIn)} samples={samples} read={(sample) => sample.messagesIn} format={number} unit=" /s" />
          <HistoryChart label={t("dashboard.messagesOut")} value={rate(stats?.messagesOut)} samples={samples} read={(sample) => sample.messagesOut} format={number} unit=" /s" />
          <HistoryChart label={t("dashboard.bytesIn")} value={byteRate(stats?.bytesIn)} samples={samples} read={(sample) => sample.bytesIn} format={bytes} binary unit="/s" />
          <HistoryChart label={t("dashboard.bytesOut")} value={byteRate(stats?.bytesOut)} samples={samples} read={(sample) => sample.bytesOut} format={bytes} binary unit="/s" />
          <HistoryChart label={t("dashboard.memory")} value={stats?.heapBytes == null ? "–" : bytes(stats.heapBytes)} samples={samples} read={(sample) => sample.heapBytes} format={bytes} binary />
        </div>
      </CardContent>
    </Card>
  )
}

export function DashboardPage() {
  const { t, i18n } = useTranslation()
  const status = useBrokerStatus()
  const connected = status.data?.connected ?? false
  const options = { enabled: connected }
  const clients = $api.useQuery("get", "/api/clients", {}, { ...options, refetchInterval: REFRESH_MS })
  const groups = $api.useQuery("get", "/api/groups", {}, options)
  const roles = $api.useQuery("get", "/api/roles", {}, options)
  const certificate = $api.useQuery("get", "/api/certificate")
  const backup = $api.useQuery("get", "/api/backup")
  const bridges = $api.useQuery("get", "/api/bridges", {}, { refetchInterval: REFRESH_MS })
  const system = $api.useQuery("get", "/api/system/info")
  const stats = $api.useQuery("get", "/api/broker/stats", {}, { ...options, refetchInterval: REFRESH_MS })
  const missing = $api.useQuery("get", "/api/broker/missing-clients", {}, { ...options, refetchInterval: REFRESH_MS })
  const online = clients.data?.filter((client) => client.connections.length > 0).length
  const down = bridges.data?.bridges.filter((view) => view.connected === false).map((view) => view.bridge.name) ?? []
  const allBridges = bridges.data?.bridges.length
  const uptime = stats.data?.uptimeSeconds

  function formatUptime(seconds: number) {
    const hours = Math.floor((seconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR)
    return seconds >= SECONDS_PER_DAY
      ? t("dashboard.uptimeDays", { days: Math.floor(seconds / SECONDS_PER_DAY), hours })
      : t("dashboard.uptimeHours", { hours, minutes: Math.floor((seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE) })
  }

  return (
    <div className="space-y-4">
      {certificate.data?.job.error && (
        <Alert variant="destructive">
          <TriangleAlertIcon />
          <AlertTitle>{t("certificate.failed")}</AlertTitle>
          <AlertDescription>{certificate.data.job.error}</AlertDescription>
        </Alert>
      )}
      {backup.data?.health.filter((job) => job.failed || job.overdue).map((job) => (
        <Alert key={job.job} variant="destructive">
          <TriangleAlertIcon />
          <AlertTitle>{t(job.failed ? "dashboard.backupFailed" : "dashboard.backupOverdue", { job: job.job })}</AlertTitle>
          {job.message && <AlertDescription className="break-words">{job.message}</AlertDescription>}
        </Alert>
      ))}
      {certificate.data?.certificate?.renewalDue && (
        <Alert variant="destructive">
          <TriangleAlertIcon />
          <AlertTitle>{t("certificate.due")}</AlertTitle>
          <AlertDescription>{t("dashboard.renewalDue", { days: certificate.data.certificate.daysLeft })}</AlertDescription>
        </Alert>
      )}
      {down.length > 0 && (
        <Alert variant="destructive">
          <TriangleAlertIcon />
          <AlertTitle>{t("dashboard.bridgesDown")}</AlertTitle>
          <AlertDescription>
            <Link to="/bridges" className="underline">
              {down.join(", ")}
            </Link>
          </AlertDescription>
        </Alert>
      )}
      {missing.data && missing.data.length > 0 && (
        <Alert>
          <TriangleAlertIcon />
          <AlertTitle>{t("dashboard.clientsMissing")}</AlertTitle>
          <AlertDescription>
            <Link to="/clients?status=offline" className="underline">
              {missing.data.map((client) => `${client.textname || client.username} (${t("dashboard.lastSeen", { date: formatDateTime(client.lastSeen, i18n.language) })})`).join(", ")}
            </Link>
          </AlertDescription>
        </Alert>
      )}
      {connected && stats.isSuccess && uptime == null && (
        <Alert>
          <InfoIcon />
          <AlertTitle>{t("dashboard.noStats")}</AlertTitle>
          <AlertDescription>{t("dashboard.noStatsHint")}</AlertDescription>
        </Alert>
      )}
      <Card>
        <CardHeader>
          <CardDescription>{t("broker.title")}</CardDescription>
          <CardTitle className="flex flex-wrap items-center gap-2">
            {status.data && `${status.data.host}:${status.data.port}`}
            <Badge variant={connected ? "default" : "destructive"}>{connected ? t("broker.connected") : t("broker.offline")}</Badge>
            {stats.data?.version && <Badge variant="outline">Mosquitto {stats.data.version}</Badge>}
            {system.data && <Badge variant="outline">UI {system.data.version}</Badge>}
          </CardTitle>
        </CardHeader>
      </Card>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <CountCard to="/clients" label={t("nav.clients")} count={clients.data?.length} />
        <CountCard to="/clients?status=online" label={t("clients.online")} count={online} />
        <CountCard to="/groups" label={t("nav.groups")} count={groups.data?.length} />
        <CountCard to="/roles" label={t("nav.roles")} count={roles.data?.length} />
        <CountCard to="/bridges" label={t("dashboard.bridges")} count={allBridges === undefined ? undefined : `${allBridges - down.length} / ${allBridges}`} />
        <CountCard to="/certificate" label={t("certificate.daysLeft")} count={certificate.data?.certificate?.daysLeft} />
        <CountCard label={t("dashboard.subscriptions")} count={stats.data?.subscriptions ?? undefined} />
        <CountCard to="/topics?retained=true" label={t("dashboard.retained")} count={stats.data?.retained ?? undefined} />
        <CountCard to="/config" label={t("dashboard.uptime")} count={uptime == null ? undefined : formatUptime(uptime)} />
      </div>
      <BrokerHistory stats={stats.data} />
      <div className="grid gap-4 sm:grid-cols-[repeat(auto-fit,minmax(18rem,1fr))]">
        <ActivityCard clients={clients.data} />
        <UpcomingCard health={backup.data?.health} certificate={certificate.data} />
        <BridgesCard bridges={bridges.data?.bridges} />
      </div>
      <ListenersCard certificate={certificate.data} />
    </div>
  )
}
