import { BanIcon } from "lucide-react"
import { useState } from "react"
import { useTranslation } from "react-i18next"

import { $api } from "@/api/client"
import { TokenBlockDialog, type BlockTarget } from "@/components/token-block-dialog"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { formatDateTime } from "@/lib/format"

/** The codes the plugin gives (docker/mosquitto/token-auth/token_auth.c); any other shows as it is */
const REASONS = [
  "not_jwt",
  "algorithm",
  "critical_header",
  "unknown_issuer",
  "no_signature",
  "unknown_key",
  "signature",
  "no_expiry",
  "expired",
  "not_yet_valid",
  "audience",
  "no_username",
  "username_reserved",
  "clientid",
  "blocked",
  "no_role",
  "error",
] as const

type Reason = (typeof REASONS)[number]

function isKnown(reason: string): reason is Reason {
  return (REASONS as readonly string[]).includes(reason)
}

/**
 * Tokens the broker did not accept since it started, the latest first: what the token says, and why. What the token says about
 * its issuer and user name holds only where the signature did; the rest may be anything anyone sent.
 */
export function TokenRejectionsCard({ issuers }: { issuers: string[] }) {
  const { t, i18n } = useTranslation()
  const rejected = $api.useQuery("get", "/api/token-auth/rejected", {}, { refetchInterval: 10_000 })
  const [blocking, setBlocking] = useState<BlockTarget | null>(null)

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("tokenRejected.title")}</CardTitle>
        <CardDescription>{t("tokenRejected.description")}</CardDescription>
      </CardHeader>
      <CardContent className="overflow-x-auto">
        {!rejected.data || rejected.data.length === 0 ? (
          <p className="text-muted-foreground text-sm">{t("tokenRejected.none")}</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("tokenRejected.at")}</TableHead>
                <TableHead>{t("tokenRejected.client")}</TableHead>
                <TableHead>{t("tokenRejected.username")}</TableHead>
                <TableHead>{t("tokenRejected.reason")}</TableHead>
                <TableHead>{t("tokenRejected.issuer")}</TableHead>
                <TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {rejected.data.map((entry) => {
                const reason = isKnown(entry.reason) ? entry.reason : null
                return (
                  <TableRow key={`${entry.clientid}|${entry.issuer}|${entry.username}|${entry.kid}|${entry.reason}`}>
                    <TableCell className="whitespace-nowrap">
                      <div>{formatDateTime(entry.at as string, i18n.language)}</div>
                      {entry.count > 1 && <div className="text-muted-foreground text-xs">{t("tokenRejected.count", { count: entry.count })}</div>}
                    </TableCell>
                    <TableCell>
                      <div className="font-mono text-xs">{entry.clientid || "–"}</div>
                      <div className="text-muted-foreground font-mono text-xs">{entry.address}</div>
                    </TableCell>
                    <TableCell>
                      <div className="font-medium">{entry.username ?? "–"}</div>
                      {entry.username && !entry.verified && (
                        <Badge variant="outline" className="mt-1" title={t("tokenRejected.unverifiedHint")}>
                          {t("tokenRejected.unverified")}
                        </Badge>
                      )}
                    </TableCell>
                    <TableCell className="max-w-sm min-w-56 whitespace-normal">
                      <div>{reason ? t(`tokenRejected.reasons.${reason}`) : entry.reason}</div>
                      {reason && <div className="text-muted-foreground text-xs">{t(`tokenRejected.hints.${reason}`, { kid: entry.kid ?? "–" })}</div>}
                    </TableCell>
                    <TableCell className="text-muted-foreground max-w-64 truncate font-mono text-xs" title={entry.issuer ?? undefined}>
                      {entry.issuer ?? "–"}
                    </TableCell>
                    <TableCell>
                      {entry.verified && entry.username && entry.reason !== "blocked" && (
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon-sm"
                          aria-label={t("tokenBlocks.blockClient", { name: entry.username })}
                          title={t("tokenBlocks.blockClient", { name: entry.username })}
                          onClick={() => setBlocking({ username: entry.username!, sub: entry.sub, jti: entry.jti, issuer: entry.issuer })}
                        >
                          <BanIcon />
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        )}
        {blocking && <TokenBlockDialog target={blocking} issuers={issuers} onClose={() => setBlocking(null)} />}
      </CardContent>
    </Card>
  )
}
