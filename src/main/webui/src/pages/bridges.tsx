import { useQueryClient } from "@tanstack/react-query"
import { PencilIcon, PlusIcon, RefreshCwIcon, SunIcon, Trash2Icon, TriangleAlertIcon } from "lucide-react"
import { useState, type FormEvent, type ReactNode } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"

import { $api, ApiError, type Schemas } from "@/api/client"
import { ConfirmDialog } from "@/components/confirm-dialog"
import { MoreSettings } from "@/components/more-settings"
import { joinOptions, splitOptions, type BridgeOptions } from "@/lib/bridge-options"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Field, FieldContent, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { Spinner } from "@/components/ui/spinner"
import { Switch } from "@/components/ui/switch"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Textarea } from "@/components/ui/textarea"
import { useErrorToast } from "@/lib/errors"

type Bridge = Schemas["BridgeForm"]
type BridgeView = Schemas["BridgeView"]
type Topic = Schemas["BridgeTopic"]
type Direction = "both" | "in" | "out"
type Warning = "echo" | "overlap" | "noState"

const bridgesKey = ["get", "/api/bridges"]
const directions: Direction[] = ["both", "out", "in"]
const REFRESH_MS = 5000
const DEFAULT = "default"
const emptyBridge: Bridge = { name: "", address: "", topics: [{ pattern: "#", direction: "both", qos: 0 }], extraOptions: [] }

export function BridgesPage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const view = $api.useQuery("get", "/api/bridges", {}, { refetchInterval: REFRESH_MS })
  const remove = $api.useMutation("delete", "/api/bridges/{name}")
  const setEnabled = $api.useMutation("put", "/api/bridges/{name}/enabled")
  const [editing, setEditing] = useState<BridgeView | "new" | null>(null)
  const [pairing, setPairing] = useState(false)
  const [deleting, setDeleting] = useState<string | null>(null)
  const pairAgain = $api.useMutation("post", "/api/bridges/{name}/victron/pair-again")
  const [pairingAgain, setPairingAgain] = useState<string | null>(null)

  async function confirmPairAgain() {
    if (!pairingAgain) {
      return
    }
    const name = pairingAgain
    setPairingAgain(null)
    try {
      await pairAgain.mutateAsync({ params: { path: { name } } })
      toast.success(t("victron.pairedAgain", { name }))
      await queryClient.invalidateQueries({ queryKey: bridgesKey })
    } catch (error) {
      errorToast(error)
    }
  }

  async function confirmDelete() {
    if (!deleting) {
      return
    }
    try {
      await remove.mutateAsync({ params: { path: { name: deleting } } })
      toast.success(t("bridges.deleted"))
      await queryClient.invalidateQueries({ queryKey: bridgesKey })
    } catch (error) {
      errorToast(error)
    }
    setDeleting(null)
  }

  async function toggle(name: string, enabled: boolean) {
    try {
      await setEnabled.mutateAsync({ params: { path: { name } }, body: { enabled } })
      await queryClient.invalidateQueries({ queryKey: bridgesKey })
    } catch (error) {
      errorToast(error)
    }
  }

  if (view.isPending) {
    return <Skeleton className="h-64 w-full" />
  }
  if (view.isError) {
    throw view.error
  }
  const { available, bridges } = view.data
  const warnings = bridges.flatMap((view) => view.warnings.map((warning) => t(`bridges.warnings.${warning as Warning}`, { name: view.bridge.name })))
  const changedCertificates = bridges.filter((view) => view.victronCertificateChanged)

  return (
    <div className="max-w-4xl space-y-4">
      {!available && (
        <Alert variant="destructive">
          <TriangleAlertIcon />
          <AlertTitle>{t("config.agentMissingTitle")}</AlertTitle>
          <AlertDescription>{t("config.agentMissing")}</AlertDescription>
        </Alert>
      )}
      {warnings.length > 0 && (
        <Alert>
          <TriangleAlertIcon />
          <AlertDescription>
            <ul className="list-inside list-disc">
              {warnings.map((warning) => (
                <li key={warning}>{warning}</li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}
      {changedCertificates.map((view) => (
        <Alert key={view.bridge.name} variant="destructive">
          <TriangleAlertIcon />
          <AlertTitle>{t("victron.certificateChangedTitle", { name: view.bridge.name })}</AlertTitle>
          <AlertDescription className="space-y-2">
            <p>{t("victron.certificateChanged")}</p>
            <dl className="grid gap-1 text-xs">
              <dt className="font-medium">{t("victron.certificateTrusted")}</dt>
              <dd className="font-mono break-all">{view.victronCertificate}</dd>
              <dt className="font-medium">{t("victron.certificatePresented")}</dt>
              <dd className="font-mono break-all">{view.victronCertificateChanged}</dd>
            </dl>
            <Button size="sm" variant="outline" disabled={!available} onClick={() => setPairingAgain(view.bridge.name)}>
              <RefreshCwIcon />
              {t("victron.pairAgain")}
            </Button>
          </AlertDescription>
        </Alert>
      ))}
      <p className="text-sm text-muted-foreground">{t("bridges.hint")}</p>
      <div className="flex flex-wrap justify-end gap-2">
        <Button variant="outline" disabled={!available} onClick={() => setPairing(true)}>
          <SunIcon />
          {t("victron.add")}
        </Button>
        <Button disabled={!available} onClick={() => setEditing("new")}>
          <PlusIcon />
          {t("bridges.add")}
        </Button>
      </div>
      {bridges.length === 0 ? (
        <p className="rounded-lg border p-6 text-center text-sm text-muted-foreground">{t("bridges.empty")}</p>
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("bridges.name")}</TableHead>
                <TableHead>{t("bridges.address")}</TableHead>
                <TableHead>{t("bridges.topics")}</TableHead>
                <TableHead>{t("bridges.status")}</TableHead>
                <TableHead>{t("bridges.enabled")}</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {bridges.map((item) => (
                <TableRow key={item.bridge.name}>
                  <TableCell className="font-medium">
                    <span className="flex flex-wrap items-center gap-2">
                      {item.bridge.name}
                      {item.victronPortalId && <Badge variant="outline">Victron</Badge>}
                    </span>
                  </TableCell>
                  <TableCell className="font-mono text-xs">{item.bridge.address}</TableCell>
                  <TableCell>{item.bridge.topics.length}</TableCell>
                  <TableCell>
                    {item.bridge.disabled ? (
                      <Badge variant="outline">{t("bridges.disabled")}</Badge>
                    ) : (
                      <Badge variant={item.connected === true ? "default" : item.connected === false ? "destructive" : "secondary"}>
                        {t(item.connected === true ? "bridges.connected" : item.connected === false ? "bridges.disconnected" : "bridges.unknown")}
                      </Badge>
                    )}
                  </TableCell>
                  <TableCell>
                    <Switch
                      aria-label={t("bridges.enabled")}
                      checked={!item.bridge.disabled}
                      disabled={!available || setEnabled.isPending}
                      onCheckedChange={(enabled) => void toggle(item.bridge.name, enabled)}
                    />
                  </TableCell>
                  <TableCell>
                    <div className="flex justify-end">
                      {item.victronCertificate && (
                        <Button variant="ghost" size="icon-sm" aria-label={t("victron.pairAgain")} title={t("victron.pairAgain")} disabled={!available} onClick={() => setPairingAgain(item.bridge.name)}>
                          <RefreshCwIcon />
                        </Button>
                      )}
                      <Button variant="ghost" size="icon-sm" aria-label={t("common.edit")} disabled={!available} onClick={() => setEditing(item)}>
                        <PencilIcon />
                      </Button>
                      <Button variant="ghost" size="icon-sm" aria-label={t("common.delete")} disabled={!available} onClick={() => setDeleting(item.bridge.name)}>
                        <Trash2Icon />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {pairing && <VictronDialog onClose={() => setPairing(false)} />}
      {editing !== null && <BridgeDialog view={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
      <ConfirmDialog
        open={pairingAgain !== null}
        onOpenChange={(open) => !open && setPairingAgain(null)}
        title={t("victron.pairAgainTitle", { name: pairingAgain })}
        description={t("victron.pairAgainDesc")}
        confirmLabel={t("victron.pairAgain")}
        destructive={false}
        onConfirm={() => void confirmPairAgain()}
      />
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={t("bridges.deleteTitle", { name: deleting })}
        description={t("bridges.deleteDesc")}
        onConfirm={() => void confirmDelete()}
      />
    </div>
  )
}

function VictronDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const pair = $api.useMutation("post", "/api/bridges/victron")
  const [name, setName] = useState("victron")
  const [host, setHost] = useState("")
  const [secured, setSecured] = useState(true)
  const [twoWay, setTwoWay] = useState(false)
  const [localPrefix, setLocalPrefix] = useState("")
  const [portalId, setPortalId] = useState("")
  const [port, setPort] = useState("")

  async function submit(event: FormEvent) {
    event.preventDefault()
    try {
      await pair.mutateAsync({
        body: { name, host, secured, twoWay, localPrefix: localPrefix || undefined, portalId: portalId || undefined, port: port === "" ? undefined : Number(port) },
      })
      toast.success(t("bridges.saved"))
      await queryClient.invalidateQueries({ queryKey: bridgesKey })
      onClose()
    } catch (error) {
      errorToast(error)
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-xl">
        <form onSubmit={submit} className="space-y-6">
          <DialogHeader>
            <DialogTitle>{t("victron.title")}</DialogTitle>
          </DialogHeader>
          <FieldGroup>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field>
                <FieldLabel htmlFor="victron-host">{t("victron.host")}</FieldLabel>
                <Input id="victron-host" required autoComplete="off" pattern="[^\s\/:]+" placeholder="venus.local" className="font-mono text-xs" value={host} onChange={(e) => setHost(e.target.value)} />
              </Field>
              <Field>
                <FieldLabel htmlFor="victron-name">{t("bridges.name")}</FieldLabel>
                <Input id="victron-name" required autoComplete="off" pattern="[^\/\s]+" value={name} onChange={(e) => setName(e.target.value)} />
              </Field>
            </div>
            <Field>
              <FieldLabel>{t("victron.mode")}</FieldLabel>
              <Select value={twoWay ? "twoWay" : "readOnly"} onValueChange={(mode) => setTwoWay(mode === "twoWay")}>
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="readOnly">{t("victron.readOnly")}</SelectItem>
                  <SelectItem value="twoWay">{t("victron.twoWay")}</SelectItem>
                </SelectContent>
              </Select>
              <FieldDescription>{t(twoWay ? "victron.twoWayHint" : "victron.readOnlyHint")}</FieldDescription>
            </Field>
            <Field orientation="horizontal">
              <Switch id="victron-secured" checked={secured} onCheckedChange={setSecured} />
              <FieldContent>
                <FieldLabel htmlFor="victron-secured">{t("victron.secured")}</FieldLabel>
                <FieldDescription>{t(secured ? "victron.securedHint" : "victron.unsecuredHint")}</FieldDescription>
              </FieldContent>
            </Field>
            {secured && (
              <Alert>
                <TriangleAlertIcon />
                <AlertTitle>{t("victron.pairingTitle")}</AlertTitle>
                <AlertDescription>{t("victron.pairingSteps")}</AlertDescription>
              </Alert>
            )}
            <MoreSettings label={t("bridges.advanced")}>
              <div className="grid gap-4 sm:grid-cols-3">
                <Field>
                  <FieldLabel htmlFor="victron-prefix">{t("bridges.localPrefix")}</FieldLabel>
                  <Input id="victron-prefix" pattern="\S*" placeholder="victron/" className="font-mono text-xs" value={localPrefix} onChange={(e) => setLocalPrefix(e.target.value)} />
                </Field>
                <Field>
                  <FieldLabel htmlFor="victron-portal">{t("victron.portalId")}</FieldLabel>
                  <Input id="victron-portal" pattern="[0-9a-zA-Z]*" placeholder={t("victron.auto")} className="font-mono text-xs" value={portalId} onChange={(e) => setPortalId(e.target.value)} />
                </Field>
                <Field>
                  <FieldLabel htmlFor="victron-port">{t("config.port")}</FieldLabel>
                  <Input id="victron-port" type="number" min={1} max={65535} placeholder={secured ? "8883" : "1883"} value={port} onChange={(e) => setPort(e.target.value)} />
                </Field>
              </div>
            </MoreSettings>
          </FieldGroup>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={pair.isPending}>
              {pair.isPending && <Spinner />}
              {t(secured ? "victron.pair" : "victron.connect")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function OptionSwitch({ id, label, hint, checked, onChange }: { id: string; label: string; hint?: string; checked: boolean; onChange: (checked: boolean) => void }) {
  return (
    <Field orientation="horizontal">
      <Switch id={id} checked={checked} onCheckedChange={onChange} />
      <FieldContent>
        <FieldLabel htmlFor={id}>{label}</FieldLabel>
        {hint && <FieldDescription>{hint}</FieldDescription>}
      </FieldContent>
    </Field>
  )
}

function OptionInput({ id, label, hint, value, type, placeholder, onChange }: { id: string; label: string; hint?: string; value?: string; type?: string; placeholder?: string; onChange: (value: string) => void }) {
  return (
    <Field>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <Input id={id} type={type} min={type === "number" ? 0 : undefined} spellCheck={false} placeholder={placeholder} value={value ?? ""} onChange={(e) => onChange(e.target.value)} />
      {hint && <FieldDescription>{hint}</FieldDescription>}
    </Field>
  )
}

/** What is at its default is not written, so a file stays as short as the settings are simple. */
function BridgeOptionFields({ options, lazy, onChange }: { options: BridgeOptions; lazy: boolean; onChange: (options: BridgeOptions) => void }) {
  const { t } = useTranslation()
  const set = (key: string, value: string) => onChange({ ...options, [key]: value })

  return (
    <>
      <FieldSet title={t("bridges.tls")}>
        <OptionInput id="bridge-cafile" label={t("bridges.cafile")} value={options.bridge_cafile} onChange={(v) => set("bridge_cafile", v)} />
        <div className="grid gap-4 sm:grid-cols-2">
          <OptionInput id="bridge-certfile" label={t("bridges.certfile")} value={options.bridge_certfile} onChange={(v) => set("bridge_certfile", v)} />
          <OptionInput id="bridge-keyfile" label={t("bridges.keyfile")} value={options.bridge_keyfile} onChange={(v) => set("bridge_keyfile", v)} />
        </div>
        <Field>
          <FieldLabel>{t("bridges.tlsVersion")}</FieldLabel>
          <Select value={options.bridge_tls_version || DEFAULT} onValueChange={(v) => set("bridge_tls_version", v === DEFAULT ? "" : v)}>
            <SelectTrigger className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={DEFAULT}>{t("config.defaultValue")}</SelectItem>
              <SelectItem value="tlsv1.3">TLS 1.3</SelectItem>
              <SelectItem value="tlsv1.2">TLS 1.2</SelectItem>
            </SelectContent>
          </Select>
        </Field>
        <OptionSwitch id="bridge-insecure" label={t("bridges.insecure")} hint={t("bridges.insecureHint")} checked={options.bridge_insecure === "true"} onChange={(on) => set("bridge_insecure", on ? "true" : "")} />
      </FieldSet>
      <FieldSet title={t("bridges.notifications")}>
        <OptionSwitch id="bridge-notifications" label={t("bridges.notificationsOn")} hint={t("bridges.notificationsHint")} checked={options.notifications !== "false"} onChange={(on) => set("notifications", on ? "" : "false")} />
        <OptionSwitch id="bridge-local-only" label={t("bridges.notificationsLocal")} hint={t("bridges.notificationsLocalHint")} checked={options.notifications_local_only === "true"} onChange={(on) => set("notifications_local_only", on ? "true" : "")} />
        <OptionInput id="bridge-notification-topic" label={t("bridges.notificationTopic")} hint={t("bridges.notificationTopicHint")} placeholder={t("config.defaultValue")} value={options.notification_topic} onChange={(v) => set("notification_topic", v)} />
      </FieldSet>
      <FieldSet title={t("bridges.connectionGroup")}>
        <OptionSwitch id="bridge-round-robin" label={t("bridges.roundRobin")} hint={t("bridges.roundRobinHint")} checked={options.round_robin === "true"} onChange={(on) => set("round_robin", on ? "true" : "")} />
        {lazy && (
          <div className="grid gap-4 sm:grid-cols-2">
            <OptionInput id="bridge-idle" label={t("bridges.idleTimeout")} type="number" placeholder={t("config.defaultValue")} value={options.idle_timeout} onChange={(v) => set("idle_timeout", v)} />
            <OptionInput id="bridge-threshold" label={t("bridges.threshold")} type="number" placeholder={t("config.defaultValue")} value={options.threshold} onChange={(v) => set("threshold", v)} />
          </div>
        )}
      </FieldSet>
    </>
  )
}

function FieldSet({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="space-y-4 border-t pt-4">
      <h3 className="text-sm font-semibold">{title}</h3>
      {children}
    </div>
  )
}

function BridgeDialog({ view, onClose }: { view: BridgeView | null; onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const create = $api.useMutation("post", "/api/bridges")
  const update = $api.useMutation("put", "/api/bridges/{name}")
  const [value, setValue] = useState<Bridge>(view?.bridge ?? emptyBridge)
  const [initialOptions] = useState(() => splitOptions(view?.bridge.extraOptions ?? []))
  const [options, setOptions] = useState(initialOptions.fields)
  const [extra, setExtra] = useState(initialOptions.others.join("\n"))
  const [confirmRestart, setConfirmRestart] = useState(false)
  const set = (change: Partial<Bridge>) => setValue({ ...value, ...change })

  function setTopic(index: number, change: Partial<Topic>) {
    set({ topics: value.topics.map((topic, i) => (i === index ? { ...topic, ...change } : topic)) })
  }

  async function save(restart: boolean) {
    const body = { ...value, extraOptions: joinOptions(options, extra) }
    try {
      if (view) {
        await update.mutateAsync({ params: { path: { name: view.bridge.name }, query: { confirmRestart: restart } }, body })
      } else {
        await create.mutateAsync({ body })
      }
      toast.success(t("bridges.saved"))
      await queryClient.invalidateQueries({ queryKey: bridgesKey })
      onClose()
    } catch (error) {
      if (error instanceof ApiError && error.code === "restart_required") {
        setConfirmRestart(true)
      } else {
        errorToast(error)
      }
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    void save(false)
  }

  const saving = create.isPending || update.isPending

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <form onSubmit={submit} className="space-y-6">
          <DialogHeader>
            <DialogTitle>{view ? t("bridges.editTitle", { name: view.bridge.name }) : t("bridges.addTitle")}</DialogTitle>
          </DialogHeader>
          <FieldGroup>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field>
                <FieldLabel htmlFor="bridge-name">{t("bridges.name")}</FieldLabel>
                <Input id="bridge-name" required autoComplete="off" pattern="[^\/\s]+" value={value.name} onChange={(e) => set({ name: e.target.value })} />
              </Field>
              <Field>
                <FieldLabel htmlFor="bridge-address">{t("bridges.address")}</FieldLabel>
                <Input id="bridge-address" required autoComplete="off" className="font-mono text-xs" value={value.address} onChange={(e) => set({ address: e.target.value })} />
                <FieldDescription>{t("bridges.addressHint")}</FieldDescription>
              </Field>
              <Field>
                <FieldLabel htmlFor="bridge-username">{t("bridges.username")}</FieldLabel>
                <Input id="bridge-username" autoComplete="off" value={value.username ?? ""} onChange={(e) => set({ username: e.target.value || undefined })} />
              </Field>
              <Field>
                <FieldLabel htmlFor="bridge-password">{t("bridges.password")}</FieldLabel>
                <Input id="bridge-password" type="password" autoComplete="new-password" value={value.password ?? ""} onChange={(e) => set({ password: e.target.value || undefined })} />
                {view?.passwordSet && <FieldDescription>{t("bridges.passwordKeep")}</FieldDescription>}
              </Field>
            </div>

            <Field>
              <FieldLabel>{t("bridges.topics")}</FieldLabel>
              <div className="space-y-3">
                {value.topics.map((topic, index) => (
                  <div key={index} className="grid items-end gap-2 rounded-lg border p-3 sm:grid-cols-[1fr_7.5rem_4.5rem_auto]">
                    <Field>
                      <FieldLabel htmlFor={`topic-${index}`}>{t("bridges.topic")}</FieldLabel>
                      <Input id={`topic-${index}`} required pattern="\S+" className="font-mono text-xs" value={topic.pattern} onChange={(e) => setTopic(index, { pattern: e.target.value })} />
                    </Field>
                    <Field>
                      <FieldLabel>{t("bridges.direction")}</FieldLabel>
                      <Select value={topic.direction} onValueChange={(direction) => setTopic(index, { direction })}>
                        <SelectTrigger className="w-full">
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          {directions.map((direction) => (
                            <SelectItem key={direction} value={direction}>
                              {t(`bridges.directions.${direction}`)}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </Field>
                    <Field>
                      <FieldLabel>{t("bridges.qos")}</FieldLabel>
                      <Select value={String(topic.qos)} onValueChange={(qos) => setTopic(index, { qos: Number(qos) })}>
                        <SelectTrigger className="w-full">
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          {[0, 1, 2].map((qos) => (
                            <SelectItem key={qos} value={String(qos)}>
                              {String(qos)}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </Field>
                    <Button type="button" variant="ghost" size="icon" aria-label={t("common.delete")} disabled={value.topics.length === 1} onClick={() => set({ topics: value.topics.filter((_, i) => i !== index) })}>
                      <Trash2Icon />
                    </Button>
                    <MoreSettings className="sm:col-span-4" label={t("bridges.prefixes")} open={Boolean(topic.localPrefix || topic.remotePrefix)}>
                    <div className="grid gap-2 sm:grid-cols-2">
                      <Field>
                        <FieldLabel htmlFor={`topic-local-${index}`}>{t("bridges.localPrefix")}</FieldLabel>
                        <Input id={`topic-local-${index}`} className="font-mono text-xs" value={topic.localPrefix ?? ""} onChange={(e) => setTopic(index, { localPrefix: e.target.value || undefined })} />
                      </Field>
                      <Field>
                        <FieldLabel htmlFor={`topic-remote-${index}`}>{t("bridges.remotePrefix")}</FieldLabel>
                        <Input id={`topic-remote-${index}`} className="font-mono text-xs" value={topic.remotePrefix ?? ""} onChange={(e) => setTopic(index, { remotePrefix: e.target.value || undefined })} />
                      </Field>
                    </div>
                    </MoreSettings>
                  </div>
                ))}
              </div>
              <Button type="button" variant="outline" className="w-fit" onClick={() => set({ topics: [...value.topics, { pattern: "", direction: "both", qos: 0 }] })}>
                <PlusIcon />
                {t("bridges.topicAdd")}
              </Button>
            </Field>

            <MoreSettings label={t("bridges.advanced")}>
              <FieldGroup>
                <Field orientation="horizontal">
                  <Switch id="bridge-private" checked={value.tryPrivate ?? true} onCheckedChange={(tryPrivate) => set({ tryPrivate })} />
                  <FieldContent>
                    <FieldLabel htmlFor="bridge-private">{t("bridges.tryPrivate")}</FieldLabel>
                    <FieldDescription>{t("bridges.tryPrivateHint")}</FieldDescription>
                  </FieldContent>
                </Field>
                <Field orientation="horizontal">
                  <Switch id="bridge-clean" checked={value.cleanSession ?? false} onCheckedChange={(cleanSession) => set({ cleanSession })} />
                  <FieldLabel htmlFor="bridge-clean">{t("bridges.cleanSession")}</FieldLabel>
                </Field>
                <div className="grid gap-4 sm:grid-cols-2">
                  <Field>
                    <FieldLabel htmlFor="bridge-client-id">{t("bridges.clientId")}</FieldLabel>
                    <Input id="bridge-client-id" pattern="\S*" className="font-mono text-xs" value={value.remoteClientId ?? ""} onChange={(e) => set({ remoteClientId: e.target.value || undefined })} />
                    <FieldDescription>{t("bridges.clientIdHint")}</FieldDescription>
                  </Field>
                  <Field>
                    <FieldLabel>{t("bridges.protocol")}</FieldLabel>
                    <Select value={value.protocolVersion ?? DEFAULT} onValueChange={(v) => set({ protocolVersion: v === DEFAULT ? undefined : v })}>
                      <SelectTrigger className="w-full">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value={DEFAULT}>{t("config.defaultValue")}</SelectItem>
                        <SelectItem value="mqttv31">MQTT 3.1</SelectItem>
                        <SelectItem value="mqttv311">MQTT 3.1.1</SelectItem>
                        <SelectItem value="mqttv50">MQTT 5</SelectItem>
                      </SelectContent>
                    </Select>
                  </Field>
                  <Field>
                    <FieldLabel>{t("bridges.startType")}</FieldLabel>
                    <Select value={value.startType ?? DEFAULT} onValueChange={(v) => set({ startType: v === DEFAULT ? undefined : v })}>
                      <SelectTrigger className="w-full">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value={DEFAULT}>{t("config.defaultValue")}</SelectItem>
                        <SelectItem value="automatic">automatic</SelectItem>
                        <SelectItem value="lazy">lazy</SelectItem>
                        <SelectItem value="once">once</SelectItem>
                      </SelectContent>
                    </Select>
                  </Field>
                  <Field>
                    <FieldLabel htmlFor="bridge-keepalive">{t("bridges.keepalive")}</FieldLabel>
                    <Input id="bridge-keepalive" type="number" min={0} placeholder={t("config.defaultValue")} value={value.keepalive ?? ""} onChange={(e) => set({ keepalive: e.target.value === "" ? undefined : Number(e.target.value) })} />
                  </Field>
                  <Field>
                    <FieldLabel htmlFor="bridge-restart">{t("bridges.restartTimeout")}</FieldLabel>
                    <Input id="bridge-restart" placeholder={t("config.defaultValue")} value={value.restartTimeout ?? ""} onChange={(e) => set({ restartTimeout: e.target.value || undefined })} />
                  </Field>
                </div>
                <BridgeOptionFields options={options} lazy={value.startType === "lazy"} onChange={setOptions} />
                <Field>
                  <FieldLabel htmlFor="bridge-extra">{t("bridges.extraOptions")}</FieldLabel>
                  <Textarea id="bridge-extra" rows={3} spellCheck={false} className="font-mono text-xs" value={extra} onChange={(e) => setExtra(e.target.value)} />
                  <FieldDescription>{t("bridges.extraOptionsHint")}</FieldDescription>
                </Field>
              </FieldGroup>
            </MoreSettings>
          </FieldGroup>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={saving}>
              {saving && <Spinner />}
              {t("common.save")}
            </Button>
          </DialogFooter>
        </form>
        <ConfirmDialog
          open={confirmRestart}
          onOpenChange={setConfirmRestart}
          title={t("bridges.restartTitle")}
          description={t("bridges.restartDesc")}
          confirmLabel={t("bridges.restartConfirm")}
          onConfirm={() => {
            setConfirmRestart(false)
            void save(true)
          }}
        />
      </DialogContent>
    </Dialog>
  )
}
