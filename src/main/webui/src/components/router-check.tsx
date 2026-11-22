import { RouterIcon } from "lucide-react"
import { useState, type FormEvent } from "react"
import { useTranslation } from "react-i18next"

import { $api, type Schemas } from "@/api/client"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Spinner } from "@/components/ui/spinner"
import { useErrorMessage } from "@/lib/errors"

type RouterInfo = Schemas["RouterInfo"]
type Verdict = "dual" | "ipv4" | "ipv6" | "dslite" | "none"

const HOST_KEY = "mosquitto-ui.router-host"
const UI_PORT = 8080

function verdict(info: RouterInfo): Verdict {
  const ipv4 = info.ipv4Kind === "PUBLIC"
  const ipv6 = Boolean(info.externalIPv6 || info.ipv6Prefix)
  if (ipv4 && ipv6) return "dual"
  if (ipv4) return "ipv4"
  if (ipv6) return info.dsLite ? "dslite" : "ipv6"
  return "none"
}

/**
 * What the internet connection of the router allows for connections from outside, and which forwardings MQTT over TLS and HTTP-01
 * need. Only reads the status of the router; the forwardings are for the user to set up.
 */
export function RouterCheck({ tlsPort, http01 }: { tlsPort: number; http01: boolean }) {
  const { t } = useTranslation()
  const errorMessage = useErrorMessage()
  const [host, setHost] = useState(() => localStorage.getItem(HOST_KEY) ?? "fritz.box")
  const [asked, setAsked] = useState<string | null>(null)
  const router = $api.useQuery("get", "/api/router", { params: { query: { host: asked ?? "" } } }, { enabled: asked !== null, retry: false })

  function check(event: FormEvent) {
    event.preventDefault()
    const name = host.trim() || "fritz.box"
    localStorage.setItem(HOST_KEY, name)
    if (name === asked) {
      void router.refetch()
    } else {
      setAsked(name)
    }
  }

  const info = router.data
  // the API names no error bodies, so the type would say there are none
  const failure: unknown = router.error
  const kind = info ? verdict(info) : null
  const ipv4 = kind === "dual" || kind === "ipv4"
  const ipv6 = kind === "dual" || kind === "ipv6" || kind === "dslite"

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <RouterIcon className="size-4" />
          {t("router.title")}
        </CardTitle>
        <CardDescription>{t("router.description")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4 text-sm">
        <form onSubmit={check} className="flex flex-wrap items-center gap-2">
          <Input aria-label={t("router.host")} className="w-48 font-mono text-xs" value={host} onChange={(e) => setHost(e.target.value)} />
          <Button type="submit" variant="outline" disabled={router.isFetching}>
            {router.isFetching && <Spinner />}
            {t("router.check")}
          </Button>
        </form>
        {failure != null && (
          <Alert variant="destructive">
            <AlertTitle>{t("router.failed")}</AlertTitle>
            <AlertDescription className="break-words">{errorMessage(failure)}</AlertDescription>
          </Alert>
        )}
        {info && kind && (
          <>
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
              <dt className="text-muted-foreground">{t("router.model")}</dt>
              <dd>
                {info.model ?? "–"}
                {info.accessType && <span className="text-muted-foreground"> · {info.accessType}</span>}
              </dd>
              <dt className="text-muted-foreground">IPv4</dt>
              <dd className="flex flex-wrap items-center gap-2">
                <span className="font-mono text-xs">{info.externalIPv4 ?? "–"}</span>
                <Badge variant={info.ipv4Kind === "PUBLIC" ? "default" : "secondary"}>{t(`router.kinds.${info.ipv4Kind as "PUBLIC"}`)}</Badge>
              </dd>
              <dt className="text-muted-foreground">IPv6</dt>
              <dd className="font-mono text-xs">
                {info.externalIPv6 ?? "–"}
                {info.ipv6Prefix && ` · ${info.ipv6Prefix}/${info.ipv6PrefixLength}`}
              </dd>
            </dl>
            <Alert variant={kind === "none" ? "destructive" : "default"}>
              <AlertTitle>{t(`router.verdicts.${kind}`)}</AlertTitle>
              <AlertDescription>{t(`router.verdictHints.${kind}`)}</AlertDescription>
            </Alert>
            {(ipv4 || ipv6) && (
              <div className="space-y-2">
                <p className="font-medium">{t("router.forwards")}</p>
                <ul className="list-disc space-y-1 pl-5">
                  {ipv4 && <li>{t("router.forwardIpv4", { port: tlsPort })}</li>}
                  {ipv4 && http01 && <li>{t("router.forwardIpv4Http", { port: UI_PORT })}</li>}
                  {ipv6 && <li>{t("router.forwardIpv6", { port: tlsPort })}</li>}
                  {ipv6 && http01 && <li>{t("router.forwardIpv6Http", { port: UI_PORT })}</li>}
                </ul>
                <p className="text-muted-foreground">{t(ipv6 ? "router.whereIpv6" : "router.where")}</p>
              </div>
            )}
            {kind === "none" && http01 && <p className="text-muted-foreground">{t("router.http01Impossible")}</p>}
          </>
        )}
      </CardContent>
    </Card>
  )
}
