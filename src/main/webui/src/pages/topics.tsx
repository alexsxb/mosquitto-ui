import { ChevronRightIcon, CopyIcon, PinIcon, PinOffIcon, Trash2Icon, TriangleAlertIcon } from "lucide-react"
import { useEffect, useMemo, useRef, useState, type FormEvent } from "react"
import { useTranslation } from "react-i18next"
import { useSearchParams } from "react-router"
import { toast } from "sonner"

import { $api } from "@/api/client"
import { ConfirmDialog } from "@/components/confirm-dialog"
import { LineChart } from "@/components/line-chart"
import { RefreshButton } from "@/components/refresh-button"
import { SearchInput } from "@/components/search-input"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Spinner } from "@/components/ui/spinner"
import { Switch } from "@/components/ui/switch"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Textarea } from "@/components/ui/textarea"
import { useErrorMessage, useErrorToast } from "@/lib/errors"
import {
  apply,
  createRoot,
  defaultField,
  formatSize,
  matches,
  numericFields,
  pathsOf,
  prettyJson,
  seriesOf,
  sortedChildren,
  type TopicMessage,
  type TopicNode,
  type Update,
} from "@/lib/topic-tree"
import { cn } from "@/lib/utils"

interface Batch {
  updates: Update[]
  topics: number
  connected: boolean
  error?: string | null
}

/** A busy broker should not redraw the page with every event. */
const DRAW_MS = 1000
const FLASH_MS = 1500
const MAX_CHILDREN = 500
const MAX_INLINE = 80
const RATE_WINDOW_MS = 5000
const PINNED_KEY = "mosquitto-ui.pinned-charts"

interface Pinned {
  path: string
  field: string
}

function loadPinned(): Pinned[] {
  try {
    return JSON.parse(localStorage.getItem(PINNED_KEY) ?? "[]") as Pinned[]
  } catch {
    return []
  }
}

function savePinned(pinned: Pinned[]) {
  try {
    localStorage.setItem(PINNED_KEY, JSON.stringify(pinned))
  } catch {
    // only a convenience
  }
}

export function TopicsPage() {
  const { t, i18n } = useTranslation()
  const root = useRef(createRoot())
  const [, setRevision] = useState(0)
  const [connected, setConnected] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [rate, setRate] = useState(0)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [selected, setSelected] = useState<string | null>(null)
  const [search, setSearch] = useState("")
  const [pinned, setPinned] = useState(loadPinned)
  const [params, setParams] = useSearchParams()
  const retainedOnly = params.get("retained") === "true"
  const errorMessage = useErrorMessage()
  // the connection of this page does the work, and the flags of the tree alone are not reliable
  const retained = $api.useQuery("get", "/api/topics/retained", {}, { enabled: retainedOnly && connected })
  const retainedPaths = useMemo(() => pathsOf(retained.data ?? []), [retained.data])
  // read by the stream, which stays open while these change
  const watched = useRef(new Set<string>())

  useEffect(() => {
    watched.current = new Set([...pinned.map((pin) => pin.path), ...(selected === null ? [] : [selected])])
  }, [pinned, selected])

  function changePinned(next: Pinned[]) {
    setPinned(next)
    savePinned(next)
  }

  useEffect(() => {
    const tree = root.current
    const source = new EventSource("/api/topics/stream")
    let lastMessages = 0
    let lastTime = Date.now()
    source.onmessage = (event: MessageEvent<string>) => {
      const batch = JSON.parse(event.data) as Batch
      apply(tree, batch.updates, watched.current)
      setConnected(batch.connected)
      setError(batch.error ?? null)
    }
    source.onerror = () => setConnected(false)
    const draw = setInterval(() => setRevision((revision) => revision + 1), DRAW_MS)
    const measure = setInterval(() => {
      const now = Date.now()
      setRate(Math.round(((tree.messages - lastMessages) * 1000) / (now - lastTime)))
      lastMessages = tree.messages
      lastTime = now
    }, RATE_WINDOW_MS)
    return () => {
      source.close()
      clearInterval(draw)
      clearInterval(measure)
    }
  }, [])

  const term = search.trim().toLowerCase()
  const tree = root.current
  // a new object for each redraw would not help: the tree changes in place, and the search has to see it
  const searched = term ? matches(tree, term) : null
  const combined = searched ? new Set([...searched].filter((path) => retainedPaths.has(path))) : retainedPaths
  const hits = retainedOnly ? combined : searched
  const node = selected === null ? null : findNode(tree, selected)

  function toggle(path: string) {
    const next = new Set(expanded)
    if (!next.delete(path)) {
      next.add(path)
    }
    setExpanded(next)
  }

  return (
    <div className="space-y-4">
      {error && (
        <Alert variant="destructive">
          <TriangleAlertIcon />
          <AlertDescription className="break-words">{error}</AlertDescription>
        </Alert>
      )}
      {retainedOnly && retained.isError && (
        <Alert variant="destructive">
          <TriangleAlertIcon />
          <AlertDescription className="break-words">{errorMessage(retained.error)}</AlertDescription>
        </Alert>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <SearchInput className="max-w-sm" placeholder={t("topics.search")} value={search} onValueChange={setSearch} />
        <label className="flex items-center gap-2 text-sm">
          <Switch checked={retainedOnly} onCheckedChange={(checked) => setParams(checked ? { retained: "true" } : {}, { replace: true })} />
          {t("topics.retainedOnly")}
        </label>
        {retainedOnly && (retained.isFetching ? <Spinner /> : retained.data && <span className="text-sm text-muted-foreground">{t("topics.retainedFound", { count: retained.data.length })}</span>)}
        {retainedOnly && <RefreshButton queries={[retained]} />}
        <div className="ml-auto flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          <span>{t("topics.subtopics", { count: tree.topics })}</span>
          <span>{t("topics.messages", { count: tree.messages })}</span>
          <span>{t("topics.perSecond", { count: rate })}</span>
          <Badge variant={connected ? "default" : "destructive"}>{t(connected ? "topics.connected" : "topics.disconnected")}</Badge>
        </div>
      </div>
      <p className="text-sm text-muted-foreground">{t("topics.hint")}</p>
      {pinned.length > 0 && (
        <div className="grid gap-4 lg:grid-cols-2">
          {pinned.map((pin) => {
            const pinnedNode = findNode(tree, pin.path)
            const series = pinnedNode ? seriesOf(pinnedNode, pin.field) : []
            return (
              <Card key={`${pin.path}|${pin.field}`} size="sm" className="min-w-0">
                <CardHeader>
                  <CardTitle className="flex items-start justify-between gap-2 font-mono text-xs break-all">
                    {pin.path}
                    {pin.field && ` · ${pin.field}`}
                    <Button
                      variant="ghost"
                      size="icon-xs"
                      aria-label={t("topics.unpin")}
                      title={t("topics.unpin")}
                      onClick={() => changePinned(pinned.filter((other) => other !== pin))}
                    >
                      <PinOffIcon />
                    </Button>
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  {series.length > 0 ? (
                    <LineChart series={series} label={`${t("topics.chart")} ${pin.path}`} locale={i18n.resolvedLanguage} />
                  ) : (
                    <p className="py-4 text-center text-sm text-muted-foreground">{t("topics.empty")}</p>
                  )}
                </CardContent>
              </Card>
            )
          })}
        </div>
      )}
      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,26rem)]">
        <div className="max-h-[70vh] min-w-0 overflow-auto rounded-lg border p-2 font-mono text-xs">
          {tree.children.size === 0 ? (
            <p className="p-4 text-center font-sans text-sm text-muted-foreground">{t("topics.empty")}</p>
          ) : hits?.size === 0 ? (
            <p className="p-4 text-center font-sans text-sm text-muted-foreground">{t("topics.noResults")}</p>
          ) : (
            <Branch node={tree} depth={0} expanded={expanded} hits={hits} selected={selected} onToggle={toggle} onSelect={setSelected} />
          )}
        </div>
        <div className="min-w-0 space-y-4">
          <Details
            node={node}
            pinned={pinned}
            onPin={(pin) => changePinned([...pinned, pin])}
            onCleared={() => setRevision((revision) => revision + 1)}
          />
          <Publish key={selected} topic={selected ?? ""} disabled={!connected} />
        </div>
      </div>
    </div>
  )
}

function findNode(root: TopicNode, path: string) {
  let node: TopicNode | undefined = root
  for (const name of path.split("/")) {
    node = node?.children.get(name)
  }
  return node ?? null
}

function Branch({
  node,
  depth,
  expanded,
  hits,
  selected,
  onToggle,
  onSelect,
}: {
  node: TopicNode
  depth: number
  expanded: Set<string>
  hits: Set<string> | null
  selected: string | null
  onToggle: (path: string) => void
  onSelect: (path: string) => void
}) {
  const { t } = useTranslation()
  const children = sortedChildren(node).filter((child) => !hits || hits.has(child.path))
  return (
    <ul>
      {children.slice(0, MAX_CHILDREN).map((child) => {
        const open = hits !== null || expanded.has(child.path)
        const branch = child.children.size > 0
        return (
          <li key={child.name}>
            <div
              className={cn(
                "flex min-w-0 items-center gap-1 rounded px-1 py-0.5 hover:bg-muted",
                selected === child.path && "bg-muted",
                Date.now() - child.touched < FLASH_MS && "bg-primary/10",
              )}
              style={{ paddingLeft: `${depth * 14 + 4}px` }}
            >
              <button
                type="button"
                aria-label={child.path}
                aria-expanded={branch ? open : undefined}
                className={cn("shrink-0 rounded", !branch && "invisible")}
                onClick={() => onToggle(child.path)}
              >
                <ChevronRightIcon className={cn("size-3.5 transition-transform", open && "rotate-90")} />
              </button>
              <button
                type="button"
                className="flex min-w-0 flex-1 items-baseline gap-2 text-left"
                onClick={() => {
                  onSelect(child.path)
                  if (branch && !open) {
                    onToggle(child.path)
                  }
                }}
              >
                <span className="shrink-0 font-medium">{child.name || "∅"}</span>
                {child.message && !child.message.binary && <span className="truncate text-muted-foreground">= {child.message.value?.slice(0, MAX_INLINE)}</span>}
                {child.message?.binary && <span className="truncate text-muted-foreground">= {formatSize(child.message.size)}</span>}
                {branch && (
                  <span className="shrink-0 font-sans text-muted-foreground">
                    ({t("topics.subtopics", { count: child.topics })}, {t("topics.messages", { count: child.messages })})
                  </span>
                )}
              </button>
            </div>
            {branch && open && <Branch node={child} depth={depth + 1} expanded={expanded} hits={hits} selected={selected} onToggle={onToggle} onSelect={onSelect} />}
          </li>
        )
      })}
      {children.length > MAX_CHILDREN && (
        <li className="px-1 py-0.5 font-sans text-muted-foreground" style={{ paddingLeft: `${depth * 14 + 22}px` }}>
          {t("topics.more", { count: children.length - MAX_CHILDREN })}
        </li>
      )}
    </ul>
  )
}

function Details({ node, pinned, onPin, onCleared }: { node: TopicNode | null; pinned: Pinned[]; onPin: (pin: Pinned) => void; onCleared: () => void }) {
  const { t, i18n } = useTranslation()
  const errorToast = useErrorToast()
  const countRetained = $api.useMutation("post", "/api/topics/count-retained")
  const clear = $api.useMutation("post", "/api/topics/clear-retained")
  const [view, setView] = useState("json")
  const [chosenField, setChosenField] = useState<string | null>(null)
  const [confirming, setConfirming] = useState<number | null>(null)
  const message = node?.message ?? null
  const json = useMemo(() => prettyJson(message?.value ?? null), [message?.value])

  async function copy(text: string) {
    await navigator.clipboard.writeText(text)
    toast.success(t("topics.copied"))
  }

  if (!node) {
    return (
      <Card>
        <CardContent className="py-8 text-center text-sm text-muted-foreground">{t("topics.noSelection")}</CardContent>
      </Card>
    )
  }
  const clock = (time: number) => new Date(time).toLocaleTimeString(i18n.resolvedLanguage)
  const fields = Object.keys(node.points.length > 0 ? numericFields(node.points[node.points.length - 1][1]) : {})
  const field = chosenField !== null && fields.includes(chosenField) ? chosenField : defaultField(fields)
  const series = field === undefined ? [] : seriesOf(node, field)
  const isPinned = pinned.some((pin) => pin.path === node.path && pin.field === field)
  const brokerTopic = node.path.startsWith("$")

  /** The broker knows, the tree only what it was told. */
  async function askToClear() {
    if (!node) {
      return
    }
    try {
      const { count } = await countRetained.mutateAsync({ body: { topic: node.path } })
      if (count === 0) {
        toast.info(t("topics.clearNone"))
      } else {
        setConfirming(count)
      }
    } catch (error) {
      errorToast(error)
    }
  }

  async function clearRetained() {
    if (!node) {
      return
    }
    setConfirming(null)
    try {
      const result = await clear.mutateAsync({ body: { topic: node.path } })
      toast.success(t("topics.clearDone", { count: result.count }))
      onCleared()
    } catch (error) {
      errorToast(error)
    }
  }

  return (
    <Card className="min-w-0">
      <CardHeader>
        <CardTitle className="flex items-start justify-between gap-2 font-mono text-sm break-all">
          {node.path}
          <Button variant="ghost" size="icon-xs" aria-label={t("topics.copyTopic")} title={t("topics.copyTopic")} onClick={() => void copy(node.path)}>
            <CopyIcon />
          </Button>
        </CardTitle>
        <div className="flex flex-wrap gap-1">
          {message && <Badge variant="secondary">{t("topics.qos", { qos: message.qos })}</Badge>}
          {message?.retained && <Badge>{t("topics.retained")}</Badge>}
          <Badge variant="secondary">{t("topics.messages", { count: node.messages })}</Badge>
          {node.children.size > 0 && <Badge variant="secondary">{t("topics.subtopics", { count: node.topics })}</Badge>}
        </div>
      </CardHeader>
      <CardContent className="space-y-4 text-sm">
        {message && (
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
            <dt className="text-muted-foreground">{t("topics.received")}</dt>
            <dd>{clock(message.time)}</dd>
            <dt className="text-muted-foreground">{t("topics.size")}</dt>
            <dd>{formatSize(message.size)}</dd>
          </dl>
        )}
        {message && (
          <div className="space-y-2">
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium">{t("topics.value")}</span>
              <div className="flex items-center gap-1">
                {json && (
                  <Tabs value={view} onValueChange={setView}>
                    <TabsList>
                      <TabsTrigger value="json">{t("topics.json")}</TabsTrigger>
                      <TabsTrigger value="raw">{t("topics.raw")}</TabsTrigger>
                    </TabsList>
                  </Tabs>
                )}
                <Button variant="ghost" size="icon-xs" aria-label={t("topics.copyValue")} title={t("topics.copyValue")} onClick={() => void copy(message.value ?? "")}>
                  <CopyIcon />
                </Button>
              </div>
            </div>
            {message.binary && <p className="text-xs text-muted-foreground">{t("topics.binary")}</p>}
            {message.cut && <p className="text-xs text-muted-foreground">{t("topics.cut", { size: formatSize(message.size) })}</p>}
            <pre className="max-h-72 overflow-auto rounded-lg border p-3 font-mono text-xs break-all whitespace-pre-wrap">{view === "json" && json ? json : message.value}</pre>
          </div>
        )}
        {field !== undefined && series.length > 0 && (
          <div className="space-y-2">
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium">{t("topics.chart")}</span>
              <div className="flex min-w-0 items-center gap-1">
                {fields.length > 1 && (
                  <Select value={field} onValueChange={setChosenField}>
                    <SelectTrigger size="sm" aria-label={t("topics.chartField")} className="max-w-40">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {fields.map((name) => (
                        <SelectItem key={name} value={name}>
                          {name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                )}
                <Button
                  variant="ghost"
                  size="icon-xs"
                  aria-label={t("topics.pin")}
                  title={t("topics.pin")}
                  disabled={isPinned}
                  onClick={() => onPin({ path: node.path, field })}
                >
                  <PinIcon />
                </Button>
              </div>
            </div>
            <LineChart series={series} label={`${t("topics.chart")} ${node.path}`} locale={i18n.resolvedLanguage} />
            <p className="text-xs text-muted-foreground">{t("topics.chartHint")}</p>
          </div>
        )}
        {node.history.length > 1 && (
          <div className="space-y-2">
            <p className="font-medium">{t("topics.history")}</p>
            <p className="text-xs text-muted-foreground">{t("topics.historyHint")}</p>
            <ul className="max-h-56 space-y-1 overflow-auto rounded-lg border p-2 font-mono text-xs">
              {node.history.map((entry: TopicMessage, index) => (
                <li key={index} className="flex gap-2">
                  <span className="shrink-0 text-muted-foreground">{clock(entry.time)}</span>
                  <span className="truncate">{entry.value}</span>
                </li>
              ))}
            </ul>
          </div>
        )}
        {!brokerTopic && (
          <Button variant="destructive" size="sm" disabled={clear.isPending || countRetained.isPending} onClick={() => void askToClear()}>
            {clear.isPending || countRetained.isPending ? <Spinner /> : <Trash2Icon />}
            {t("topics.clearRetained")}
          </Button>
        )}
      </CardContent>
      <ConfirmDialog
        open={confirming !== null}
        onOpenChange={(open) => !open && setConfirming(null)}
        title={t("topics.clearTitle")}
        description={t("topics.clearDesc", { count: confirming ?? 0, topic: node.path })}
        confirmLabel={t("topics.clearConfirm")}
        onConfirm={() => void clearRetained()}
      />
    </Card>
  )
}

function Publish({ topic: initialTopic, disabled }: { topic: string; disabled: boolean }) {
  const { t } = useTranslation()
  const errorToast = useErrorToast()
  const publish = $api.useMutation("post", "/api/topics/publish")
  const [topic, setTopic] = useState(initialTopic.startsWith("$") ? "" : initialTopic)
  const [value, setValue] = useState("")
  const [qos, setQos] = useState("0")
  const [retain, setRetain] = useState(false)

  function format() {
    try {
      setValue(JSON.stringify(JSON.parse(value), null, 2))
    } catch {
      toast.error(t("topics.invalidJson"))
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    try {
      await publish.mutateAsync({ body: { topic, value, qos: Number(qos), retain } })
      toast.success(t("topics.published"))
    } catch (error) {
      errorToast(error)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("topics.publish")}</CardTitle>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit} className="space-y-3">
          <Field>
            <FieldLabel htmlFor="publish-topic">{t("topics.topic")}</FieldLabel>
            <Input id="publish-topic" required autoComplete="off" pattern="[^$#+][^#+]*" className="font-mono text-xs" value={topic} onChange={(e) => setTopic(e.target.value)} />
          </Field>
          <Field>
            <FieldLabel htmlFor="publish-value">{t("topics.value")}</FieldLabel>
            <Textarea id="publish-value" rows={4} spellCheck={false} className="font-mono text-xs" value={value} onChange={(e) => setValue(e.target.value)} />
          </Field>
          <div className="flex flex-wrap items-center gap-4">
            <Select value={qos} onValueChange={setQos}>
              <SelectTrigger size="sm" aria-label="QoS" className="w-24">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {["0", "1", "2"].map((level) => (
                  <SelectItem key={level} value={level}>
                    {t("topics.qos", { qos: level })}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <div className="flex items-center gap-2">
              <Switch id="publish-retain" checked={retain} onCheckedChange={setRetain} />
              <FieldLabel htmlFor="publish-retain">{t("topics.publishRetain")}</FieldLabel>
            </div>
            <Button type="button" variant="ghost" size="sm" onClick={format}>
              {t("topics.format")}
            </Button>
          </div>
          <FieldDescription>{t("topics.publishHint")}</FieldDescription>
          <Button type="submit" disabled={disabled || publish.isPending}>
            {publish.isPending && <Spinner />}
            {t("topics.publish")}
          </Button>
        </form>
      </CardContent>
    </Card>
  )
}
