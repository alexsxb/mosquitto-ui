import { useQueryClient } from "@tanstack/react-query"
import { MailIcon, PencilIcon, PlusIcon, Trash2Icon, XIcon } from "lucide-react"
import { useState, type FormEvent } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"

import { $api, type Schemas } from "@/api/client"
import { ConfirmDialog } from "@/components/confirm-dialog"
import { InspectUnavailable } from "@/components/inspect-unavailable"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { Spinner } from "@/components/ui/spinner"
import { Switch } from "@/components/ui/switch"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { useErrorToast } from "@/lib/errors"
import { formatDateTime } from "@/lib/format"

type MessageRules = Schemas["MessageRules"]
type ContentRule = Schemas["ContentRule"]
type RateRule = Schemas["RateRule"]
type RuleStats = Schemas["RuleStats"]
type Action = Schemas["RuleAction"]

const rulesKey = { queryKey: ["get", "/api/rules"] }
const statusKey = { queryKey: ["get", "/api/rules/status"] }

/** The types a field can be checked for (MessageRulesService, docker/mosquitto/inspect/rules.c) */
const TYPES = ["any", "number", "string", "boolean", "object", "array", "null"] as const
type FieldType = (typeof TYPES)[number]

function isType(type: string): type is FieldType {
  return (TYPES as readonly string[]).includes(type)
}

const ACTIONS: Action[] = ["drop", "disconnect"]

/** Saves all rules at once: the server keeps them as one file */
function useSaveRules() {
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const save = $api.useMutation("put", "/api/rules")
  return {
    pending: save.isPending,
    async run(rules: MessageRules) {
      try {
        await save.mutateAsync({ body: rules })
        await Promise.all([queryClient.invalidateQueries(rulesKey), queryClient.invalidateQueries(statusKey)])
        return true
      } catch (error) {
        errorToast(error)
        return false
      }
    },
  }
}

/**
 * What messages must look like and how many a client may send. The broker refuses what does not hold, before anyone gets it
 * (the inspect plugin, docker/mosquitto/inspect/rules.c); the broker user of this application is never checked.
 */
export function MessageRulesPage() {
  const { t } = useTranslation()
  const rules = $api.useQuery("get", "/api/rules")
  const status = $api.useQuery("get", "/api/rules/status", {}, { refetchInterval: 5_000 })

  if (!rules.data) {
    return <Skeleton className="h-60 w-full" />
  }
  const stats = new Map((status.data?.stats ?? []).map((s) => [s.id, s]))

  return (
    <div className="space-y-6">
      {status.data && !status.data.available && <InspectUnavailable reason={status.data.reason} />}
      {status.data?.error && (
        <Alert variant="destructive">
          <AlertTitle>{t("messageRules.fileError")}</AlertTitle>
          <AlertDescription>{status.data.error}</AlertDescription>
        </Alert>
      )}
      <ContentRulesCard rules={rules.data} stats={stats} />
      <RateRulesCard rules={rules.data} stats={stats} />
      <Card>
        <CardHeader>
          <CardTitle>{t("messageRules.howTitle")}</CardTitle>
        </CardHeader>
        <CardContent className="text-muted-foreground space-y-2 text-sm">
          <p>{t("messageRules.how1")}</p>
          <p>{t("messageRules.how2")}</p>
          <p>{t("messageRules.how3")}</p>
        </CardContent>
      </Card>
    </div>
  )
}

function Dropped({ stats }: { stats: RuleStats | undefined }) {
  const { t, i18n } = useTranslation()
  if (!stats) {
    return <span className="text-muted-foreground">–</span>
  }
  return (
    <div>
      <div className={stats.dropped > 0 ? "font-medium" : "text-muted-foreground"}>{new Intl.NumberFormat(i18n.language).format(stats.dropped)}</div>
      {stats.lastAt && (
        <div className="text-muted-foreground text-xs">
          {formatDateTime(stats.lastAt as string, i18n.language)} · <span className="font-mono break-all">{stats.lastClient}</span>
          <div>{stats.lastReason}</div>
        </div>
      )}
      {!stats.lastAt && stats.dropped === 0 && <div className="text-muted-foreground text-xs">{t("messageRules.sinceStart")}</div>}
    </div>
  )
}

function ActionBadge({ action, alert }: { action: Action; alert: boolean }) {
  const { t } = useTranslation()
  return (
    <span className="inline-flex items-center gap-1">
      <Badge variant={action === "disconnect" ? "destructive" : "outline"}>{t(`messageRules.actions.${action}`)}</Badge>
      {alert && <MailIcon className="text-muted-foreground size-3.5" aria-label={t("messageRules.alert")} />}
    </span>
  )
}

function ContentRulesCard({ rules, stats }: { rules: MessageRules; stats: Map<string, RuleStats> }) {
  const { t } = useTranslation()
  const save = useSaveRules()
  const [editing, setEditing] = useState<ContentRule | "new" | null>(null)
  const [deleting, setDeleting] = useState<ContentRule | null>(null)

  async function store(content: ContentRule[]) {
    return save.run({ ...rules, content })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("messageRules.contentTitle")}</CardTitle>
        <CardDescription>{t("messageRules.contentDescription")}</CardDescription>
        <CardAction>
          <Button type="button" variant="outline" size="sm" onClick={() => setEditing("new")}>
            <PlusIcon />
            {t("messageRules.add")}
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="overflow-x-auto">
        {rules.content.length === 0 ? (
          <p className="text-muted-foreground text-sm">{t("messageRules.noContent")}</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("messageRules.topic")}</TableHead>
                <TableHead className="hidden md:table-cell">{t("messageRules.checks")}</TableHead>
                <TableHead className="hidden md:table-cell">{t("messageRules.action")}</TableHead>
                <TableHead className="hidden md:table-cell">{t("messageRules.dropped")}</TableHead>
                <TableHead>{t("messageRules.enabled")}</TableHead>
                <TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {rules.content.map((rule) => (
                <TableRow key={rule.id}>
                  <TableCell className="max-w-56 whitespace-normal md:max-w-64">
                    {rule.name && <div className="font-medium">{rule.name}</div>}
                    <div className="font-mono text-xs break-all whitespace-normal">{rule.topic}</div>
                    <div className="text-muted-foreground text-xs">{rule.clients ? t("messageRules.onlyClients", { clients: rule.clients }) : t("messageRules.allClients")}</div>
                    {/* the other columns are not there on a phone */}
                    <div className="mt-1 space-y-1 md:hidden">
                      <ActionBadge action={rule.action} alert={rule.alert} />
                      <Dropped stats={stats.get(rule.id ?? "")} />
                    </div>
                  </TableCell>
                  <TableCell className="hidden max-w-72 whitespace-normal md:table-cell">
                    <div className="flex flex-wrap gap-1">
                      {rule.json && <Badge variant="secondary">JSON</Badge>}
                      {rule.maxBytes != null && <Badge variant="secondary">{t("messageRules.atMostBytes", { count: rule.maxBytes })}</Badge>}
                      {rule.fields.map((field) => (
                        <Badge key={field.path} variant="outline" className="font-mono">
                          {field.path}: {field.type}
                        </Badge>
                      ))}
                    </div>
                  </TableCell>
                  <TableCell className="hidden md:table-cell">
                    <ActionBadge action={rule.action} alert={rule.alert} />
                  </TableCell>
                  <TableCell className="hidden max-w-64 whitespace-normal md:table-cell">
                    <Dropped stats={stats.get(rule.id ?? "")} />
                  </TableCell>
                  <TableCell>
                    <Switch
                      checked={rule.enabled}
                      aria-label={t("messageRules.enabled")}
                      disabled={save.pending}
                      onCheckedChange={(enabled) => void store(rules.content.map((r) => (r.id === rule.id ? { ...r, enabled } : r)))}
                    />
                  </TableCell>
                  <TableCell>
                    <div className="flex">
                      <Button variant="ghost" size="icon-sm" aria-label={t("messageRules.edit")} onClick={() => setEditing(rule)}>
                        <PencilIcon />
                      </Button>
                      <Button variant="ghost" size="icon-sm" aria-label={t("messageRules.delete")} onClick={() => setDeleting(rule)}>
                        <Trash2Icon />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
      {editing !== null && (
        <ContentRuleDialog
          rule={editing === "new" ? null : editing}
          pending={save.pending}
          onClose={() => setEditing(null)}
          onSave={async (rule) => {
            const content = editing === "new" ? [...rules.content, rule] : rules.content.map((r) => (r.id === rule.id ? rule : r))
            if (await store(content)) {
              toast.success(t("messageRules.saved"))
              setEditing(null)
            }
          }}
        />
      )}
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={t("messageRules.deleteTitle", { name: deleting?.name ?? deleting?.topic })}
        description={t("messageRules.deleteDescription")}
        onConfirm={() => {
          const id = deleting?.id
          setDeleting(null)
          void store(rules.content.filter((r) => r.id !== id))
        }}
      />
    </Card>
  )
}

function ContentRuleDialog({
  rule,
  pending,
  onClose,
  onSave,
}: {
  rule: ContentRule | null
  pending: boolean
  onClose: () => void
  onSave: (rule: ContentRule) => Promise<void>
}) {
  const { t } = useTranslation()
  const [name, setName] = useState(rule?.name ?? "")
  const [topic, setTopic] = useState(rule?.topic ?? "")
  const [clients, setClients] = useState(rule?.clients ?? "")
  const [json, setJson] = useState(rule?.json ?? true)
  const [maxBytes, setMaxBytes] = useState(rule?.maxBytes != null ? String(rule.maxBytes) : "")
  const [fields, setFields] = useState(rule?.fields ?? [])
  const [action, setAction] = useState<Action>(rule?.action ?? "drop")
  const [alert, setAlert] = useState(rule?.alert ?? false)

  function submit(event: FormEvent) {
    event.preventDefault()
    void onSave({
      id: rule?.id ?? null,
      name: name.trim() || null,
      topic: topic.trim(),
      clients: clients.trim() || null,
      json: json || fields.length > 0,
      maxBytes: maxBytes.trim() ? Number(maxBytes) : null,
      fields: fields.filter((f) => f.path.trim()),
      action,
      alert,
      enabled: rule?.enabled ?? true,
    })
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-xl">
        <form onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>{rule ? t("messageRules.editContent") : t("messageRules.newContent")}</DialogTitle>
            <DialogDescription>{t("messageRules.contentDialog")}</DialogDescription>
          </DialogHeader>
          <FieldGroup className="my-4">
            <Field>
              <FieldLabel htmlFor="rule-name">{t("messageRules.name")}</FieldLabel>
              <Input id="rule-name" value={name} onChange={(e) => setName(e.target.value)} placeholder={t("messageRules.namePlaceholder")} />
            </Field>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field>
                <FieldLabel htmlFor="rule-topic">{t("messageRules.topic")}</FieldLabel>
                <Input id="rule-topic" className="font-mono" required value={topic} onChange={(e) => setTopic(e.target.value)} placeholder="shelly/#" />
              </Field>
              <Field>
                <FieldLabel htmlFor="rule-clients">{t("messageRules.clients")}</FieldLabel>
                <Input id="rule-clients" className="font-mono" value={clients} onChange={(e) => setClients(e.target.value)} placeholder="*" />
                <FieldDescription>{t("messageRules.clientsHint")}</FieldDescription>
              </Field>
            </div>
            <div className="grid items-start gap-4 sm:grid-cols-2">
              <Field orientation="horizontal">
                <Switch id="rule-json" checked={json || fields.length > 0} disabled={fields.length > 0} onCheckedChange={setJson} />
                <FieldLabel htmlFor="rule-json">{t("messageRules.json")}</FieldLabel>
              </Field>
              <Field>
                <FieldLabel htmlFor="rule-max">{t("messageRules.maxBytes")}</FieldLabel>
                <Input id="rule-max" type="number" min={0} value={maxBytes} onChange={(e) => setMaxBytes(e.target.value)} placeholder={t("messageRules.noLimit")} />
              </Field>
            </div>
            <Field>
              <FieldLabel>{t("messageRules.fields")}</FieldLabel>
              <FieldDescription>{t("messageRules.fieldsHint")}</FieldDescription>
              <div className="space-y-2">
                {fields.map((field, index) => (
                  <div key={index} className="flex gap-2">
                    <Input
                      className="font-mono"
                      aria-label={t("messageRules.fieldPath")}
                      value={field.path}
                      placeholder="aenergy.total"
                      onChange={(e) => setFields(fields.map((f, i) => (i === index ? { ...f, path: e.target.value } : f)))}
                    />
                    <Select value={isType(field.type) ? field.type : "any"} onValueChange={(type) => setFields(fields.map((f, i) => (i === index ? { ...f, type } : f)))}>
                      <SelectTrigger className="w-36" aria-label={t("messageRules.fieldType")}>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {TYPES.map((type) => (
                          <SelectItem key={type} value={type}>
                            {t(`messageRules.types.${type}`)}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <Button type="button" variant="ghost" size="icon" aria-label={t("messageRules.removeField")} onClick={() => setFields(fields.filter((_, i) => i !== index))}>
                      <XIcon />
                    </Button>
                  </div>
                ))}
                <Button type="button" variant="outline" size="sm" onClick={() => setFields([...fields, { path: "", type: "number" }])}>
                  <PlusIcon />
                  {t("messageRules.addField")}
                </Button>
              </div>
            </Field>
            <ActionFields action={action} setAction={setAction} alert={alert} setAlert={setAlert} />
          </FieldGroup>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={pending || !topic.trim()}>
              {pending && <Spinner />}
              {t("common.save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function ActionFields({ action, setAction, alert, setAlert }: { action: Action; setAction: (a: Action) => void; alert: boolean; setAlert: (a: boolean) => void }) {
  const { t } = useTranslation()
  return (
    <div className="grid items-start gap-4 sm:grid-cols-2">
      <Field>
        <FieldLabel>{t("messageRules.action")}</FieldLabel>
        <Select value={action} onValueChange={(value) => setAction(value as Action)}>
          <SelectTrigger aria-label={t("messageRules.action")}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {ACTIONS.map((a) => (
              <SelectItem key={a} value={a}>
                {t(`messageRules.actions.${a}`)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <FieldDescription>{t(`messageRules.actionHints.${action}`)}</FieldDescription>
      </Field>
      <Field orientation="horizontal" className="sm:mt-7">
        <Switch id="rule-alert" checked={alert} onCheckedChange={setAlert} />
        <FieldLabel htmlFor="rule-alert">{t("messageRules.alertLabel")}</FieldLabel>
      </Field>
    </div>
  )
}

function RateRulesCard({ rules, stats }: { rules: MessageRules; stats: Map<string, RuleStats> }) {
  const { t, i18n } = useTranslation()
  const save = useSaveRules()
  const [editing, setEditing] = useState<RateRule | "new" | null>(null)
  const [deleting, setDeleting] = useState<RateRule | null>(null)
  const number = new Intl.NumberFormat(i18n.language)

  async function store(rate: RateRule[]) {
    return save.run({ ...rules, rate })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("messageRules.rateTitle")}</CardTitle>
        <CardDescription>{t("messageRules.rateDescription")}</CardDescription>
        <CardAction>
          <Button type="button" variant="outline" size="sm" onClick={() => setEditing("new")}>
            <PlusIcon />
            {t("messageRules.add")}
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="overflow-x-auto">
        {rules.rate.length === 0 ? (
          <p className="text-muted-foreground text-sm">{t("messageRules.noRate")}</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("messageRules.clients")}</TableHead>
                <TableHead className="hidden sm:table-cell">{t("messageRules.limit")}</TableHead>
                <TableHead className="hidden md:table-cell">{t("messageRules.action")}</TableHead>
                <TableHead className="hidden md:table-cell">{t("messageRules.dropped")}</TableHead>
                <TableHead>{t("messageRules.enabled")}</TableHead>
                <TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {rules.rate.map((rule) => (
                <TableRow key={rule.id}>
                  <TableCell className="max-w-56 whitespace-normal md:max-w-none">
                    {rule.name && <div className="font-medium">{rule.name}</div>}
                    <div className={rule.clients ? "font-mono text-xs" : "text-muted-foreground text-xs"}>{rule.clients ?? t("messageRules.allClients")}</div>
                    <div className="mt-1 space-y-1 md:hidden">
                      <div className="text-sm sm:hidden">{t("messageRules.perSecondValue", { value: number.format(rule.perSecond) })}</div>
                      <ActionBadge action={rule.action} alert={rule.alert} />
                      <Dropped stats={stats.get(rule.id ?? "")} />
                    </div>
                  </TableCell>
                  <TableCell className="hidden whitespace-nowrap sm:table-cell">
                    <div>{t("messageRules.perSecondValue", { value: number.format(rule.perSecond) })}</div>
                    <div className="text-muted-foreground text-xs">{t("messageRules.burstValue", { count: rule.burst ?? Math.max(1, Math.floor(rule.perSecond)) })}</div>
                  </TableCell>
                  <TableCell className="hidden md:table-cell">
                    <ActionBadge action={rule.action} alert={rule.alert} />
                  </TableCell>
                  <TableCell className="hidden max-w-64 whitespace-normal md:table-cell">
                    <Dropped stats={stats.get(rule.id ?? "")} />
                  </TableCell>
                  <TableCell>
                    <Switch
                      checked={rule.enabled}
                      aria-label={t("messageRules.enabled")}
                      disabled={save.pending}
                      onCheckedChange={(enabled) => void store(rules.rate.map((r) => (r.id === rule.id ? { ...r, enabled } : r)))}
                    />
                  </TableCell>
                  <TableCell>
                    <div className="flex">
                      <Button variant="ghost" size="icon-sm" aria-label={t("messageRules.edit")} onClick={() => setEditing(rule)}>
                        <PencilIcon />
                      </Button>
                      <Button variant="ghost" size="icon-sm" aria-label={t("messageRules.delete")} onClick={() => setDeleting(rule)}>
                        <Trash2Icon />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
      {editing !== null && (
        <RateRuleDialog
          rule={editing === "new" ? null : editing}
          pending={save.pending}
          onClose={() => setEditing(null)}
          onSave={async (rule) => {
            const rate = editing === "new" ? [...rules.rate, rule] : rules.rate.map((r) => (r.id === rule.id ? rule : r))
            if (await store(rate)) {
              toast.success(t("messageRules.saved"))
              setEditing(null)
            }
          }}
        />
      )}
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={t("messageRules.deleteTitle", { name: deleting?.name ?? deleting?.clients ?? t("messageRules.allClients") })}
        description={t("messageRules.deleteDescription")}
        onConfirm={() => {
          const id = deleting?.id
          setDeleting(null)
          void store(rules.rate.filter((r) => r.id !== id))
        }}
      />
    </Card>
  )
}

function RateRuleDialog({ rule, pending, onClose, onSave }: { rule: RateRule | null; pending: boolean; onClose: () => void; onSave: (rule: RateRule) => Promise<void> }) {
  const { t } = useTranslation()
  const [name, setName] = useState(rule?.name ?? "")
  const [clients, setClients] = useState(rule?.clients ?? "")
  const [perSecond, setPerSecond] = useState(String(rule?.perSecond ?? 20))
  const [burst, setBurst] = useState(rule?.burst != null ? String(rule.burst) : "")
  const [action, setAction] = useState<Action>(rule?.action ?? "drop")
  const [alert, setAlert] = useState(rule?.alert ?? false)

  function submit(event: FormEvent) {
    event.preventDefault()
    void onSave({
      id: rule?.id ?? null,
      name: name.trim() || null,
      clients: clients.trim() || null,
      perSecond: Number(perSecond),
      burst: burst.trim() ? Number(burst) : null,
      action,
      alert,
      enabled: rule?.enabled ?? true,
    })
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-xl">
        <form onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>{rule ? t("messageRules.editRate") : t("messageRules.newRate")}</DialogTitle>
            <DialogDescription>{t("messageRules.rateDialog")}</DialogDescription>
          </DialogHeader>
          <FieldGroup className="my-4">
            <Field>
              <FieldLabel htmlFor="rate-name">{t("messageRules.name")}</FieldLabel>
              <Input id="rate-name" value={name} onChange={(e) => setName(e.target.value)} placeholder={t("messageRules.namePlaceholder")} />
            </Field>
            <Field>
              <FieldLabel htmlFor="rate-clients">{t("messageRules.clients")}</FieldLabel>
              <Input id="rate-clients" className="font-mono" value={clients} onChange={(e) => setClients(e.target.value)} placeholder="*" />
              <FieldDescription>{t("messageRules.clientsHint")}</FieldDescription>
            </Field>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field>
                <FieldLabel htmlFor="rate-per-second">{t("messageRules.perSecond")}</FieldLabel>
                <Input id="rate-per-second" type="number" min={0.01} step="any" required value={perSecond} onChange={(e) => setPerSecond(e.target.value)} />
              </Field>
              <Field>
                <FieldLabel htmlFor="rate-burst">{t("messageRules.burst")}</FieldLabel>
                <Input id="rate-burst" type="number" min={1} value={burst} onChange={(e) => setBurst(e.target.value)} placeholder={perSecond} />
                <FieldDescription>{t("messageRules.burstHint")}</FieldDescription>
              </Field>
            </div>
            <ActionFields action={action} setAction={setAction} alert={alert} setAlert={setAlert} />
          </FieldGroup>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={pending || !(Number(perSecond) > 0)}>
              {pending && <Spinner />}
              {t("common.save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
