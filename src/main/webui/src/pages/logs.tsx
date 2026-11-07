import { ArrowDownIcon, ChevronDownIcon, DownloadIcon, PauseIcon, PlayIcon, XIcon } from "lucide-react"
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react"
import { useTranslation } from "react-i18next"
import { Link, useSearchParams } from "react-router"

import { $api, type Schemas } from "@/api/client"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Label } from "@/components/ui/label"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { SearchInput } from "@/components/search-input"

type LogLine = Schemas["LogLine"]

/** The levels the broker publishes its log with (DynsecClient.level); any other shows as it is */
const LEVELS = ["error", "warning", "notice", "info", "subscribe", "unsubscribe", "websockets", "debug"] as const
type Level = (typeof LEVELS)[number]

function isLevel(level: string): level is Level {
  return (LEVELS as readonly string[]).includes(level)
}

/** What a line of mosquitto 2.1 says, as far as the page points it out */
type Kind = "connected" | "rejected" | "disconnected" | "timeout" | "bridge"

/**
 * Lines as mosquitto 2.1 writes them (src/loop.c), e.g. "Client shelly-1 [10.0.0.5:51234] disconnected: not authorised.";
 * a wrong password is "not authorised" as well. Older versions said "Client shelly-1 has exceeded timeout".
 */
function kindOf(text: string): Kind | null {
  if (text.startsWith("New client connected from ")) {
    return "connected"
  }
  if (/^Client \S+ .*disconnected: (not authori[sz]ed|bad username or password|banned)/.test(text)) {
    return "rejected"
  }
  if (/^Client \S+ (.*disconnected: exceeded timeout|has exceeded timeout)/.test(text)) {
    return "timeout"
  }
  if (/^Client \S+ .*(disconnected|closed its connection)/.test(text)) {
    return "disconnected"
  }
  if (/\bbridge\b/i.test(text)) {
    return "bridge"
  }
  return null
}

/** "New client connected from 10.0.0.5:51234 as shelly1 (p2, c1, k60, u'shelly')." */
const CONNECTED_AS = /^New client connected from \S+ as (\S+) \(.*\bu'([^']*)'/

/** The words of a line, without what ends a sentence: a client id stands alone, as in "Client shelly1 [10.0.0.5:51234] ..." */
function words(text: string) {
  return text.split(/\s+/).map((word) => word.replace(/[.,:;]+$/, ""))
}

/**
 * The client ids of the lines of one client: the one asked for, those that logged in with the user asked for, and the ones
 * known to be connected. Lines about a client only name its id, the user name is only in the line of its connection.
 */
function clientIds(lines: LogLine[], clientid: string | null, user: string | null, connected: string[]) {
  const ids = new Set<string>(connected)
  if (clientid) {
    ids.add(clientid)
  }
  if (user) {
    for (const line of lines) {
      const match = CONNECTED_AS.exec(line.text)
      if (match && match[2] === user) {
        ids.add(match[1])
      }
    }
  }
  return ids
}

const FILTERS = {
  all: () => true,
  problems: (line: LogLine, kind: Kind | null) => line.level === "error" || line.level === "warning" || kind === "rejected" || kind === "timeout",
  connections: (_: LogLine, kind: Kind | null) => kind === "connected" || kind === "rejected" || kind === "disconnected" || kind === "timeout",
  subscriptions: (line: LogLine) => line.level === "subscribe" || line.level === "unsubscribe",
  bridges: (_: LogLine, kind: Kind | null) => kind === "bridge",
} as const
type Filter = keyof typeof FILTERS

function kindVariant(kind: Kind) {
  return kind === "rejected" || kind === "timeout" ? "destructive" : kind === "connected" ? "secondary" : "outline"
}

/** More rows than this make the page slow; the download has all of them */
const RENDER_MAX = 2000

function levelClass(level: string) {
  return level === "error" ? "text-destructive font-medium" : level === "warning" ? "font-medium text-amber-600 dark:text-amber-400" : "text-muted-foreground"
}

function time(value: string, language: string) {
  return new Intl.DateTimeFormat(language, { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(new Date(value))
}

/** Mosquitto never publishes debug lines on a topic, the choice leaves them out */
const CHOOSABLE: readonly Level[] = LEVELS.filter((level) => level !== "debug")
const LEVELS_KEY = "mosquitto-ui.logs.levels"

/** The levels chosen last time in this browser; none: all */
function loadLevels(): string[] {
  try {
    const saved: unknown = JSON.parse(localStorage.getItem(LEVELS_KEY) ?? "[]")
    return Array.isArray(saved) ? saved.filter((level): level is string => typeof level === "string") : []
  } catch {
    return []
  }
}

function saveLevels(levels: string[]) {
  try {
    localStorage.setItem(LEVELS_KEY, JSON.stringify(levels))
  } catch {
    // only a convenience
  }
}

/** log_type as mosquitto.conf(5) names it, for each level of the page */
const LOG_TYPE: Record<Level, string> = {
  error: "error",
  warning: "warning",
  notice: "notice",
  info: "information",
  subscribe: "subscribe",
  unsubscribe: "unsubscribe",
  websockets: "websockets",
  debug: "debug",
}
/** What mosquitto writes without any log_type */
const DEFAULT_LOG_TYPES = ["error", "warning", "notice", "information"]

/** Whether the broker writes the level, after the log_type lines of the file of the UI; unknown levels count as written */
function writes(logTypes: string[], level: string) {
  const types = logTypes.length === 0 ? DEFAULT_LOG_TYPES : logTypes
  if (types.includes("none")) {
    return false
  }
  return !isLevel(level) || types.includes("all") || types.includes(LOG_TYPE[level])
}

type Status = { readable: boolean; connected: boolean; keep: number }

/**
 * The log of the broker, as it publishes it on $SYS/broker/log (log_dest topic in mosquitto.conf), like tail -f: the server
 * sends each new line as it comes (server-sent events), the newest is at the bottom, and the view follows it while it is at
 * the end. Paused, the lines are collected and shown on resume.
 */
export function LogsPage() {
  const { t, i18n } = useTranslation()
  const [lines, setLines] = useState<LogLine[] | null>(null)
  const [status, setStatus] = useState<Status | null>(null)
  const [streaming, setStreaming] = useState(false)
  const [paused, setPaused] = useState(false)
  const [waiting, setWaiting] = useState(0)
  const [follow, setFollow] = useState(true)
  const [search, setSearch] = useState("")
  const [filter, setFilter] = useState<Filter>("all")
  /** Empty: every level */
  const [levels, setLevels] = useState<string[]>(loadLevels)
  const pausedRef = useRef(false)
  const held = useRef<LogLine[]>([])
  const box = useRef<HTMLDivElement>(null)
  /** Only the lines of one client, from the pages Clients (user) and Sessions (clientid) */
  const [params, setParams] = useSearchParams()
  const onlyClient = params.get("clientid")
  const onlyUser = params.get("user")
  const connectedIds = useMemo(() => params.getAll("id"), [params])
  /** What the broker is set up to write; until it is known, every level */
  const config = $api.useQuery("get", "/api/config")
  const logTypes = config.data?.form.logTypes
  const written = (level: string) => !logTypes || writes(logTypes, level)
  const logOff = logTypes?.includes("none") ?? false

  useEffect(() => {
    const source = new EventSource("/api/logs/stream")
    source.onopen = () => setStreaming(true)
    source.onmessage = (event: MessageEvent<string>) => {
      const batch = JSON.parse(event.data) as Schemas["LogBatch"]
      setStreaming(true)
      setStatus({ readable: batch.readable, connected: batch.connected, keep: batch.keep })
      const cap = (all: LogLine[]) => (all.length > batch.keep ? all.slice(all.length - batch.keep) : all)
      if (batch.reset) {
        // the first batch, also after the stream came back: all that the UI kept
        held.current = []
        setWaiting(0)
        setLines(batch.lines)
      } else if (batch.lines.length === 0) {
        return
      } else if (pausedRef.current) {
        held.current = cap([...held.current, ...batch.lines])
        setWaiting(held.current.length)
      } else {
        setLines((old) => cap([...(old ?? []), ...batch.lines]))
      }
    }
    // EventSource tries again by itself
    source.onerror = () => setStreaming(false)
    return () => source.close()
  }, [])

  function togglePause() {
    if (paused) {
      const more = held.current
      held.current = []
      setWaiting(0)
      setLines((old) => {
        const all = [...(old ?? []), ...more]
        const keep = status?.keep ?? all.length
        return all.length > keep ? all.slice(all.length - keep) : all
      })
      setFollow(true)
    }
    pausedRef.current = !paused
    setPaused(!paused)
  }

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase()
    const all = lines ?? []
    const ids = onlyClient || onlyUser ? clientIds(all, onlyClient, onlyUser, connectedIds) : null
    return all
      .map((line) => ({ line, kind: kindOf(line.text) }))
      .filter(
        ({ line, kind }) =>
          (levels.length === 0 || levels.includes(line.level)) &&
          FILTERS[filter](line, kind) &&
          (!term || line.text.toLowerCase().includes(term)) &&
          (!ids || words(line.text).some((word) => ids.has(word))),
      )
  }, [lines, filter, search, levels, onlyClient, onlyUser, connectedIds])
  /** How many lines each level has, for the choice; levels the page does not know are added */
  const counts = useMemo(() => {
    const all = new Map<string, number>(CHOOSABLE.map((level) => [level, 0]))
    for (const line of lines ?? []) {
      all.set(line.level, (all.get(line.level) ?? 0) + 1)
    }
    return all
  }, [lines])

  function changeLevels(next: string[]) {
    setLevels(next)
    saveLevels(next)
  }

  const levelName = (level: string) => (isLevel(level) ? t(`logs.levels.${level}`) : level)
  const shown = filtered.length > RENDER_MAX ? filtered.slice(filtered.length - RENDER_MAX) : filtered

  // at the end, it stays at the end
  useLayoutEffect(() => {
    if (follow && box.current) {
      box.current.scrollTop = box.current.scrollHeight
    }
  }, [shown, follow])

  function onScroll() {
    const el = box.current
    if (el) {
      setFollow(el.scrollHeight - el.scrollTop - el.clientHeight < 32)
    }
  }

  function toEnd() {
    setFollow(true)
    if (box.current) {
      box.current.scrollTop = box.current.scrollHeight
    }
  }

  function download() {
    const text = filtered.map(({ line }) => `${line.time as string} ${line.level.padEnd(11)} ${line.text}`).join("\n") + "\n"
    const url = URL.createObjectURL(new Blob([text], { type: "text/plain" }))
    const link = document.createElement("a")
    link.href = url
    link.download = `mosquitto-${new Date().toISOString()}.log`.replaceAll(":", "-")
    link.click()
    URL.revokeObjectURL(url)
  }

  const state = !streaming ? "away" : paused ? "paused" : "live"

  return (
    <>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <SearchInput className="max-w-xs" value={search} placeholder={t("logs.search")} onValueChange={setSearch} />
        <Popover>
          <PopoverTrigger asChild>
            <Button variant="outline" className="w-44 justify-between font-normal" aria-label={t("logs.levelFilter")}>
              <span className="truncate">
                {levels.length === 0 ? t("logs.allLevels") : levels.length === 1 ? levelName(levels[0]) : t("logs.someLevels", { count: levels.length })}
              </span>
              <ChevronDownIcon className="size-4 opacity-50" />
            </Button>
          </PopoverTrigger>
          <PopoverContent align="start" className="w-60 p-2">
            <div className="space-y-1">
              {[...counts.entries()].map(([level, count]) => {
                const on = written(level)
                // an off level with old lines stays choosable, and a chosen one can always be taken away
                const choosable = on || count > 0 || levels.includes(level)
                return (
                  <div
                    key={level}
                    className={`flex items-center gap-2 rounded-md p-1.5 ${choosable ? "hover:bg-muted" : ""} ${on ? "" : "opacity-50"}`}
                    title={on ? undefined : t("logs.levelOffHint")}
                  >
                    <Checkbox
                      id={`log-level-${level}`}
                      checked={levels.includes(level)}
                      disabled={!choosable}
                      onCheckedChange={(checked) => changeLevels(checked ? [...levels, level] : levels.filter((l) => l !== level))}
                    />
                    <Label htmlFor={`log-level-${level}`} className={`flex-1 font-normal ${levelClass(level)}`}>
                      {levelName(level)}
                      {!on && <span className="text-muted-foreground ml-1.5 text-xs">{t("logs.levelOff")}</span>}
                    </Label>
                    <span className="text-muted-foreground text-xs tabular-nums">{count}</span>
                  </div>
                )
              })}
            </div>
            {logTypes && CHOOSABLE.some((level) => !written(level)) && (
              <p className="text-muted-foreground mt-1 border-t px-1.5 pt-2 text-xs">
                {t("logs.levelsOffHint")}{" "}
                <Link to="/config" className="underline underline-offset-4">
                  {t("logs.toConfig")}
                </Link>
              </p>
            )}
            {levels.length > 0 && (
              <Button variant="ghost" size="sm" className="mt-1 w-full" onClick={() => changeLevels([])}>
                {t("logs.allLevels")}
              </Button>
            )}
          </PopoverContent>
        </Popover>
        <Select value={filter} onValueChange={(value) => setFilter(value as Filter)}>
          <SelectTrigger className="w-48" aria-label={t("logs.filter")}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {(Object.keys(FILTERS) as Filter[]).map((item) => (
              <SelectItem key={item} value={item}>
                {t(`logs.filters.${item}`)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        {(onlyClient || onlyUser) && (
          <Badge variant="secondary" className="h-8 gap-1 pr-1 font-normal">
            {onlyClient ? t("logs.onlyClient", { name: onlyClient }) : t("logs.onlyUser", { name: onlyUser })}
            <Button variant="ghost" size="icon-sm" className="size-6" aria-label={t("logs.allClients")} title={t("logs.allClients")} onClick={() => setParams({})}>
              <XIcon />
            </Button>
          </Badge>
        )}
        {lines && <span className="text-muted-foreground text-sm">{t("common.shown", { shown: filtered.length, total: lines.length })}</span>}
        <div className="ml-auto flex flex-wrap items-center gap-2">
          <span className="text-muted-foreground flex items-center gap-1.5 text-sm">
            <span className={`size-2 rounded-full ${state === "live" ? "animate-pulse bg-green-500" : state === "paused" ? "bg-amber-500" : "bg-red-500"}`} />
            {t(`logs.states.${state}`)}
            {paused && waiting > 0 && ` · ${t("logs.waiting", { count: waiting })}`}
          </span>
          <Button variant={paused ? "default" : "outline"} size="sm" onClick={togglePause}>
            {paused ? <PlayIcon /> : <PauseIcon />}
            {paused ? t("logs.resume") : t("logs.pause")}
          </Button>
          <Button variant="outline" size="sm" disabled={filtered.length === 0} onClick={download}>
            <DownloadIcon />
            {t("logs.download")}
          </Button>
        </div>
      </div>

      {logOff && (
        <Alert variant="destructive" className="mb-3">
          <AlertTitle>{t("logs.logOffTitle")}</AlertTitle>
          <AlertDescription>
            <p>
              {t("logs.logOff")}{" "}
              <Link to="/config" className="underline underline-offset-4">
                {t("logs.toConfig")}
              </Link>
            </p>
          </AlertDescription>
        </Alert>
      )}

      {status && !status.readable && status.connected && (
        <Alert variant="destructive" className="mb-3">
          <AlertTitle>{t("logs.notReadableTitle")}</AlertTitle>
          <AlertDescription>{t("logs.notReadable")}</AlertDescription>
        </Alert>
      )}

      {lines === null ? (
        <Skeleton className="h-80 w-full" />
      ) : (
        <div className="relative">
          <div
            ref={box}
            onScroll={onScroll}
            className="bg-card h-[calc(100dvh-20rem)] min-h-72 overflow-y-auto rounded-lg border font-mono text-xs md:h-[calc(100dvh-12rem)]"
          >
            {filtered.length === 0 ? (
              <p className="text-muted-foreground px-4 py-8 text-center font-sans text-sm">{lines.length === 0 ? t("logs.none") : levels.length > 0 && levels.every((level) => !written(level)) ? t("logs.noneLevelsOff") : t("logs.noneFiltered")}</p>
            ) : (
              <>
                {filtered.length > shown.length && (
                  <p className="text-muted-foreground border-b px-3 py-2 font-sans">{t("logs.older", { count: filtered.length - shown.length })}</p>
                )}
                {shown.map(({ line, kind }) => {
                  const level = levelName(line.level)
                  return (
                    <div key={line.id} className="hover:bg-muted/50 flex flex-col gap-x-3 border-b px-3 py-1 last:border-b-0 sm:flex-row sm:items-baseline">
                      <div className="flex shrink-0 gap-3 sm:contents">
                        <span className="text-muted-foreground whitespace-nowrap tabular-nums">{time(line.time as string, i18n.language)}</span>
                        <span className={`w-24 shrink-0 truncate ${levelClass(line.level)}`}>{level}</span>
                      </div>
                      <div className="min-w-0 flex-1 break-all">
                        {kind && (
                          <Badge variant={kindVariant(kind)} className="mr-2 align-middle font-sans">
                            {t(`logs.kinds.${kind}`)}
                          </Badge>
                        )}
                        {line.text}
                      </div>
                    </div>
                  )
                })}
              </>
            )}
          </div>
          {!follow && filtered.length > 0 && (
            <Button size="sm" className="absolute right-4 bottom-4 shadow-md" onClick={toEnd}>
              <ArrowDownIcon />
              {t("logs.toEnd")}
            </Button>
          )}
        </div>
      )}
      <p className="text-muted-foreground mt-2 text-xs">{t("logs.hint", { count: status?.keep ?? 10000 })}</p>
    </>
  )
}
