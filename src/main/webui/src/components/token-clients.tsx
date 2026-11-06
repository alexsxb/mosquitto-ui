import { useQueryClient } from "@tanstack/react-query"
import { BanIcon, EyeOffIcon, KeyRoundIcon } from "lucide-react"
import { useState } from "react"
import { useTranslation } from "react-i18next"
import { Link } from "react-router"
import { toast } from "sonner"

import { $api, type Schemas } from "@/api/client"
import { BadgeList } from "@/components/badge-list"
import { TokenBlockDialog, type BlockTarget } from "@/components/token-block-dialog"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { useErrorToast } from "@/lib/errors"
import { formatDateTime } from "@/lib/format"

/** Why one seen can no longer log in, as the server tells (TokenClientLogins.SeenTokenClient) */
const LOCKED_OUT = ["disabled", "issuer", "revoked", "blocked"] as const

type LockedOut = (typeof LOCKED_OUT)[number]

function isLockedOut(reason: string | null): reason is LockedOut {
  return reason !== null && (LOCKED_OUT as readonly string[]).includes(reason)
}

const seenKey = { queryKey: ["get", "/api/token-auth/seen"] }

type SeenClient = Schemas["SeenTokenClient"]

/** The filters of the page Clients, as they apply to clients with a token: disabled is one that can no longer log in */
export interface TokenClientFilter {
  search: string
  status: "all" | "online" | "offline" | "disabled"
  /** null: any role */
  role: string | null
}

function matches(client: SeenClient, filter: TokenClientFilter) {
  const term = filter.search.trim().toLowerCase()
  if (term && ![client.username, client.clientid, client.issuer, ...client.roles].some((v) => v?.toLowerCase().includes(term))) {
    return false
  }
  if (filter.role !== null && !client.roles.includes(filter.role)) {
    return false
  }
  switch (filter.status) {
    case "online":
      return client.connected
    case "offline":
      return !client.connected
    case "disabled":
      return client.lockedOut !== null
    default:
      return true
  }
}

/**
 * The clients that connected with an access token, now or before: dynamic security does not know them, the token login plugin
 * tells who they are, and the UI keeps when each was last there. One gone stays until hidden; it shows again when it connects
 * again. Nothing shows while there are none, or none of them is left by the filter.
 */
export function TokenClients({ filter }: { filter?: TokenClientFilter }) {
  const { t, i18n } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const seen = $api.useQuery("get", "/api/token-auth/seen", {}, { refetchInterval: 10_000 })
  const settings = $api.useQuery("get", "/api/token-auth")
  const hide = $api.useMutation("delete", "/api/token-auth/seen/{username}")
  const [blocking, setBlocking] = useState<BlockTarget | null>(null)
  const shown = filter ? (seen.data ?? []).filter((client) => matches(client, filter)) : (seen.data ?? [])
  if (!seen.data || shown.length === 0) {
    return null
  }
  const connected = shown.filter((client) => client.connected).length

  async function hideClient(username: string) {
    try {
      await hide.mutateAsync({ params: { path: { username } } })
      toast.success(t("tokenClients.hidden", { name: username }))
    } catch (error) {
      errorToast(error)
    } finally {
      await queryClient.invalidateQueries(seenKey)
    }
  }

  return (
    <Card className="mt-6">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <KeyRoundIcon className="size-4" />
          {t("tokenClients.title", { count: shown.length })}
          <span className="text-muted-foreground text-sm font-normal">
            {t("tokenClients.connectedNow", { count: connected })}
            {shown.length < seen.data.length && ` · ${t("common.shown", { shown: shown.length, total: seen.data.length })}`}
          </span>
        </CardTitle>
        <CardDescription>
          {t("tokenClients.description")}{" "}
          <Link to="/token-auth" className="underline underline-offset-4">
            {t("nav.tokenAuth")}
          </Link>
        </CardDescription>
      </CardHeader>
      <CardContent className="overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("tokenClients.username")}</TableHead>
              <TableHead>{t("tokenClients.clientid")}</TableHead>
              <TableHead>{t("tokenClients.roles")}</TableHead>
              <TableHead>{t("tokenClients.lastSeen")}</TableHead>
              <TableHead>{t("tokenClients.expires")}</TableHead>
              <TableHead className="w-0" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {shown.map((client) => {
              const lockedOut = isLockedOut(client.lockedOut) ? client.lockedOut : null
              return (
                <TableRow key={client.username}>
                  <TableCell>
                    <div className="font-medium">{client.username}</div>
                    {lockedOut && (
                      <Badge variant={lockedOut === "blocked" ? "destructive" : "outline"} className="mt-1" title={t(`tokenClients.lockedOutHint.${lockedOut}`)}>
                        {t(`tokenClients.lockedOut.${lockedOut}`)}
                      </Badge>
                    )}
                  </TableCell>
                  <TableCell>
                    <div className="font-mono text-xs">{client.clientid ?? "–"}</div>
                    <div className="text-muted-foreground font-mono text-xs">{client.address}</div>
                  </TableCell>
                  <TableCell>
                    <BadgeList names={client.roles} />
                  </TableCell>
                  <TableCell className="whitespace-nowrap">
                    <span className="inline-flex items-center gap-2">
                      <span className={client.connected ? "size-2 rounded-full bg-emerald-500" : "bg-muted-foreground/40 size-2 rounded-full"} />
                      {client.connected
                        ? t("clients.connectedSince", { date: formatDateTime(client.connectedAt as string, i18n.language) })
                        : formatDateTime(client.lastSeen as string, i18n.language)}
                    </span>
                  </TableCell>
                  <TableCell className="max-w-56">
                    <div className="whitespace-nowrap">{client.expires ? formatDateTime(client.expires as string, i18n.language) : "–"}</div>
                    <div className="text-muted-foreground truncate font-mono text-xs" title={client.issuer}>
                      {client.issuer}
                    </div>
                  </TableCell>
                  <TableCell>
                    <div className="flex">
                      {/* a token taken back cannot come back anyway */}
                      {lockedOut !== "blocked" && lockedOut !== "revoked" && (
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          aria-label={t("tokenBlocks.blockClient", { name: client.username })}
                          title={t("tokenBlocks.blockClient", { name: client.username })}
                          onClick={() =>
                            setBlocking({ username: client.username, sub: client.sub, jti: client.jti, issuer: client.issuer, expires: client.expires as string | null })
                          }
                        >
                          <BanIcon />
                        </Button>
                      )}
                      {!client.connected && (
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          aria-label={t("tokenClients.hide", { name: client.username })}
                          title={t("tokenClients.hide", { name: client.username })}
                          disabled={hide.isPending}
                          onClick={() => void hideClient(client.username)}
                        >
                          <EyeOffIcon />
                        </Button>
                      )}
                    </div>
                  </TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
        {blocking && <TokenBlockDialog target={blocking} issuers={settings.data?.issuers.map((i) => i.issuer) ?? []} onClose={() => setBlocking(null)} />}
      </CardContent>
    </Card>
  )
}
