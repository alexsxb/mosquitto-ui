import { useQueryClient } from "@tanstack/react-query"
import { FileCodeIcon, NetworkIcon, PlusIcon, RotateCcwIcon, SlidersHorizontalIcon, Trash2Icon, TriangleAlertIcon } from "lucide-react"
import { useMemo, useState } from "react"
import type { TFunction } from "i18next"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"

import { $api, ApiError, type Schemas } from "@/api/client"
import { ConfirmDialog } from "@/components/confirm-dialog"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Field, FieldContent, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { MoreSettings } from "@/components/more-settings"
import { MultiSelect } from "@/components/multi-select"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { Spinner } from "@/components/ui/spinner"
import { Switch } from "@/components/ui/switch"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Textarea } from "@/components/ui/textarea"
import { useErrorToast } from "@/lib/errors"

type Form = Schemas["ConfigForm"]
type Check = Schemas["ConfigCheck"]
type Listener = Schemas["ListenerForm"]

const logTypes = ["error", "warning", "notice", "information", "subscribe", "unsubscribe", "websockets", "debug"]
const DEFAULT = "default"
const CUSTOM = "custom"
const NEW_LISTENER_PORT = 8883

/** The combinations people want; what they do with the checklist of mosquitto.conf(5) stays possible as "custom". */
const logLevels = [
  { id: "none", types: ["none"] },
  { id: "errors", types: ["error"] },
  { id: "warnings", types: ["error", "warning"] },
  { id: "normal", types: ["error", "warning", "notice"] },
  { id: "verbose", types: ["error", "warning", "notice", "information"] },
  { id: "all", types: ["all"] },
] as const

function logLevelOf(types: string[]) {
  if (types.length === 0) {
    return DEFAULT
  }
  return logLevels.find((level) => level.types.length === types.length && level.types.every((type) => types.includes(type)))?.id ?? CUSTOM
}

type OptionType = "bool" | "number" | "size" | "duration" | "text"

interface Option {
  key: string
  type: OptionType
  /** Of the broker, from mosquitto.conf(5); 0 for a limit is none. */
  defaultValue?: string
  placeholder?: string
}

const optionGroups: { id: "clients" | "messages" | "system"; options: Option[] }[] = [
  {
    id: "clients",
    options: [
      { key: "allow_anonymous", type: "bool", defaultValue: "false" },
      { key: "allow_zero_length_clientid", type: "bool", defaultValue: "true" },
      { key: "persistent_client_expiration", type: "duration" },
      { key: "max_keepalive", type: "number", defaultValue: "0" },
    ],
  },
  {
    id: "messages",
    options: [
      { key: "retain_available", type: "bool", defaultValue: "true" },
      { key: "max_packet_size", type: "size", defaultValue: "2000000" },
      { key: "max_queued_messages", type: "number", defaultValue: "1000" },
      { key: "max_queued_bytes", type: "size", defaultValue: "0" },
      { key: "max_inflight_messages", type: "number", defaultValue: "20" },
      { key: "max_inflight_bytes", type: "size", defaultValue: "0" },
      { key: "queue_qos0_messages", type: "bool", defaultValue: "false" },
      { key: "upgrade_outgoing_qos", type: "bool", defaultValue: "false" },
    ],
  },
  {
    id: "system",
    options: [
      { key: "sys_interval", type: "number", defaultValue: "10" },
      { key: "memory_limit", type: "size", defaultValue: "0" },
      { key: "set_tcp_nodelay", type: "bool", defaultValue: "false" },
    ],
  },
]

const logOptions: Option[] = [
  { key: "connection_messages", type: "bool", defaultValue: "true" },
  { key: "log_timestamp", type: "bool", defaultValue: "true" },
  { key: "log_timestamp_format", type: "text", placeholder: "%Y-%m-%dT%H:%M:%S" },
]

export function ConfigPage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const config = $api.useQuery("get", "/api/config")
  const checkText = $api.useMutation("post", "/api/config/check")
  const checkForm = $api.useMutation("post", "/api/config/check-form")
  const apply = $api.useMutation("put", "/api/config")
  const [confirming, setConfirming] = useState<Check | null>(null)
  const [rejection, setRejection] = useState<string | null>(null)

  async function applyChecked(check: Check) {
    setRejection(null)
    try {
      const result = await apply.mutateAsync({ body: { text: check.text } })
      toast.success(result.restarted ? t("config.appliedRestart") : t("config.applied"))
      await queryClient.invalidateQueries({ queryKey: ["get", "/api/config"] })
    } catch (error) {
      if (error instanceof ApiError && error.code === "config_rejected") {
        setRejection(error.message)
      } else {
        errorToast(error)
      }
    }
  }

  async function save(check: () => Promise<Check>) {
    try {
      const result = await check()
      if (result.restartRequired) {
        setConfirming(result)
      } else {
        await applyChecked(result)
      }
    } catch (error) {
      errorToast(error)
    }
  }

  if (config.isPending) {
    return <Skeleton className="h-64 w-full" />
  }
  if (config.isError) {
    throw config.error
  }
  const { available, form, mainPorts, text } = config.data
  const busy = checkText.isPending || checkForm.isPending || apply.isPending

  return (
    <div className="max-w-4xl space-y-4">
      {!available && (
        <Alert variant="destructive">
          <TriangleAlertIcon />
          <AlertTitle>{t("config.agentMissingTitle")}</AlertTitle>
          <AlertDescription>{t("config.agentMissing")}</AlertDescription>
        </Alert>
      )}
      {rejection && (
        <Alert variant="destructive">
          <TriangleAlertIcon />
          <AlertTitle>{t("config.rejectedTitle")}</AlertTitle>
          <AlertDescription className="font-mono text-xs whitespace-pre-wrap">{rejection}</AlertDescription>
        </Alert>
      )}
      <p className="text-sm text-muted-foreground">{t("config.hint")}</p>
      {/* keyed by the text, so that a saved change shows in every tab */}
      <Editor key={text} text={text} form={form} mainPorts={mainPorts} available={available} busy={busy} onSaveForm={(body) => void save(() => checkForm.mutateAsync({ body }))} onSaveText={(value) => void save(() => checkText.mutateAsync({ body: { text: value } }))} />
      <ConfirmDialog
        open={confirming !== null}
        onOpenChange={(open) => !open && setConfirming(null)}
        title={t("config.restartTitle")}
        description={t("config.restartDesc", { options: confirming?.changedOptions.join(", ") })}
        confirmLabel={t("config.restartConfirm")}
        onConfirm={() => {
          if (confirming) {
            void applyChecked(confirming)
          }
          setConfirming(null)
        }}
      />
    </div>
  )
}

type ResetPart = "config" | "bridges" | "certificate" | "access" | "backups"

const resetParts = [
  { part: "config", label: "resetConfig", hint: "resetConfigHint" },
  { part: "bridges", label: "resetBridges", hint: "resetBridgesHint" },
  { part: "certificate", label: "resetCertificate", hint: "resetCertificateHint" },
  { part: "access", label: "resetAccess", hint: "resetAccessHint" },
  { part: "backups", label: "resetBackups", hint: "resetBackupsHint" },
] as const

function Reset({ disabled }: { disabled: boolean }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const reset = $api.useMutation("post", "/api/config/reset")
  // the clients only on purpose: without them, no device gets in anymore
  const [selected, setSelected] = useState<Record<ResetPart, boolean>>({ config: true, bridges: true, certificate: true, access: false, backups: false })
  const [word, setWord] = useState("")
  const [failure, setFailure] = useState<string | null>(null)

  async function run() {
    setFailure(null)
    try {
      const outcome = await reset.mutateAsync({ body: selected })
      setWord("")
      await queryClient.invalidateQueries()
      if (outcome.error) {
        setFailure(outcome.error)
      } else {
        toast.success(t("config.resetDone"))
      }
    } catch (error) {
      errorToast(error)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("config.reset")}</CardTitle>
        <CardDescription>{t("config.resetHint")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {failure && (
          <Alert variant="destructive">
            <TriangleAlertIcon />
            <AlertTitle>{t("config.resetFailed")}</AlertTitle>
            <AlertDescription className="font-mono text-xs whitespace-pre-wrap">{failure}</AlertDescription>
          </Alert>
        )}
        <div className="space-y-3">
          {resetParts.map(({ part, label, hint }) => (
            <div key={part} className="flex items-start gap-3">
              <Checkbox id={`reset-${part}`} className="mt-0.5" checked={selected[part]} onCheckedChange={(checked) => setSelected({ ...selected, [part]: checked === true })} />
              <div className="space-y-1">
                <Label htmlFor={`reset-${part}`}>{t(`config.${label}`)}</Label>
                <p className="text-sm text-muted-foreground">{t(`config.${hint}`)}</p>
              </div>
            </div>
          ))}
        </div>
        <Field className="max-w-xs">
          <FieldLabel htmlFor="reset-word">{t("config.resetWord")}</FieldLabel>
          <Input id="reset-word" autoComplete="off" value={word} onChange={(e) => setWord(e.target.value)} />
        </Field>
        <Button variant="destructive" disabled={disabled || word !== "RESET" || !Object.values(selected).some(Boolean) || reset.isPending} onClick={() => void run()}>
          {reset.isPending && <Spinner />}
          {t("config.resetConfirm")}
        </Button>
      </CardContent>
    </Card>
  )
}

function TextEditor({ text, disabled, onSave }: { text: string; disabled: boolean; onSave: (text: string) => void }) {
  const { t } = useTranslation()
  const [value, setValue] = useState(text)

  return (
    <div className="space-y-3">
      <Field>
        <Textarea aria-label={t("config.text")} rows={18} spellCheck={false} className="font-mono text-xs" value={value} onChange={(e) => setValue(e.target.value)} />
        <FieldDescription>{t("config.textHint")}</FieldDescription>
      </Field>
      <div className="flex justify-end">
        <Button disabled={disabled || value === text} onClick={() => onSave(value)}>
          {t("config.save")}
        </Button>
      </div>
    </div>
  )
}

function Editor({
  text,
  form,
  mainPorts,
  available,
  busy,
  onSaveForm,
  onSaveText,
}: {
  text: string
  form: Form
  mainPorts: number[]
  available: boolean
  busy: boolean
  onSaveForm: (form: Form) => void
  onSaveText: (text: string) => void
}) {
  const { t } = useTranslation()
  const [value, setValue] = useState(form)
  const [tab, setTab] = useState("broker")
  const dirty = useMemo(() => JSON.stringify(value) !== JSON.stringify(form), [value, form])
  const set = (change: Partial<Form>) => setValue({ ...value, ...change })
  const problem = listenerProblem(value.listeners, mainPorts)

  return (
    <>
      <Tabs value={tab} onValueChange={setTab} className="gap-4">
        <TabsList className="w-full justify-start overflow-x-auto group-data-horizontal/tabs:h-10">
          <TabsTrigger value="broker">
            <SlidersHorizontalIcon />
            {t("config.tabBroker")}
          </TabsTrigger>
          <TabsTrigger value="listeners">
            <NetworkIcon />
            {t("config.listeners")}
          </TabsTrigger>
          <TabsTrigger value="text">
            <FileCodeIcon />
            {t("config.text")}
          </TabsTrigger>
          <TabsTrigger value="reset" className="ml-auto">
            <RotateCcwIcon />
            {t("config.reset")}
          </TabsTrigger>
        </TabsList>
        <TabsContent value="broker">
          <BrokerSettings value={value} set={set} />
        </TabsContent>
        <TabsContent value="listeners">
          <ListenerSettings value={value} mainPorts={mainPorts} set={set} />
        </TabsContent>
        <TabsContent value="text">
          <TextEditor text={text} disabled={!available || busy || dirty} onSave={onSaveText} />
        </TabsContent>
        <TabsContent value="reset">
          <Reset disabled={!available} />
        </TabsContent>
      </Tabs>
      {dirty && (
        <div className="sticky bottom-4 z-10 flex flex-wrap items-center gap-3 rounded-lg border bg-popover p-3 shadow-md">
          <span className="mr-auto text-sm">{problem ? t(`config.problems.${problem.code}`, { port: problem.port }) : t("config.unsaved")}</span>
          <Button variant="outline" onClick={() => setValue(form)}>
            {t("config.discard")}
          </Button>
          <Button disabled={!available || busy || problem !== null} onClick={() => onSaveForm(value)}>
            {busy && <Spinner />}
            {t("config.save")}
          </Button>
        </div>
      )}
    </>
  )
}

function listenerProblem(listeners: Listener[], mainPorts: number[]) {
  const seen = new Set<number>()
  for (const listener of listeners) {
    const port = listener.port ?? 0
    if (mainPorts.includes(port)) {
      return { code: "portInMain", port } as const
    }
    if (seen.has(port)) {
      return { code: "portTwice", port } as const
    }
    seen.add(port)
    if (listener.certfile !== undefined && listener.certfile !== null && (!listener.certfile.trim() || !listener.keyfile?.trim())) {
      return { code: "tlsIncomplete", port } as const
    }
  }
  return null
}

function BrokerSettings({ value, set }: { value: Form; set: (change: Partial<Form>) => void }) {
  const { t } = useTranslation()
  const level = logLevelOf(value.logTypes)
  // stays "custom" while someone clicks through the list, also over a combination that has a name
  const [custom, setCustom] = useState(level === CUSTOM)
  const setOption = (key: string, option: string | undefined) => {
    const options = { ...value.options }
    if (option === undefined || option === "") {
      delete options[key]
    } else {
      options[key] = option
    }
    set({ options })
  }
  const field = (option: Option) => <OptionField key={option.key} option={option} value={value.options[option.key]} onChange={(v) => setOption(option.key, v)} />

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>{t("config.logging")}</CardTitle>
        </CardHeader>
        <CardContent>
          <FieldGroup>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field>
                <FieldLabel>{t("config.logLevel")}</FieldLabel>
                <Select
                  value={custom ? CUSTOM : level}
                  onValueChange={(id) => {
                    setCustom(id === CUSTOM)
                    if (id !== CUSTOM) {
                      set({ logTypes: id === DEFAULT ? [] : [...(logLevels.find((entry) => entry.id === id)?.types ?? [])] })
                    }
                  }}
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={DEFAULT}>{t("config.defaultValue")}</SelectItem>
                    {logLevels.map((entry) => (
                      <SelectItem key={entry.id} value={entry.id}>
                        {t(`config.logLevels.${entry.id}`)}
                      </SelectItem>
                    ))}
                    <SelectItem value={CUSTOM}>{t("config.logLevels.custom")}</SelectItem>
                  </SelectContent>
                </Select>
                {level === "none" && !custom && <FieldDescription className="text-destructive">{t("config.logOffHint")}</FieldDescription>}
              </Field>
              {custom && (
                <Field>
                  <FieldLabel>{t("config.logTypes")}</FieldLabel>
                  <MultiSelect idPrefix="log" label={t("config.logTypes")} names={logTypes} selected={value.logTypes} onChange={(selected) => set({ logTypes: selected })} />
                </Field>
              )}
            </div>
            <MoreSettings label={t("config.more")} open={logOptions.some((option) => value.options[option.key] !== undefined)}>
              <div className="grid gap-4 sm:grid-cols-2">{logOptions.map(field)}</div>
            </MoreSettings>
          </FieldGroup>
        </CardContent>
      </Card>
      {optionGroups.map((group) => (
        <Card key={group.id}>
          <CardHeader>
            <CardTitle>{t(`config.groups.${group.id}`)}</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid gap-4 sm:grid-cols-2">{group.options.map(field)}</div>
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

const sizeUnits = [
  { id: "B", bytes: 1 },
  { id: "KB", bytes: 1024 },
  { id: "MB", bytes: 1024 * 1024 },
  { id: "GB", bytes: 1024 * 1024 * 1024 },
] as const

/** The largest unit that shows the number without a fraction. */
function bestUnit(value: string | undefined) {
  const bytes = Number(value ?? 0)
  return bytes === 0 ? sizeUnits[2] : ([...sizeUnits].reverse().find((unit) => bytes % unit.bytes === 0) ?? sizeUnits[0])
}

function OptionField({ option, value, onChange }: { option: Option; value?: string; onChange: (value: string | undefined) => void }) {
  const { t } = useTranslation()
  const id = `option-${option.key}`
  const label = t(`config.options.${option.key}` as "config.options.sys_interval")
  const hint = t(`config.optionHints.${option.key}` as "config.optionHints.sys_interval", { defaultValue: "" })
  const standard = defaultText(option, t)

  return (
    <Field>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      {option.type === "bool" && <BoolInput id={id} label={label} standard={option.defaultValue === "true"} value={value} onChange={onChange} />}
      {option.type === "number" && <Input id={id} type="number" min={0} placeholder={standard} value={value ?? ""} onChange={(e) => onChange(e.target.value)} />}
      {option.type === "text" && <Input id={id} className="font-mono text-xs" placeholder={option.placeholder} value={value ?? ""} onChange={(e) => onChange(e.target.value)} />}
      {option.type === "size" && <SizeInput id={id} placeholder={standard} value={value} onChange={onChange} />}
      {option.type === "duration" && <DurationInput id={id} placeholder={standard} value={value} onChange={onChange} />}
      {hint && <FieldDescription>{hint}</FieldDescription>}
    </Field>
  )
}

function defaultText(option: Option, t: TFunction) {
  if (option.type === "duration") {
    return t("config.neverExpires")
  }
  if (option.type === "size") {
    if (option.defaultValue === "0") {
      return t("config.noLimit")
    }
    return t("config.standard", { value: `${Math.round(Number(option.defaultValue) / 1000000)} MB` })
  }
  return t("config.standard", { value: option.defaultValue })
}

function BoolInput({ id, label, standard, value, onChange }: { id: string; label: string; standard: boolean; value?: string; onChange: (value: string | undefined) => void }) {
  const { t } = useTranslation()
  return (
    <Select value={value ?? DEFAULT} onValueChange={(v) => onChange(v === DEFAULT ? undefined : v)}>
      <SelectTrigger id={id} aria-label={label} className="w-full">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value={DEFAULT}>{t("config.standard", { value: t(standard ? "config.on" : "config.off") })}</SelectItem>
        <SelectItem value="true">{t("config.on")}</SelectItem>
        <SelectItem value="false">{t("config.off")}</SelectItem>
      </SelectContent>
    </Select>
  )
}

function SizeInput({ id, placeholder, value, onChange }: { id: string; placeholder: string; value?: string; onChange: (value: string | undefined) => void }) {
  const { t } = useTranslation()
  const [unit, setUnit] = useState(() => bestUnit(value))
  const shown = value === undefined ? "" : String(Number(value) / unit.bytes)
  return (
    <div className="flex gap-2">
      <Input id={id} type="number" min={0} step="any" placeholder={placeholder} value={shown} onChange={(e) => onChange(e.target.value === "" ? undefined : String(Math.round(Number(e.target.value) * unit.bytes)))} />
      <Select
        value={unit.id}
        onValueChange={(next) => {
          const chosen = sizeUnits.find((entry) => entry.id === next) ?? unit
          setUnit(chosen)
          if (value !== undefined) {
            onChange(String(Math.round(Number(shown) * chosen.bytes)))
          }
        }}
      >
        <SelectTrigger aria-label={t("config.unit")} className="w-20 shrink-0">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {sizeUnits.map((entry) => (
            <SelectItem key={entry.id} value={entry.id}>
              {entry.id}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  )
}

const durationUnits = ["h", "d", "w", "m", "y"] as const

function DurationInput({ id, placeholder, value, onChange }: { id: string; placeholder: string; value?: string; onChange: (value: string | undefined) => void }) {
  const { t } = useTranslation()
  const match = /^(\d+)([hdwmy])$/.exec(value ?? "")
  const [unit, setUnit] = useState<string>(match?.[2] ?? "d")
  return (
    <div className="flex gap-2">
      <Input id={id} type="number" min={1} placeholder={placeholder} value={match?.[1] ?? ""} onChange={(e) => onChange(e.target.value === "" ? undefined : `${e.target.value}${unit}`)} />
      <Select
        value={unit}
        onValueChange={(next) => {
          setUnit(next)
          if (match) {
            onChange(`${match[1]}${next}`)
          }
        }}
      >
        <SelectTrigger aria-label={t("config.unit")} className="w-28 shrink-0">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {durationUnits.map((entry) => (
            <SelectItem key={entry} value={entry}>
              {t(`config.durations.${entry}`)}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  )
}

function ListenerSettings({ value, mainPorts, set }: { value: Form; mainPorts: number[]; set: (change: Partial<Form>) => void }) {
  const { t } = useTranslation()
  const certificate = $api.useQuery("get", "/api/certificate")
  const hasCertificate = Boolean(certificate.data?.certificate)

  function setListener(index: number, change: Partial<Listener>) {
    set({ listeners: value.listeners.map((listener, i) => (i === index ? { ...listener, ...change } : listener)) })
  }

  function setTls(index: number, on: boolean) {
    setListener(
      index,
      on
        ? { certfile: hasCertificate ? certificate.data?.certfile : "", keyfile: hasCertificate ? certificate.data?.keyfile : "" }
        : { certfile: undefined, keyfile: undefined, cafile: undefined },
    )
  }

  const nextPort = () => {
    const used = new Set([...mainPorts, ...value.listeners.map((listener) => listener.port ?? 0)])
    let port = NEW_LISTENER_PORT
    while (used.has(port)) {
      port += 1
    }
    return port
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("config.listeners")}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {mainPorts.map((port) => (
          <div key={port} className="flex flex-wrap items-center gap-2 rounded-lg border border-dashed p-3 text-sm">
            <span className="font-medium tabular-nums">{port}</span>
            <span className="text-muted-foreground">{t("config.fromMain")}</span>
          </div>
        ))}
        {value.listeners.map((listener, index) => {
          const tls = listener.certfile !== undefined && listener.certfile !== null
          return (
            <div key={index} className="space-y-3 rounded-lg border p-3">
              <div className="grid items-end gap-3 sm:grid-cols-[6rem_1fr_9rem_auto_auto]">
                <Field>
                  <FieldLabel htmlFor={`listener-port-${index}`}>{t("config.port")}</FieldLabel>
                  <Input id={`listener-port-${index}`} type="number" min={1} max={65535} value={listener.port} onChange={(e) => setListener(index, { port: Number(e.target.value) })} />
                </Field>
                <Field>
                  <FieldLabel htmlFor={`listener-bind-${index}`}>{t("config.bindAddress")}</FieldLabel>
                  <Input id={`listener-bind-${index}`} placeholder={t("config.allAddresses")} value={listener.bindAddress ?? ""} onChange={(e) => setListener(index, { bindAddress: e.target.value || undefined })} />
                </Field>
                <Field>
                  <FieldLabel>{t("config.protocol")}</FieldLabel>
                  <Select value={listener.protocol} onValueChange={(protocol) => setListener(index, { protocol })}>
                    <SelectTrigger aria-label={t("config.protocol")} className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="mqtt">MQTT</SelectItem>
                      <SelectItem value="websockets">WebSockets</SelectItem>
                    </SelectContent>
                  </Select>
                </Field>
                <div className="flex h-8 items-center gap-2">
                  <Switch id={`listener-tls-${index}`} checked={tls} onCheckedChange={(on) => setTls(index, on)} />
                  <Label htmlFor={`listener-tls-${index}`}>TLS</Label>
                </div>
                <Button variant="ghost" size="icon" aria-label={t("common.delete")} onClick={() => set({ listeners: value.listeners.filter((_, i) => i !== index) })}>
                  <Trash2Icon />
                </Button>
              </div>
              {tls && (
                <div className="grid gap-3 sm:grid-cols-2">
                  <Field>
                    <FieldLabel htmlFor={`listener-cert-${index}`}>{t("config.certfile")}</FieldLabel>
                    <Input id={`listener-cert-${index}`} className="font-mono text-xs" value={listener.certfile ?? ""} onChange={(e) => setListener(index, { certfile: e.target.value })} />
                  </Field>
                  <Field>
                    <FieldLabel htmlFor={`listener-key-${index}`}>{t("config.keyfile")}</FieldLabel>
                    <Input id={`listener-key-${index}`} className="font-mono text-xs" value={listener.keyfile ?? ""} onChange={(e) => setListener(index, { keyfile: e.target.value })} />
                  </Field>
                </div>
              )}
              <MoreSettings
                label={t("config.more")}
                open={Boolean(listener.cafile) || listener.maxConnections !== undefined && listener.maxConnections !== null || Boolean(listener.proxyProtocol)}
              >
                <div className="grid gap-3 sm:grid-cols-2">
                  <Field>
                    <FieldLabel>{t("config.proxyProtocol")}</FieldLabel>
                    <Select
                      value={String(listener.proxyProtocol ?? 0)}
                      onValueChange={(version) => setListener(index, { proxyProtocol: version === "0" ? undefined : Number(version) })}
                    >
                      <SelectTrigger aria-label={t("config.proxyProtocol")} className="w-full">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="0">{t("config.proxyProtocolOff")}</SelectItem>
                        <SelectItem value="1">{t("config.proxyProtocolVersion", { version: 1 })}</SelectItem>
                        <SelectItem value="2">{t("config.proxyProtocolVersion", { version: 2 })}</SelectItem>
                      </SelectContent>
                    </Select>
                    <FieldDescription>{t(listener.proxyProtocol === 2 && tls ? "config.proxyProtocolTls" : "config.proxyProtocolHint")}</FieldDescription>
                  </Field>
                  {listener.proxyProtocol === 2 && (
                    <Field orientation="horizontal">
                      <Switch
                        id={`listener-proxy-tls-${index}`}
                        checked={Boolean(listener.proxyRequireTls)}
                        onCheckedChange={(on) => setListener(index, { proxyRequireTls: on || undefined })}
                      />
                      <FieldContent>
                        <FieldLabel htmlFor={`listener-proxy-tls-${index}`}>{t("config.proxyRequireTls")}</FieldLabel>
                        <FieldDescription>{t("config.proxyRequireTlsHint")}</FieldDescription>
                      </FieldContent>
                    </Field>
                  )}
                  <Field>
                    <FieldLabel htmlFor={`listener-max-${index}`}>{t("config.maxConnections")}</FieldLabel>
                    <Input
                      id={`listener-max-${index}`}
                      type="number"
                      min={-1}
                      placeholder={t("config.noLimit")}
                      value={listener.maxConnections ?? ""}
                      onChange={(e) => setListener(index, { maxConnections: e.target.value === "" ? undefined : Number(e.target.value) })}
                    />
                  </Field>
                  {tls && (
                    <Field>
                      <FieldLabel htmlFor={`listener-ca-${index}`}>{t("config.cafile")}</FieldLabel>
                      <Input id={`listener-ca-${index}`} className="font-mono text-xs" value={listener.cafile ?? ""} onChange={(e) => setListener(index, { cafile: e.target.value || undefined })} />
                      <FieldDescription>{t("config.cafileHint")}</FieldDescription>
                    </Field>
                  )}
                </div>
              </MoreSettings>
              {listener.proxyProtocol ? <ProxySetup port={listener.port ?? 0} version={listener.proxyProtocol} requireTls={listener.proxyProtocol === 2 && Boolean(listener.proxyRequireTls)} /> : null}
            </div>
          )
        })}
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" onClick={() => set({ listeners: [...value.listeners, { port: nextPort(), protocol: "mqtt" }] })}>
            <PlusIcon />
            {t("config.listenerAdd")}
          </Button>
          {/* the usual setup behind Traefik: TLS ends there, the address of the client comes along */}
          <Button variant="outline" onClick={() => set({ listeners: [...value.listeners, { port: nextPort(), protocol: "mqtt", proxyProtocol: 2 }] })}>
            <PlusIcon />
            {t("config.listenerAddProxy")}
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}

/**
 * What the proxy in front needs for a listener with the PROXY protocol, for Traefik and HAProxy. Requiring TLS needs the TLS entry
 * in the header: HAProxy sends it with send-proxy-v2-ssl, for every client, Traefik never does.
 */
function ProxySetup({ port, version, requireTls }: { port: number; version: number; requireTls: boolean }) {
  const { t } = useTranslation()
  const traefik = `tcp:
  routers:
    mqtt:
      entryPoints: ["mqtt"]
      rule: "HostSNI(\`mqtt.example.com\`)"
      service: mosquitto
      tls:
        certResolver: letsencrypt
  services:
    mosquitto:
      loadBalancer:
        servers:
          - address: "BROKER-HOST:${port}"
        serversTransport: mosquitto-proxy
  serversTransports:
    mosquitto-proxy:
      proxyProtocol:
        version: ${version}`
  const traefikOld = `  services:
    mosquitto:
      loadBalancer:
        servers:
          - address: "BROKER-HOST:${port}"
        proxyProtocol:
          version: ${version}`
  const haproxy = `backend mosquitto
  mode tcp
  server broker BROKER-HOST:${port} send-proxy${version === 2 ? (requireTls ? "-v2-ssl" : "-v2") : ""}`
  return (
    <MoreSettings label={t("config.proxySetup")}>
      <div className="space-y-3 text-sm">
        <p className="text-muted-foreground">{t("config.proxySetupHint", { port })}</p>
        {requireTls && <p className="text-destructive">{t("config.proxySetupTraefikNoTls")}</p>}
        <div className="space-y-1">
          <div className="font-medium">{t("config.proxySetupTraefik")}</div>
          <pre className="overflow-x-auto rounded-md bg-muted p-3 font-mono text-xs">{traefik}</pre>
        </div>
        <div className="space-y-1">
          <div className="font-medium">{t("config.proxySetupTraefikOld")}</div>
          <pre className="overflow-x-auto rounded-md bg-muted p-3 font-mono text-xs">{traefikOld}</pre>
        </div>
        <div className="space-y-1">
          <div className="font-medium">HAProxy</div>
          <pre className="overflow-x-auto rounded-md bg-muted p-3 font-mono text-xs">{haproxy}</pre>
        </div>
      </div>
    </MoreSettings>
  )
}
