import { ArrowDownIcon, ArrowUpIcon, ChevronDownIcon, ChevronRightIcon, CircleDotIcon, ScrollTextIcon } from "lucide-react"
import { Fragment, useMemo, useState } from "react"
import { useTranslation } from "react-i18next"
import { useNavigate } from "react-router"

import { $api, type Schemas } from "@/api/client"
import { InspectUnavailable } from "@/components/inspect-unavailable"
import { PageToolbar } from "@/components/page-toolbar"
import { SortableHead } from "@/components/sortable-head"
import { TablePagination } from "@/components/table-pagination"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { formatBytes, formatDateTime } from "@/lib/format"
import { useTable } from "@/lib/use-table"

type Session = Schemas["Session"]
type Status = "all" | "online" | "offline"

const statuses: Status[] = ["all", "online", "offline"]
const columns = {
  clientid: (s: Session) => s.clientid,
  subscriptions: (s: Session) => s.subscriptionCount,
  messagesIn: (s: Session) => s.messagesIn,
}

/**
 * Every session the broker has, connected or kept for later, as the inspect plugin tells (docker/mosquitto/inspect): what each
 * client subscribed, how much it sent and got, since the broker started.
 */
export function SessionsPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const status = $api.useQuery("get", "/api/inspect/status", {}, { refetchInterval: 10_000 })
  const available = status.data?.available === true
  const sessions = $api.useQuery("get", "/api/inspect/sessions", {}, { enabled: available, refetchInterval: 5_000 })
  const [search, setSearch] = useState("")
  const [filter, setFilter] = useState<Status>("all")
  const [open, setOpen] = useState<Set<string>>(new Set())
  const number = new Intl.NumberFormat(i18n.language)

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase()
    return (sessions.data ?? []).filter(
      (s) =>
        (filter === "all" || (filter === "online") === s.connected) &&
        [s.clientid, s.username, s.address, ...s.subscriptions.map((sub) => sub.topic)].some((v) => v?.toLowerCase().includes(term)),
    )
  }, [sessions.data, search, filter])
  const table = useTable("sessions", filtered, columns, "clientid")

  function toggle(clientid: string) {
    const next = new Set(open)
    if (next.has(clientid)) {
      next.delete(clientid)
    } else {
      next.add(clientid)
    }
    setOpen(next)
  }

  if (status.data && !available) {
    return <InspectUnavailable reason={status.data.reason} />
  }

  return (
    <>
      <PageToolbar
        search={search}
        onSearch={(value) => {
          setSearch(value)
          table.setPage(0)
        }}
        placeholder={t("sessions.search")}
        count={sessions.data && t("common.shown", { shown: filtered.length, total: sessions.data.length })}
        refresh={[sessions]}
      >
        <Select
          value={filter}
          onValueChange={(value) => {
            setFilter(value as Status)
            table.setPage(0)
          }}
        >
          <SelectTrigger className="w-40" aria-label={t("clients.status")}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {statuses.map((item) => (
              <SelectItem key={item} value={item}>
                {t(`sessions.statuses.${item}`)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </PageToolbar>
      <p className="text-muted-foreground mb-4 text-sm">{t("sessions.description")}</p>

      {!sessions.data ? (
        <Skeleton className="h-40 w-full" />
      ) : filtered.length === 0 ? (
        <p className="text-muted-foreground py-8 text-center text-sm">{t("sessions.empty")}</p>
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-0" />
                <SortableHead active={table.sort === "clientid"} direction={table.direction} onSort={() => table.toggle("clientid")}>
                  {t("sessions.client")}
                </SortableHead>
                <TableHead className="hidden sm:table-cell">{t("sessions.state")}</TableHead>
                <TableHead className="hidden lg:table-cell">{t("sessions.connection")}</TableHead>
                <SortableHead
                  className="hidden md:table-cell"
                  active={table.sort === "subscriptions"}
                  direction={table.direction}
                  onSort={() => table.toggle("subscriptions")}
                >
                  {t("sessions.subscriptions")}
                </SortableHead>
                <SortableHead className="hidden md:table-cell" active={table.sort === "messagesIn"} direction={table.direction} onSort={() => table.toggle("messagesIn")}>
                  {t("sessions.messages")}
                </SortableHead>
                <TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {table.rows.map((s) => {
                const expanded = open.has(s.clientid)
                const unknown = s.subscriptionCount - s.subscriptions.length
                return (
                  <Fragment key={s.clientid}>
                    <TableRow>
                      <TableCell>
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          aria-label={t("sessions.details", { name: s.clientid })}
                          aria-expanded={expanded}
                          onClick={() => toggle(s.clientid)}
                        >
                          {expanded ? <ChevronDownIcon /> : <ChevronRightIcon />}
                        </Button>
                      </TableCell>
                      <TableCell className="max-w-72">
                        <div className="flex items-center gap-2">
                          {/* the column of the state is not there on a phone */}
                          <span className={`size-2 shrink-0 rounded-full sm:hidden ${s.connected ? "bg-emerald-500" : "bg-muted-foreground/40"}`} />
                          <span className="truncate font-mono text-xs" title={s.clientid}>
                            {s.clientid}
                          </span>
                        </div>
                        <div className="mt-0.5 flex flex-wrap items-center gap-1">
                          {s.username && <span className="text-sm">{s.username}</span>}
                          {s.kind !== "dynsec" && <Badge variant="outline">{t(`sessions.kinds.${s.kind === "token" ? "token" : "ui"}`)}</Badge>}
                        </div>
                      </TableCell>
                      <TableCell className="hidden whitespace-nowrap sm:table-cell">
                        <span className="inline-flex items-center gap-2">
                          <span className={s.connected ? "size-2 rounded-full bg-emerald-500" : "bg-muted-foreground/40 size-2 rounded-full"} />
                          <span className="text-sm">
                            {s.connected
                              ? t("clients.connectedSince", { date: formatDateTime(s.connectedAt as string | null, i18n.language) })
                              : s.offlineAt
                                ? t("sessions.offlineSince", { date: formatDateTime(s.offlineAt as string, i18n.language) })
                                : t("sessions.kept")}
                          </span>
                        </span>
                        {!s.cleanStart && <div className="text-muted-foreground mt-0.5 text-xs">{t("sessions.persistent")}</div>}
                      </TableCell>
                      <TableCell className="hidden lg:table-cell">
                        <div className="font-mono text-xs">{s.address ?? "–"}</div>
                        <div className="text-muted-foreground text-xs">
                          {t("sessions.protocol", { version: s.protocol })}
                          {s.transport === "websockets" && " · WebSockets"} · {t("sessions.keepalive", { seconds: s.keepalive })}
                        </div>
                      </TableCell>
                      <TableCell className="hidden md:table-cell">
                        <div>{number.format(s.subscriptionCount)}</div>
                        {unknown > 0 && <div className="text-muted-foreground text-xs">{t("sessions.unknownSubscriptions", { count: unknown })}</div>}
                      </TableCell>
                      <TableCell className="hidden whitespace-nowrap md:table-cell">
                        <div title={s.lastIn ? t("sessions.lastSent", { date: formatDateTime(s.lastIn as string, i18n.language) }) : undefined}>
                          <ArrowUpIcon className="text-muted-foreground inline size-3" aria-label={t("sessions.sent")} /> {number.format(s.messagesIn)}
                          <span className="text-muted-foreground text-xs"> · {formatBytes(s.bytesIn, i18n.language)}</span>
                        </div>
                        <div title={s.lastOut ? t("sessions.lastReceived", { date: formatDateTime(s.lastOut as string, i18n.language) }) : undefined}>
                          <ArrowDownIcon className="text-muted-foreground inline size-3" aria-label={t("sessions.received")} /> {number.format(s.messagesOut)}
                          <span className="text-muted-foreground text-xs"> · {formatBytes(s.bytesOut, i18n.language)}</span>
                        </div>
                        {s.dropped > 0 && <div className="text-destructive text-xs">{t("sessions.droppedCount", { count: s.dropped })}</div>}
                      </TableCell>
                      <TableCell>
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          aria-label={t("sessions.trace", { name: s.clientid })}
                          title={t("sessions.trace", { name: s.clientid })}
                          onClick={() => void navigate(`/trace?clientid=${encodeURIComponent(s.clientid)}`)}
                        >
                          <CircleDotIcon />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          aria-label={t("sessions.logs", { name: s.clientid })}
                          title={t("sessions.logs", { name: s.clientid })}
                          onClick={() => void navigate(`/logs?clientid=${encodeURIComponent(s.clientid)}`)}
                        >
                          <ScrollTextIcon />
                        </Button>
                      </TableCell>
                    </TableRow>
                    {expanded && (
                      <TableRow className="bg-muted/30 hover:bg-muted/30">
                        <TableCell />
                        <TableCell colSpan={6} className="max-w-[calc(100vw-5rem)] whitespace-normal sm:max-w-none">
                          <SessionDetails session={s} />
                        </TableCell>
                      </TableRow>
                    )}
                  </Fragment>
                )
              })}
            </TableBody>
          </Table>
        </div>
      )}
      <TablePagination page={table.page} pageSize={table.pageSize} total={table.total} onPage={table.setPage} onPageSize={table.setPageSize} allowAll />
    </>
  )
}

/** What does not fit in the row: the connection on small screens, and the subscriptions */
function SessionDetails({ session: s }: { session: Session }) {
  const { t, i18n } = useTranslation()
  const number = new Intl.NumberFormat(i18n.language)
  const unknown = s.subscriptionCount - s.subscriptions.length
  return (
    <div className="space-y-3 py-1">
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm lg:hidden">
        <dt className="text-muted-foreground sm:hidden">{t("sessions.state")}</dt>
        <dd className="sm:hidden">
          {s.connected
            ? t("clients.connectedSince", { date: formatDateTime(s.connectedAt as string | null, i18n.language) })
            : s.offlineAt
              ? t("sessions.offlineSince", { date: formatDateTime(s.offlineAt as string, i18n.language) })
              : t("sessions.kept")}
          {!s.cleanStart && ` · ${t("sessions.persistent")}`}
        </dd>
        <dt className="text-muted-foreground">{t("sessions.connection")}</dt>
        <dd>
          <span className="font-mono text-xs break-all">{s.address ?? "–"}</span> · {t("sessions.protocol", { version: s.protocol })} ·{" "}
          {t("sessions.keepalive", { seconds: s.keepalive })}
        </dd>
      </dl>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-muted-foreground">{t("sessions.sent")}</dt>
        <dd>
          {number.format(s.messagesIn)} · {formatBytes(s.bytesIn, i18n.language)}
          {s.lastIn && <span className="text-muted-foreground"> · {t("sessions.last", { date: formatDateTime(s.lastIn as string, i18n.language) })}</span>}
        </dd>
        <dt className="text-muted-foreground">{t("sessions.received")}</dt>
        <dd>
          {number.format(s.messagesOut)} · {formatBytes(s.bytesOut, i18n.language)}
          {s.lastOut && <span className="text-muted-foreground"> · {t("sessions.last", { date: formatDateTime(s.lastOut as string, i18n.language) })}</span>}
        </dd>
      </dl>
      {s.subscriptions.length === 0 ? (
        <p className="text-muted-foreground text-sm">{t("sessions.noSubscriptions")}</p>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("sessions.topic")}</TableHead>
              <TableHead>QoS</TableHead>
              <TableHead>{t("sessions.options")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {s.subscriptions.map((sub) => (
              <TableRow key={sub.topic}>
                <TableCell className="font-mono text-xs break-all whitespace-normal">{sub.topic}</TableCell>
                <TableCell>{sub.qos}</TableCell>
                <TableCell className="space-x-1">
                  {sub.noLocal && <Badge variant="outline">No Local</Badge>}
                  {sub.retainAsPublished && <Badge variant="outline">Retain as Published</Badge>}
                  {sub.identifier != null && <Badge variant="outline">ID {sub.identifier}</Badge>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {unknown > 0 && <p className="text-muted-foreground text-xs">{t("sessions.unknownHint", { count: unknown })}</p>}
    </div>
  )
}
