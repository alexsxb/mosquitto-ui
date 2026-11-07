import { useQueryClient } from "@tanstack/react-query"
import { CircleDotIcon, DownloadIcon, SquareIcon, Trash2Icon } from "lucide-react"
import { useEffect, useMemo, useRef, useState, type FormEvent } from "react"
import { useTranslation } from "react-i18next"
import { useSearchParams } from "react-router"
import { toast } from "sonner"

import { $api, fetchClient, type Schemas } from "@/api/client"
import { InspectUnavailable } from "@/components/inspect-unavailable"
import { SortableHead } from "@/components/sortable-head"
import { TablePagination } from "@/components/table-pagination"
import { SearchInput } from "@/components/search-input"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Spinner } from "@/components/ui/spinner"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { useErrorToast } from "@/lib/errors"
import { formatDateTime } from "@/lib/format"
import { useTable } from "@/lib/use-table"

type Trace = Schemas["Trace"]
type TraceEvent = Schemas["TraceEvent"]

const tracesKey = { queryKey: ["get", "/api/inspect/traces"] }

/** The types the plugin records (docker/mosquitto/inspect/inspect.c); any other shows as it is */
const TYPES = ["connect", "offline", "disconnect", "subscribe", "subscribeDenied", "unsubscribe", "publish", "publishDenied", "dropped", "deliver"] as const
type EventType = (typeof TYPES)[number]

function isType(type: string): type is EventType {
  return (TYPES as readonly string[]).includes(type)
}

const ENDS = ["stopped", "time", "limit", "replaced", "lost"] as const
type End = (typeof ENDS)[number]

function isEnd(end: string | null | undefined): end is End {
  return (ENDS as readonly (string | null | undefined)[]).includes(end)
}

const GROUPS = { all: null, messages: ["publish", "deliver"], sessions: ["connect", "offline", "disconnect"], subscriptions: ["subscribe", "unsubscribe"], denied: ["publishDenied", "subscribeDenied", "dropped"] } as const
type Group = keyof typeof GROUPS

const columns = { at: (e: TraceEvent) => Date.parse(e.at as string) }

function badgeVariant(type: string) {
  return type.endsWith("Denied") || type === "dropped" ? "destructive" : type === "publish" || type === "deliver" ? "secondary" : "outline"
}

/** The payload as text when it is UTF-8, else as hex */
function showPayload(base64: string | null | undefined) {
  if (!base64) {
    return null
  }
  const text = decoded(base64)
  if (text !== null) {
    return { text, hex: false }
  }
  const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))
  return { text: Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join(" "), hex: true }
}

/** The payload as the plugin sends it, base64, decoded to text when it is UTF-8 */
function decoded(base64: string) {
  const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes)
  } catch {
    return null
  }
}

/**
 * The events for the download. text: the payload as text where it is UTF-8, base64 where not; base64: as the plugin sends
 * it. payloadEncoding tells which each one is.
 */
function forDownload(events: TraceEvent[], text: boolean) {
  return events.map((e) => {
    if (!e.payload) {
      return e
    }
    const asText = text ? decoded(e.payload) : null
    return asText !== null ? { ...e, payload: asText, payloadEncoding: "utf-8" } : { ...e, payloadEncoding: "base64" }
  })
}

function time(value: string, language: string) {
  return new Intl.DateTimeFormat(language, { hour: "2-digit", minute: "2-digit", second: "2-digit", fractionalSecondDigits: 3 }).format(new Date(value))
}

/**
 * Recordings of what clients do, made by the inspect plugin of the broker: connect, subscribe, every message they send and
 * get, and what the broker refused them. One runs at a time; the last few stay in the UI until it restarts.
 */
export function TracePage() {
  const { t } = useTranslation()
  const [params, setParams] = useSearchParams()
  const status = $api.useQuery("get", "/api/inspect/status", {}, { refetchInterval: 10_000 })
  const available = status.data?.available === true
  const traces = $api.useQuery("get", "/api/inspect/traces", {}, { enabled: available, refetchInterval: 3_000 })
  const selectedId = params.get("id") ?? traces.data?.[0]?.id ?? null
  const selected = traces.data?.find((trace) => trace.id === selectedId) ?? null

  if (status.data && !available) {
    return <InspectUnavailable reason={status.data.reason} />
  }

  function select(id: string) {
    setParams({ id }, { replace: true })
  }

  return (
    <div className="space-y-6">
      <StartCard initialClient={params.get("clientid") ?? ""} running={traces.data?.some((trace) => trace.active) ?? false} onStarted={select} />
      {traces.data && traces.data.length > 1 && (
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-muted-foreground text-sm">{t("trace.earlier")}</span>
          <Select value={selectedId ?? ""} onValueChange={select}>
            <SelectTrigger className="w-80" aria-label={t("trace.earlier")}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {traces.data.map((trace) => (
                <SelectItem key={trace.id} value={trace.id}>
                  <TraceName trace={trace} />
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}
      {selected && <TraceCard key={selected.id} trace={selected} />}
    </div>
  )
}

function TraceName({ trace }: { trace: Trace }) {
  const { t, i18n } = useTranslation()
  const what = [trace.clientid, trace.topic].filter(Boolean).join(" · ")
  return (
    <>
      {formatDateTime(trace.started as string, i18n.language)} – {what} ({t("trace.events", { count: trace.count })})
    </>
  )
}

function StartCard({ initialClient, running, onStarted }: { initialClient: string; running: boolean; onStarted: (id: string) => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const start = $api.useMutation("post", "/api/inspect/traces")
  const [clientid, setClientid] = useState(initialClient)
  const [topic, setTopic] = useState("")
  const [minutes, setMinutes] = useState("5")
  const [maxEvents, setMaxEvents] = useState("5000")
  const [payloadBytes, setPayloadBytes] = useState("256")

  async function submit(event: FormEvent) {
    event.preventDefault()
    try {
      const trace = await start.mutateAsync({
        body: {
          clientid: clientid.trim() || null,
          topic: topic.trim() || null,
          seconds: Math.round(Number(minutes) * 60),
          maxEvents: Number(maxEvents),
          payloadBytes: Number(payloadBytes),
        },
      })
      toast.success(t("trace.started"))
      await queryClient.invalidateQueries(tracesKey)
      onStarted(trace.id)
    } catch (error) {
      errorToast(error)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("trace.title")}</CardTitle>
        <CardDescription>{t("trace.description")}</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={(event) => void submit(event)}>
          <FieldGroup>
            <div className="grid gap-4 md:grid-cols-2">
              <Field>
                <FieldLabel htmlFor="trace-client">{t("trace.clientid")}</FieldLabel>
                <Input id="trace-client" className="font-mono" value={clientid} placeholder="sensor-*" onChange={(e) => setClientid(e.target.value)} />
                <FieldDescription>{t("trace.clientidHint")}</FieldDescription>
              </Field>
              <Field>
                <FieldLabel htmlFor="trace-topic">{t("trace.topic")}</FieldLabel>
                <Input id="trace-topic" className="font-mono" value={topic} placeholder="sensors/#" onChange={(e) => setTopic(e.target.value)} />
                <FieldDescription>{t("trace.topicHint")}</FieldDescription>
              </Field>
            </div>
            <div className="grid gap-4 sm:grid-cols-3">
              <Field>
                <FieldLabel htmlFor="trace-minutes">{t("trace.minutes")}</FieldLabel>
                <Input id="trace-minutes" type="number" min={1} max={60} value={minutes} onChange={(e) => setMinutes(e.target.value)} />
              </Field>
              <Field>
                <FieldLabel htmlFor="trace-max">{t("trace.maxEvents")}</FieldLabel>
                <Input id="trace-max" type="number" min={1} max={50000} value={maxEvents} onChange={(e) => setMaxEvents(e.target.value)} />
              </Field>
              <Field>
                <FieldLabel htmlFor="trace-bytes">{t("trace.payloadBytes")}</FieldLabel>
                <Input id="trace-bytes" type="number" min={0} max={4096} value={payloadBytes} onChange={(e) => setPayloadBytes(e.target.value)} />
              </Field>
            </div>
            <div className="flex flex-wrap items-center gap-3">
              <Button type="submit" disabled={start.isPending || (!clientid.trim() && !topic.trim())}>
                {start.isPending ? <Spinner /> : <CircleDotIcon />}
                {t("trace.start")}
              </Button>
              {running && <span className="text-muted-foreground text-sm">{t("trace.replaces")}</span>}
            </div>
          </FieldGroup>
        </form>
      </CardContent>
    </Card>
  )
}

/** A recording with its events, fetched as they come while it runs */
function TraceCard({ trace }: { trace: Trace }) {
  const { t, i18n } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const stop = $api.useMutation("post", "/api/inspect/traces/{id}/stop")
  const remove = $api.useMutation("delete", "/api/inspect/traces/{id}")
  const [events, setEvents] = useState<TraceEvent[]>([])
  const [group, setGroup] = useState<Group>("all")
  const [search, setSearch] = useState("")
  /** How many events were fetched: also when the recording ends, only the new ones come */
  const loaded = useRef(0)

  // new events only: the ones before stay, a recording does not change what it has
  useEffect(() => {
    let cancelled = false
    async function load() {
      const after = loaded.current
      const { data } = await fetchClient.GET("/api/inspect/traces/{id}", { params: { path: { id: trace.id }, query: { after } } })
      if (cancelled || !data || loaded.current !== after) {
        return
      }
      loaded.current = data.total
      if (data.events.length > 0) {
        setEvents((old) => [...old, ...data.events])
      }
    }
    void load().catch(() => undefined)
    const timer = trace.active ? window.setInterval(() => void load().catch(() => undefined), 1_500) : undefined
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [trace.id, trace.active])

  const filtered = useMemo(() => {
    const types: readonly string[] | null = GROUPS[group]
    const term = search.trim().toLowerCase()
    return events.filter((e) => (!types || types.includes(e.type)) && [e.clientid, e.topic, e.username].some((v) => v?.toLowerCase().includes(term)))
  }, [events, group, search])
  const table = useTable("trace", filtered, columns, "at", "desc")

  async function stopTrace() {
    try {
      await stop.mutateAsync({ params: { path: { id: trace.id } } })
      await queryClient.invalidateQueries(tracesKey)
    } catch (error) {
      errorToast(error)
    }
  }

  async function removeTrace() {
    try {
      await remove.mutateAsync({ params: { path: { id: trace.id } } })
      await queryClient.invalidateQueries(tracesKey)
    } catch (error) {
      errorToast(error)
    }
  }

  function download(text: boolean) {
    const blob = new Blob([JSON.stringify({ trace, events: forDownload(events, text) }, null, 2)], { type: "application/json" })
    const url = URL.createObjectURL(blob)
    const link = document.createElement("a")
    link.href = url
    link.download = `trace-${trace.started as string}.json`.replaceAll(":", "-")
    link.click()
    URL.revokeObjectURL(url)
  }

  const end = isEnd(trace.end) ? trace.end : null

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2">
          {trace.active && <span className="size-2 animate-pulse rounded-full bg-red-500" />}
          {[trace.clientid, trace.topic].filter(Boolean).join(" · ")}
        </CardTitle>
        <CardDescription>
          {trace.active
            ? t("trace.runningUntil", { date: formatDateTime(trace.until as string, i18n.language) })
            : end
              ? t(`trace.ends.${end}`)
              : t("trace.ended")}{" "}
          · {t("trace.events", { count: trace.count })} · {t("trace.startedBy", { name: trace.startedBy, date: formatDateTime(trace.started as string, i18n.language) })}
          {trace.count >= trace.maxEvents && ` · ${t("trace.full")}`}
        </CardDescription>
        <CardAction className="flex gap-2">
          {trace.active ? (
            <Button variant="outline" size="sm" disabled={stop.isPending} onClick={() => void stopTrace()}>
              <SquareIcon />
              {t("trace.stop")}
            </Button>
          ) : (
            <Button variant="ghost" size="icon-sm" aria-label={t("trace.delete")} title={t("trace.delete")} onClick={() => void removeTrace()}>
              <Trash2Icon />
            </Button>
          )}
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline" size="sm" disabled={events.length === 0}>
                <DownloadIcon />
                {t("trace.download")}
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-56">
              <DropdownMenuItem onSelect={() => download(true)}>{t("trace.downloadText")}</DropdownMenuItem>
              <DropdownMenuItem onSelect={() => download(false)}>{t("trace.downloadBase64")}</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </CardAction>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex flex-wrap items-center gap-2">
          <SearchInput
            className="max-w-xs"
            value={search}
            placeholder={t("trace.search")}
            onValueChange={(value) => {
              setSearch(value)
              table.setPage(0)
            }}
          />
          <Select
            value={group}
            onValueChange={(value) => {
              setGroup(value as Group)
              table.setPage(0)
            }}
          >
            <SelectTrigger className="w-48" aria-label={t("trace.type")}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {(Object.keys(GROUPS) as Group[]).map((item) => (
                <SelectItem key={item} value={item}>
                  {t(`trace.groups.${item}`)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <span className="text-muted-foreground text-sm">{t("common.shown", { shown: filtered.length, total: events.length })}</span>
        </div>
        {filtered.length === 0 ? (
          <p className="text-muted-foreground py-6 text-center text-sm">{trace.active ? t("trace.waiting") : t("trace.none")}</p>
        ) : (
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <SortableHead className="hidden sm:table-cell" active direction={table.direction} onSort={() => table.toggle("at")}>
                    {t("trace.at")}
                  </SortableHead>
                  <TableHead>{t("trace.type")}</TableHead>
                  <TableHead className="hidden md:table-cell">{t("trace.client")}</TableHead>
                  <TableHead>{t("trace.what")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {table.rows.map((e, index) => (
                  <EventRow key={`${e.at as string}-${index}`} event={e} />
                ))}
              </TableBody>
            </Table>
          </div>
        )}
        <TablePagination page={table.page} pageSize={table.pageSize} total={table.total} onPage={table.setPage} onPageSize={table.setPageSize} />
      </CardContent>
    </Card>
  )
}

function EventRow({ event: e }: { event: TraceEvent }) {
  const { t, i18n } = useTranslation()
  const payload = showPayload(e.payload)
  const type = isType(e.type) ? e.type : null
  return (
    <TableRow>
      <TableCell className="hidden font-mono text-xs whitespace-nowrap sm:table-cell">{time(e.at as string, i18n.language)}</TableCell>
      <TableCell className="align-top sm:align-middle">
        <div className="mb-1 font-mono text-xs sm:hidden">{time(e.at as string, i18n.language)}</div>
        <Badge variant={badgeVariant(e.type)}>{type ? t(`trace.types.${type}`) : e.type}</Badge>
        {/* the column of the client is not there on a phone */}
        <div className="mt-1 max-w-32 truncate font-mono text-xs md:hidden" title={e.clientid}>
          {e.clientid}
        </div>
      </TableCell>
      <TableCell className="hidden max-w-56 truncate font-mono text-xs md:table-cell" title={e.clientid}>
        {e.clientid}
      </TableCell>
      <TableCell className="max-w-xl whitespace-normal sm:min-w-48">
        {e.topic && <div className="font-mono text-xs break-all">{e.topic}</div>}
        <div className="text-muted-foreground text-xs">
          {[
            e.qos != null && `QoS ${e.qos}`,
            e.retain && "retain",
            e.size != null && t("trace.bytes", { count: e.size }),
            e.rule && t("trace.rule", { rule: e.rule }),
            e.username && t("trace.user", { name: e.username }),
            e.address,
            e.protocol && t("sessions.protocol", { version: e.protocol }),
            e.keepalive != null && t("sessions.keepalive", { seconds: e.keepalive }),
            e.cleanStart === false && t("sessions.persistent"),
            e.reasonText,
          ]
            .filter(Boolean)
            .join(" · ")}
        </div>
        {payload && (
          <div className={`bg-muted mt-1 rounded px-2 py-1 font-mono text-xs break-all ${payload.hex ? "text-muted-foreground" : ""}`}>
            {payload.text}
            {e.truncated && " …"}
          </div>
        )}
      </TableCell>
    </TableRow>
  )
}
