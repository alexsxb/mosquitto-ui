import { CalendarClockIcon, FileKeyIcon, FileTextIcon, GlobeIcon, LockIcon, LockOpenIcon, ShieldCheckIcon, TriangleAlertIcon, UsersIcon } from "lucide-react"
import { useTranslation } from "react-i18next"
import { Link } from "react-router"

import { $api, type Schemas } from "@/api/client"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { cn } from "@/lib/utils"

type Listener = Schemas["ListenerForm"]
type Certificate = Schemas["CertificateInfo"]

const DAY_MS = 86_400_000

/** "CN=E7,O=Let's Encrypt,C=US" becomes "Let's Encrypt, E7". */
function issuerName(issuer: string) {
  const part = (key: string) => new RegExp(`(?:^|,\\s*)${key}=([^,]+)`).exec(issuer)?.[1]
  const names = [part("O"), part("CN")].filter(Boolean)
  return names.length > 0 ? names.join(", ") : issuer
}

function Lifetime({ certificate }: { certificate: Certificate }) {
  const { t, i18n } = useTranslation()
  const totalDays = (Date.parse(certificate.notAfter) - Date.parse(certificate.notBefore)) / DAY_MS
  const percent = totalDays > 0 ? Math.max(0, Math.min(100, (certificate.daysLeft / totalDays) * 100)) : 0

  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between gap-2 text-xs text-muted-foreground">
        <span className="flex items-center gap-1.5">
          <CalendarClockIcon className="size-3.5" />
          {t("listeners.validUntil", { date: new Intl.DateTimeFormat(i18n.language, { dateStyle: "medium" }).format(new Date(certificate.notAfter)) })}
        </span>
        <span className={cn("flex items-center gap-1 font-medium whitespace-nowrap", certificate.renewalDue ? "text-destructive" : "text-foreground")}>
          {certificate.renewalDue && <TriangleAlertIcon className="size-3.5" />}
          {t("listeners.daysLeft", { count: certificate.daysLeft })}
        </span>
      </div>
      <div className="h-1.5 overflow-hidden rounded-full bg-muted" role="progressbar" aria-valuenow={Math.round(percent)} aria-label={t("listeners.lifetime")}>
        <div className={cn("h-full rounded-full transition-[width] duration-700", certificate.renewalDue ? "bg-destructive" : "bg-primary")} style={{ width: `${percent}%` }} />
      </div>
    </div>
  )
}

function TlsDetails({ listener, certificate, ours }: { listener: Listener; certificate?: Certificate | null; ours: boolean }) {
  const { t } = useTranslation()

  return (
    <div className="space-y-3 border-t pt-3">
      {ours && certificate ? (
        <>
          <div className="flex flex-wrap gap-1.5">
            {certificate.domains.map((domain) => (
              <Badge key={domain} variant="secondary" className="font-mono">
                {domain}
              </Badge>
            ))}
          </div>
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs">
            <dt className="text-muted-foreground">{t("listeners.issuer")}</dt>
            <dd className="truncate" title={certificate.issuer}>
              {issuerName(certificate.issuer)}
            </dd>
            <dt className="text-muted-foreground">{t("listeners.key")}</dt>
            <dd>{certificate.keyType}</dd>
          </dl>
          <Lifetime certificate={certificate} />
        </>
      ) : (
        <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <FileKeyIcon className="size-3.5 shrink-0" />
          <span className="truncate font-mono" title={listener.certfile ?? undefined}>
            {listener.certfile}
          </span>
        </p>
      )}
      {listener.cafile && (
        <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <ShieldCheckIcon className="size-3.5 shrink-0" />
          {t("listeners.ownCa")}
        </p>
      )}
    </div>
  )
}

function ListenerTile({ listener, mainFile, certificate, ourCertfile }: { listener: Listener; mainFile: boolean; certificate?: Certificate | null; ourCertfile?: string | null }) {
  const { t } = useTranslation()
  const tls = Boolean(listener.certfile)

  return (
    <div className={cn("relative space-y-3 overflow-hidden rounded-lg border bg-card p-4", tls && "listener-tls")}>
      <div className="flex items-start justify-between gap-2">
        <div>
          <div className="text-2xl font-semibold tracking-tight tabular-nums">{listener.port}</div>
          <div className="mt-0.5 flex items-center gap-1.5 text-xs text-muted-foreground">
            <GlobeIcon className="size-3.5" />
            {listener.bindAddress || t("listeners.allInterfaces")}
          </div>
        </div>
        <div className="flex flex-wrap justify-end gap-1.5">
          <Badge variant="outline">{t(`listeners.protocols.${listener.protocol as "mqtt" | "websockets"}`)}</Badge>
          {listener.proxyProtocol ? (
            // whether the client used TLS up to the proxy, only the proxy knows
            <Badge variant="outline" title={t("listeners.proxyHint")}>
              {t("listeners.proxy", { version: listener.proxyProtocol })}
            </Badge>
          ) : null}
          {tls ? (
            <Badge>
              <LockIcon />
              {t("listeners.tls")}
            </Badge>
          ) : listener.proxyProtocol ? null : (
            <Badge variant="secondary">
              <LockOpenIcon />
              {t("listeners.plain")}
            </Badge>
          )}
        </div>
      </div>
      {tls && <TlsDetails listener={listener} certificate={certificate} ours={listener.certfile === ourCertfile} />}
      <div className="space-y-1.5 border-t pt-3 text-xs text-muted-foreground">
        <p className="flex items-center gap-1.5">
          <UsersIcon className="size-3.5 shrink-0" />
          {listener.maxConnections != null && listener.maxConnections >= 0 ? t("listeners.maxConnections", { count: listener.maxConnections }) : t("listeners.unlimited")}
        </p>
        <p className="flex items-center gap-1.5">
          <FileTextIcon className="size-3.5 shrink-0" />
          {t(mainFile ? "listeners.mainFile" : "listeners.managed")}
        </p>
        {!tls && (
          <p className="flex items-center gap-1.5">
            <LockOpenIcon className="size-3.5 shrink-0" />
            {t("listeners.plainHint")}
          </p>
        )}
      </div>
    </div>
  )
}

/** What the file of the UI sets up, and the ports of mosquitto.conf; of those the UI only knows the number. */
export function ListenersCard({ certificate }: { certificate?: Schemas["CertificateView"] }) {
  const { t } = useTranslation()
  const config = $api.useQuery("get", "/api/config")

  if (!config.data) {
    return null
  }
  const { form, mainListeners } = config.data
  const main = new Set(mainListeners)
  const listeners = [...mainListeners, ...form.listeners].sort((a, b) => (a.port ?? 0) - (b.port ?? 0))
  const secured = listeners.filter((listener) => listener.certfile).length

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <Link to="/config" className="hover:underline">
            {t("listeners.title")}
          </Link>
        </CardTitle>
        <CardDescription>{t("listeners.summary", { count: listeners.length, secured })}</CardDescription>
      </CardHeader>
      <CardContent>
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {listeners.map((listener) => (
            <ListenerTile key={listener.port} listener={listener} mainFile={main.has(listener)} certificate={certificate?.certificate} ourCertfile={certificate?.certfile} />
          ))}
        </div>
      </CardContent>
    </Card>
  )
}
