import { useQueryClient } from "@tanstack/react-query"
import { ArrowLeftIcon, PencilIcon, PlusIcon, RefreshCwIcon, Trash2Icon, TriangleAlertIcon } from "lucide-react"
import { useState, type FormEvent } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"

import { $api, type Schemas } from "@/api/client"
import { ConfirmDialog } from "@/components/confirm-dialog"
import { MoreSettings } from "@/components/more-settings"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Field, FieldContent, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { Spinner } from "@/components/ui/spinner"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import { useErrorToast } from "@/lib/errors"

type Settings = Schemas["CertificateSettings"]
type CertificateSet = Schemas["CertificateSetView"]
type Provider = "ACME_DNS" | "PDNS"
type Step = "account" | "order" | "dns" | "propagation" | "validation" | "certificate" | "install" | "listener"

const certificateKey = ["get", "/api/certificate"]
const LETS_ENCRYPT = "acme://letsencrypt.org"
const STAGING = "acme://letsencrypt.org/staging"
const ZEROSSL = "acme://zerossl.com"
const GOOGLE = "acme://pki.goog"
const presets = [
  { directory: STAGING, label: "caStaging" },
  { directory: LETS_ENCRYPT, label: "caLetsEncrypt" },
  { directory: ZEROSSL, label: "caZeroSsl" },
  { directory: GOOGLE, label: "caGoogle" },
] as const
const CUSTOM = "custom"
const POLL_MS = 2000
const TLS_PORT = 8883

// Staging first: a mistake in the settings must not run into the rate limits of the real CA
const defaults: Settings = {
  provider: "ACME_DNS",
  url: "",
  domains: [],
  directory: STAGING,
  keyType: "EC",
  propagationSeconds: 30,
  autoRenew: true,
  acceptTerms: false,
  tlsListener: true,
  tlsPort: TLS_PORT,
}

export function CertificatePage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const view = $api.useQuery("get", "/api/certificate", {}, { refetchInterval: (query) => (query.state.data?.job.running ? POLL_MS : false) })
  const issue = $api.useMutation("post", "/api/certificate/sets/{id}/issue")
  const activate = $api.useMutation("post", "/api/certificate/sets/{id}/activate")
  const remove = $api.useMutation("delete", "/api/certificate/sets/{id}")
  const [editing, setEditing] = useState<string | null>(null)
  const [activating, setActivating] = useState<CertificateSet | null>(null)
  const [deleting, setDeleting] = useState<CertificateSet | null>(null)

  if (view.isPending) {
    return <Skeleton className="h-64 w-full" />
  }
  if (view.isError) {
    throw view.error
  }
  const { available, certfile, certificate, job, keyfile, sets, tlsPort } = view.data
  const refresh = () => queryClient.invalidateQueries({ queryKey: certificateKey })

  async function request(set: CertificateSet) {
    try {
      await issue.mutateAsync({ params: { path: { id: set.id } } })
      toast.success(t("certificate.requested"))
      await refresh()
    } catch (error) {
      errorToast(error)
    }
  }

  async function confirmActivate() {
    if (!activating) {
      return
    }
    try {
      await activate.mutateAsync({ params: { path: { id: activating.id } } })
      toast.success(t("certificate.activated", { name: activating.name }))
    } catch (error) {
      errorToast(error)
    }
    setActivating(null)
    await refresh()
  }

  async function confirmDelete() {
    if (!deleting) {
      return
    }
    try {
      await remove.mutateAsync({ params: { path: { id: deleting.id } } })
      toast.success(t("certificate.setDeleted"))
      await refresh()
    } catch (error) {
      errorToast(error)
    }
    setDeleting(null)
  }

  const edited = sets.find((set) => set.id === editing)
  if (editing !== null) {
    return (
      <SetEditor
        key={editing}
        set={edited ?? null}
        available={available}
        running={job.running}
        onClose={() => {
          setEditing(null)
          void refresh()
        }}
      />
    )
  }

  const activeSet = sets.find((set) => set.active)
  const failedSet = sets.find((set) => set.id === job.setId)
  const needsRestart = (set: CertificateSet) => set.certificate?.keyType !== certificate?.keyType || (set.settings.tlsListener && (set.settings.tlsPort ?? TLS_PORT) !== tlsPort)

  return (
    <div className="max-w-4xl space-y-4">
      {!available && (
        <Alert variant="destructive">
          <TriangleAlertIcon />
          <AlertTitle>{t("config.agentMissingTitle")}</AlertTitle>
          <AlertDescription>{t("config.agentMissing")}</AlertDescription>
        </Alert>
      )}
      {job.error && (
        <Alert variant="destructive">
          <TriangleAlertIcon />
          <AlertTitle>
            {t("certificate.failed")}
            {failedSet && ` (${failedSet.name})`}
          </AlertTitle>
          <AlertDescription className="break-words">{job.error}</AlertDescription>
        </Alert>
      )}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          {tlsPort ? <Badge>{t("certificate.tlsActive", { port: tlsPort })}</Badge> : <span>{t("certificate.setsHint")}</span>}
        </div>
        <Button onClick={() => setEditing("new")}>
          <PlusIcon />
          {t("certificate.addSet")}
        </Button>
      </div>
      {activeSet && !tlsPort && (
        <div className="space-y-2 text-sm">
          <p className="text-muted-foreground">{t("certificate.hint")}</p>
          <pre className="overflow-x-auto rounded-lg border p-3 font-mono text-xs">{`certfile ${certfile}\nkeyfile ${keyfile}`}</pre>
        </div>
      )}
      {sets.length === 0 ? (
        <p className="rounded-lg border p-6 text-center text-sm text-muted-foreground">{t("certificate.empty")}</p>
      ) : (
        [...sets]
          .sort((a, b) => Number(b.active) - Number(a.active))
          .map((set) => (
            <SetCard
              key={set.id}
              set={set}
              available={available}
              running={job.running}
              step={job.running && job.setId === set.id ? job.step : null}
              onActivate={() => setActivating(set)}
              onDelete={() => setDeleting(set)}
              onEdit={() => setEditing(set.id)}
              onIssue={() => void request(set)}
            />
          ))
      )}
      <ConfirmDialog
        open={activating !== null}
        onOpenChange={(open) => !open && setActivating(null)}
        title={t("certificate.activateTitle", { name: activating?.name })}
        description={t(activating && needsRestart(activating) ? "certificate.activateRestart" : "certificate.activateReload")}
        confirmLabel={t("certificate.activate")}
        destructive={false}
        onConfirm={() => void confirmActivate()}
      />
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={t("certificate.deleteTitle", { name: deleting?.name })}
        description={t("certificate.deleteDesc")}
        onConfirm={() => void confirmDelete()}
      />
    </div>
  )
}

function SetCard({
  set,
  available,
  running,
  step,
  onActivate,
  onDelete,
  onEdit,
  onIssue,
}: {
  set: CertificateSet
  available: boolean
  running: boolean
  step?: string | null
  onActivate: () => void
  onDelete: () => void
  onEdit: () => void
  onIssue: () => void
}) {
  const { t, i18n } = useTranslation()
  const { active, certificate, settings } = set
  const expired = certificate !== null && certificate !== undefined && certificate.daysLeft < 0
  const preset = presets.find((item) => item.directory === settings.directory)

  return (
    <Card className={active ? "border-primary" : undefined}>
      <CardHeader className="flex flex-wrap items-center justify-between gap-2">
        <CardTitle className="flex flex-wrap items-center gap-2">
          {set.name}
          {active && <Badge>{t("certificate.active")}</Badge>}
          {active && certificate?.renewalDue && <Badge variant="destructive">{t("certificate.due")}</Badge>}
          {expired && <Badge variant="destructive">{t("certificate.expired")}</Badge>}
          {!certificate && <Badge variant="secondary">{t("certificate.none")}</Badge>}
        </CardTitle>
        <div className="flex flex-wrap items-center justify-end gap-1">
          {!active && (
            <Button size="sm" disabled={!available || running || !certificate || expired} onClick={onActivate}>
              {t("certificate.activate")}
            </Button>
          )}
          <Button size="sm" variant="outline" disabled={!available || running} onClick={onIssue}>
            <RefreshCwIcon />
            {t(certificate ? "certificate.renew" : "certificate.issue")}
          </Button>
          <Button variant="ghost" size="icon-sm" aria-label={t("common.edit")} onClick={onEdit}>
            <PencilIcon />
          </Button>
          <Button variant="ghost" size="icon-sm" aria-label={t("common.delete")} disabled={active || !available || running} onClick={onDelete}>
            <Trash2Icon />
          </Button>
        </div>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        <dl className="grid gap-x-4 gap-y-1 sm:grid-cols-[10rem_1fr]">
          <dt className="text-muted-foreground">{t("certificate.domains")}</dt>
          <dd className="break-words">{(certificate?.domains ?? settings.domains).join(", ")}</dd>
          <dt className="text-muted-foreground">{t("certificate.caLabel")}</dt>
          <dd className="break-words">{preset ? t(`certificate.${preset.label}`) : settings.directory}</dd>
          {certificate && (
            <>
              <dt className="text-muted-foreground">{t("certificate.expires")}</dt>
              <dd>
                {new Date(certificate.notAfter).toLocaleDateString(i18n.resolvedLanguage)} ({certificate.daysLeft})
              </dd>
              <dt className="text-muted-foreground">{t("certificate.issuer")}</dt>
              <dd className="break-words">{certificate.issuer}</dd>
            </>
          )}
        </dl>
        {step && (
          <p className="flex items-center gap-2 text-muted-foreground">
            <Spinner />
            {t(`certificate.steps.${step as Step}`)}
          </p>
        )}
      </CardContent>
    </Card>
  )
}

/** The settings of a set, instead of the list. */
function SetEditor({ set, available, running, onClose }: { set: CertificateSet | null; available: boolean; running: boolean; onClose: () => void }) {
  const { t } = useTranslation()
  const errorToast = useErrorToast()
  const create = $api.useMutation("post", "/api/certificate/sets")
  const update = $api.useMutation("put", "/api/certificate/sets/{id}")
  const issue = $api.useMutation("post", "/api/certificate/sets/{id}/issue")

  const settings = set?.settings ?? defaults
  const tokenSet = set?.tokenSet ?? false
  const eabKeySet = set?.eabKeySet ?? false
  const [name, setName] = useState(set?.name ?? "")
  const [value, setValue] = useState(settings)
  const [domains, setDomains] = useState(settings.domains.join("\n"))
  const [token, setToken] = useState("")
  const [eabKey, setEabKey] = useState("")
  const known = presets.some((preset) => preset.directory === value.directory)
  const [custom, setCustom] = useState(!known)
  const change = (changed: Partial<Settings>) => setValue({ ...value, ...changed })
  const saving = create.isPending || update.isPending || issue.isPending

  /** The id of the set as it is stored now. */
  async function persist() {
    const body = { name, settings: { ...value, token: token || undefined, eabHmacKey: eabKey || undefined, domains: domains.split(/[\s,]+/).filter(Boolean) } }
    if (set) {
      await update.mutateAsync({ params: { path: { id: set.id } }, body })
      return set.id
    }
    return (await create.mutateAsync({ body })).id
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    try {
      await persist()
      toast.success(t("certificate.saved"))
      onClose()
    } catch (error) {
      errorToast(error)
    }
  }

  async function request(event: FormEvent) {
    event.preventDefault()
    try {
      const id = await persist()
      await issue.mutateAsync({ params: { path: { id } } })
      toast.success(t("certificate.requested"))
      onClose()
    } catch (error) {
      errorToast(error)
    }
  }

  const acmeDns = value.provider === "ACME_DNS"
  const caValue = custom ? CUSTOM : value.directory
  const needsAccount = custom || value.directory === ZEROSSL || value.directory === GOOGLE

  return (
    <form onSubmit={submit} className="max-w-4xl space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" variant="ghost" size="sm" onClick={onClose}>
          <ArrowLeftIcon />
          {t("certificate.backToList")}
        </Button>
        <h2 className="font-medium">{set ? t("certificate.editTitle", { name: set.name }) : t("certificate.addSet")}</h2>
        {set?.active && <Badge>{t("certificate.active")}</Badge>}
      </div>
      {set?.certificate && <p className="text-sm text-muted-foreground">{t("certificate.editHint")}</p>}
      <Card>
        <CardHeader>
          <CardTitle>{t("certificate.settings")}</CardTitle>
        </CardHeader>
        <CardContent>
          <FieldGroup>
            <Field>
              <FieldLabel htmlFor="cert-name">{t("certificate.setName")}</FieldLabel>
              <Input id="cert-name" required maxLength={64} autoFocus={!set} value={name} onChange={(e) => setName(e.target.value)} />
              <FieldDescription>{t("certificate.setNameHint")}</FieldDescription>
            </Field>
            <Field>
              <FieldLabel htmlFor="cert-domains">{t("certificate.domains")}</FieldLabel>
              <Textarea id="cert-domains" required rows={1} className="font-mono text-xs" value={domains} onChange={(e) => setDomains(e.target.value)} />
              <FieldDescription>{t("certificate.domainsHint")}</FieldDescription>
            </Field>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field>
                <FieldLabel>{t("certificate.caLabel")}</FieldLabel>
                <Select
                  value={caValue}
                  onValueChange={(v) => {
                    setCustom(v === CUSTOM)
                    if (v !== CUSTOM) {
                      change({ directory: v })
                    }
                  }}
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {presets.map((preset) => (
                      <SelectItem key={preset.directory} value={preset.directory}>
                        {t(`certificate.${preset.label}`)}
                      </SelectItem>
                    ))}
                    <SelectItem value={CUSTOM}>{t("certificate.caCustom")}</SelectItem>
                  </SelectContent>
                </Select>
                {custom && <Input aria-label={t("certificate.caCustom")} required className="font-mono text-xs" value={value.directory} onChange={(e) => change({ directory: e.target.value })} />}
              </Field>
              <Field>
                <FieldLabel htmlFor="cert-email">{t("certificate.email")}</FieldLabel>
                <Input id="cert-email" type="email" value={value.email ?? ""} onChange={(e) => change({ email: e.target.value })} />
              </Field>
              <Field>
                <FieldLabel>{t("certificate.keyType")}</FieldLabel>
                <Select value={value.keyType} onValueChange={(keyType) => change({ keyType })}>
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="EC">ECDSA P-256</SelectItem>
                    <SelectItem value="RSA">RSA 2048</SelectItem>
                  </SelectContent>
                </Select>
                <FieldDescription>{t("certificate.keyTypeHint")}</FieldDescription>
              </Field>
            </div>
            {needsAccount && (
              <div className="grid gap-4 sm:grid-cols-2">
                <Field>
                  <FieldLabel htmlFor="cert-eab-id">{t("certificate.eabKeyId")}</FieldLabel>
                  <Input id="cert-eab-id" autoComplete="off" required={!custom} className="font-mono text-xs" value={value.eabKeyId ?? ""} onChange={(e) => change({ eabKeyId: e.target.value })} />
                  <FieldDescription>{t(value.directory === GOOGLE ? "certificate.eabHintGoogle" : custom ? "certificate.eabHintCustom" : "certificate.eabHintZeroSsl")}</FieldDescription>
                </Field>
                <Field>
                  <FieldLabel htmlFor="cert-eab-key">{t("certificate.eabHmacKey")}</FieldLabel>
                  <Input id="cert-eab-key" type="password" autoComplete="off" required={!custom && !eabKeySet} value={eabKey} onChange={(e) => setEabKey(e.target.value)} />
                  {eabKeySet && <FieldDescription>{t("certificate.tokenKeep")}</FieldDescription>}
                </Field>
              </div>
            )}
            <Field orientation="horizontal">
              <Switch id="cert-renew" checked={value.autoRenew} onCheckedChange={(autoRenew) => change({ autoRenew })} />
              <FieldContent>
                <FieldLabel htmlFor="cert-renew">{t("certificate.autoRenew")}</FieldLabel>
                <FieldDescription>{t("certificate.autoRenewHint")}</FieldDescription>
              </FieldContent>
            </Field>
            <div className="flex flex-wrap items-start justify-between gap-4">
              <Field orientation="horizontal" className="flex-1 basis-72">
                <Switch id="cert-listener" checked={value.tlsListener} onCheckedChange={(tlsListener) => change({ tlsListener })} />
                <FieldContent>
                  <FieldLabel htmlFor="cert-listener">{t("certificate.tlsListener")}</FieldLabel>
                  <FieldDescription>{t("certificate.tlsListenerHint")}</FieldDescription>
                </FieldContent>
              </Field>
              {value.tlsListener && (
                <Input id="cert-tls-port" aria-label={t("certificate.tlsPort")} className="w-24" type="number" min={1} max={65535} required value={value.tlsPort ?? TLS_PORT} onChange={(e) => change({ tlsPort: Number(e.target.value) })} />
              )}
            </div>
          </FieldGroup>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>{t("certificate.dnsTitle")}</CardTitle>
        </CardHeader>
        <CardContent>
          <FieldGroup>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field>
                <FieldLabel>{t("certificate.provider")}</FieldLabel>
                <Select value={value.provider} onValueChange={(provider) => change({ provider })}>
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {(["ACME_DNS", "PDNS"] as Provider[]).map((provider) => (
                      <SelectItem key={provider} value={provider}>
                        {t(`certificate.providers.${provider}`)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </Field>
              <Field>
                <FieldLabel htmlFor="cert-url">{t("certificate.url")}</FieldLabel>
                <Input id="cert-url" type="url" required value={value.url} onChange={(e) => change({ url: e.target.value })} />
                <FieldDescription>{acmeDns ? t("certificate.urlHintAcmeDns") : t("certificate.urlHintPdns")}</FieldDescription>
              </Field>
              <Field>
                <FieldLabel htmlFor="cert-token">{t("certificate.token")}</FieldLabel>
                <Input id="cert-token" type="password" autoComplete="off" required={!tokenSet} value={token} onChange={(e) => setToken(e.target.value)} />
                {tokenSet && <FieldDescription>{t("certificate.tokenKeep")}</FieldDescription>}
              </Field>
              <Field>
                <FieldLabel htmlFor="cert-propagation">{t("certificate.propagation")}</FieldLabel>
                <Input id="cert-propagation" type="number" min={0} max={3600} value={value.propagationSeconds} onChange={(e) => change({ propagationSeconds: Number(e.target.value) })} />
                <FieldDescription>{t("certificate.propagationHint")}</FieldDescription>
              </Field>
            </div>
            {acmeDns && (
              <MoreSettings label={t("bridges.advanced")} open={Boolean(value.apiUser || value.subdomain)}>
                <div className="grid gap-4 sm:grid-cols-2">
                  <Field>
                    <FieldLabel htmlFor="cert-user">{t("certificate.apiUser")}</FieldLabel>
                    <Input id="cert-user" value={value.apiUser ?? ""} onChange={(e) => change({ apiUser: e.target.value })} />
                    <FieldDescription>{t("certificate.apiUserHint")}</FieldDescription>
                  </Field>
                  <Field>
                    <FieldLabel htmlFor="cert-subdomain">{t("certificate.subdomain")}</FieldLabel>
                    <Input id="cert-subdomain" value={value.subdomain ?? ""} onChange={(e) => change({ subdomain: e.target.value })} />
                    <FieldDescription>{t("certificate.subdomainHint")}</FieldDescription>
                  </Field>
                </div>
              </MoreSettings>
            )}
          </FieldGroup>
        </CardContent>
      </Card>

      {!settings.acceptTerms && (
        <div className="flex items-center gap-2">
          <Checkbox id="cert-terms" checked={value.acceptTerms} onCheckedChange={(checked) => change({ acceptTerms: checked === true })} />
          <Label htmlFor="cert-terms" className="font-normal">
            {t("certificate.acceptTerms")}
          </Label>
        </div>
      )}
      <div className="flex flex-wrap items-center justify-end gap-2">
        <Button type="button" variant="ghost" onClick={onClose}>
          {t("common.cancel")}
        </Button>
        <Button type="submit" variant="outline" disabled={saving}>
          {t("common.save")}
        </Button>
        <Button type="button" disabled={saving || running || !available || !value.acceptTerms} onClick={(event) => void request(event)}>
          {t("certificate.saveAndIssue")}
        </Button>
      </div>
    </form>
  )
}
