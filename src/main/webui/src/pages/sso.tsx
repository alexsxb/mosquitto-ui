import { useQueryClient } from "@tanstack/react-query"
import { CheckIcon, CopyIcon, PencilIcon, PlusIcon, Trash2Icon, UserCheckIcon, XIcon } from "lucide-react"
import { useState, type FormEvent } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"

import { $api, fetchClient, type Schemas } from "@/api/client"
import { providerLoginUrl } from "@/auth/session"
import { ConfirmDialog } from "@/components/confirm-dialog"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { Spinner } from "@/components/ui/spinner"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import { useErrorToast } from "@/lib/errors"
import { formatDateTime } from "@/lib/format"

type Provider = Schemas["ProviderView"]
type Template = Schemas["SsoTemplate"]
type RoleSource = Template["roleSource"]

const GROUPS: Template["group"][] = ["SELF_HOSTED", "CLOUD", "CODE", "ENTERPRISE", "GENERIC"]
/** Select items need a value, this one stands for "nobody" */
const NONE = "__none"

const callbackUrl = (id: string) => `${window.location.origin}${providerLoginUrl(id)}`
const words = (text: string) => text.split(/[\s,]+/).map((w) => w.trim()).filter(Boolean)

function CopyButton({ text }: { text: string }) {
  const { t } = useTranslation()
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon-sm"
      aria-label={t("sso.copy")}
      title={t("sso.copy")}
      onClick={() => void navigator.clipboard.writeText(text).then(() => toast.success(t("sso.copied")))}
    >
      <CopyIcon />
    </Button>
  )
}

/** The page Login: whether the local login is on, and the identity providers with who may come in through them */
export function SsoPage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const settings = $api.useQuery("get", "/api/sso")
  const users = $api.useQuery("get", "/api/local-users")
  const localLogin = $api.useMutation("put", "/api/sso/local-login")
  const remove = $api.useMutation("delete", "/api/sso/providers/{id}")
  const [picking, setPicking] = useState(false)
  const [editing, setEditing] = useState<{ template: Template; provider?: Provider } | null>(null)
  const [deleting, setDeleting] = useState<Provider | null>(null)

  const reload = () => Promise.all([queryClient.invalidateQueries({ queryKey: ["get", "/api/sso"] }), queryClient.invalidateQueries({ queryKey: ["get", "/api/auth/mode"] })])

  if (!settings.data) {
    return <Skeleton className="h-60 w-full" />
  }
  const data = settings.data
  const templateOf = (id: string) => data.templates.find((x) => x.id === id)!
  const localUsers = users.data?.map((u) => u.username) ?? []

  async function setLocal(enabled: boolean) {
    try {
      await localLogin.mutateAsync({ body: { enabled } })
      await reload()
    } catch (error) {
      errorToast(error)
    }
  }

  async function confirmDelete() {
    if (!deleting) {
      return
    }
    try {
      await remove.mutateAsync({ params: { path: { id: deleting.id } } })
      toast.success(t("sso.deleted", { name: deleting.name }))
      await reload()
    } catch (error) {
      errorToast(error)
    }
    setDeleting(null)
  }

  return (
    <div className="grid max-w-4xl gap-6">
      <p className="max-w-2xl text-sm text-muted-foreground">{t("sso.description")}</p>
      <Card>
        <CardHeader>
          <div className="flex items-start justify-between gap-4">
            <div className="grid gap-1.5">
              <CardTitle>{t("sso.localTitle")}</CardTitle>
              <CardDescription>{data.localLoginForced ? t("sso.localForced") : t("sso.localDesc", { count: localUsers.length })}</CardDescription>
            </div>
            <Switch
              checked={data.localLogin}
              disabled={data.localLoginForced || localLogin.isPending}
              onCheckedChange={(on) => void setLocal(on)}
              aria-label={t("sso.localTitle")}
            />
          </div>
        </CardHeader>
      </Card>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-lg font-semibold">{t("sso.providers")}</h2>
        <Button onClick={() => setPicking(true)}>
          <PlusIcon />
          {t("sso.add")}
        </Button>
      </div>
      {data.providers.length === 0 && (
        <Alert>
          <AlertDescription>{t("sso.none")}</AlertDescription>
        </Alert>
      )}
      {data.providers.map((provider) => (
        <ProviderCard
          key={provider.id}
          provider={provider}
          template={templateOf(provider.template)}
          localUsers={localUsers}
          onEdit={() => setEditing({ template: templateOf(provider.template), provider })}
          onDelete={() => setDeleting(provider)}
          onChanged={reload}
        />
      ))}

      {picking && (
        <TemplatePicker
          templates={data.templates}
          onClose={() => setPicking(false)}
          onPick={(template) => {
            setPicking(false)
            setEditing({ template })
          }}
        />
      )}
      {editing && (
        <ProviderDialog
          template={editing.template}
          provider={editing.provider}
          taken={data.providers.map((p) => p.id)}
          localUsers={localUsers}
          onClose={() => setEditing(null)}
          onDone={reload}
        />
      )}
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={t("sso.deleteTitle", { name: deleting?.name })}
        description={t("sso.deleteDesc")}
        confirmLabel={t("common.delete")}
        onConfirm={() => void confirmDelete()}
      />
    </div>
  )
}

/** Who may come in, in a few words */
function AccessSummary({ provider: p }: { provider: Provider }) {
  const { t } = useTranslation()
  const parts: string[] = []
  if (p.allowAll) {
    parts.push(t("sso.summaryAll"))
  }
  if (p.allowRoles.length) {
    parts.push(t("sso.summaryRoles", { roles: p.allowRoles.join(", ") }))
  }
  if (p.githubOrgs.length) {
    parts.push(t("sso.summaryOrgs", { orgs: p.githubOrgs.join(", ") }))
  }
  if (p.allowed.length) {
    parts.push(t("sso.summaryPeople", { count: p.allowed.length }))
  }
  return <>{parts.length ? parts.join(" · ") : t("sso.summaryNobody")}</>
}

function ProviderCard({ provider, template, localUsers, onEdit, onDelete, onChanged }: {
  provider: Provider
  template: Template
  localUsers: string[]
  onEdit: () => void
  onDelete: () => void
  onChanged: () => Promise<unknown>
}) {
  const { t, i18n } = useTranslation()
  const errorToast = useErrorToast()
  const allow = $api.useMutation("post", "/api/sso/providers/{id}/allow")
  const clear = $api.useMutation("delete", "/api/sso/providers/{id}/denied")
  const [as, setAs] = useState<Record<string, string>>({})

  async function letIn(denied: Provider["denied"][number]) {
    const match = denied.email ?? denied.username
    if (!match) {
      return
    }
    const key = denied.subject ?? match
    try {
      await allow.mutateAsync({ params: { path: { id: provider.id } }, body: { match, localUser: as[key] && as[key] !== NONE ? as[key] : null } })
      toast.success(t("sso.allowed", { who: match }))
      await onChanged()
    } catch (error) {
      errorToast(error)
    }
  }

  async function clearDenied() {
    try {
      await clear.mutateAsync({ params: { path: { id: provider.id } } })
      await onChanged()
    } catch (error) {
      errorToast(error)
    }
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="grid gap-1.5">
            <CardTitle className="flex flex-wrap items-center gap-2">
              {provider.name}
              <Badge variant="secondary">{template.name}</Badge>
              {!provider.enabled && <Badge variant="outline">{t("sso.off")}</Badge>}
              {provider.fromEnvironment && <Badge variant="outline">{t("sso.fromEnvironment")}</Badge>}
            </CardTitle>
            <CardDescription>
              <AccessSummary provider={provider} />
            </CardDescription>
          </div>
          <div className="flex">
            <Button variant="ghost" size="icon-sm" aria-label={t("common.edit")} title={t("common.edit")} onClick={onEdit}>
              <PencilIcon />
            </Button>
            <Button variant="ghost" size="icon-sm" aria-label={t("common.delete")} title={t("common.delete")} onClick={onDelete}>
              <Trash2Icon />
            </Button>
          </div>
        </div>
      </CardHeader>
      <CardContent className="grid gap-4 text-sm">
        <div className="grid gap-1">
          <span className="text-muted-foreground">{t("sso.callback")}</span>
          <div className="flex min-w-0 items-center gap-1">
            <code className="truncate">{callbackUrl(provider.id)}</code>
            <CopyButton text={callbackUrl(provider.id)} />
          </div>
        </div>
        {provider.issuer && (
          <div className="grid gap-1">
            <span className="text-muted-foreground">{t("sso.issuer")}</span>
            <code className="truncate">{provider.issuer}</code>
          </div>
        )}
        <div className="text-muted-foreground">
          {provider.lastLogin ? t("sso.lastLogin", { at: formatDateTime(provider.lastLogin as string, i18n.language) }) : t("sso.neverUsed")}
        </div>
        {provider.denied.length > 0 && (
          <div className="grid gap-2 rounded-lg border p-3">
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium">{t("sso.denied", { count: provider.denied.length })}</span>
              <Button variant="ghost" size="sm" onClick={() => void clearDenied()}>
                <XIcon />
                {t("sso.clearDenied")}
              </Button>
            </div>
            <p className="text-muted-foreground">{t("sso.deniedDesc")}</p>
            <ul className="grid gap-2">
              {provider.denied.map((d) => {
                const key = d.subject ?? d.email ?? d.username ?? ""
                return (
                  <li key={key + d.at} className="flex flex-wrap items-center gap-2">
                    <div className="min-w-0 flex-1">
                      <div className="truncate font-medium">{d.email ?? d.username ?? d.subject}</div>
                      <div className="truncate text-xs text-muted-foreground">
                        {[d.username && d.email ? d.username : null, formatDateTime(d.at as string, i18n.language), d.roles.length ? t("sso.deniedRoles", { roles: d.roles.join(", ") }) : null]
                          .filter(Boolean)
                          .join(" · ")}
                      </div>
                    </div>
                    {localUsers.length > 0 && (
                      <Select value={as[key] ?? NONE} onValueChange={(v) => setAs({ ...as, [key]: v })}>
                        <SelectTrigger size="sm" className="w-44" aria-label={t("sso.asLocalUser")}>
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem value={NONE}>{t("sso.ownName")}</SelectItem>
                          {localUsers.map((u) => (
                            <SelectItem key={u} value={u}>
                              {t("sso.asUser", { user: u })}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    )}
                    <Button size="sm" disabled={!d.email && !d.username} onClick={() => void letIn(d)}>
                      <UserCheckIcon />
                      {t("sso.allow")}
                    </Button>
                  </li>
                )
              })}
            </ul>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function TemplatePicker({ templates, onClose, onPick }: { templates: Template[]; onClose: () => void; onPick: (t: Template) => void }) {
  const { t } = useTranslation()
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90svh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{t("sso.pickTitle")}</DialogTitle>
          <DialogDescription>{t("sso.pickDesc")}</DialogDescription>
        </DialogHeader>
        <div className="grid gap-5">
          {GROUPS.map((group) => (
            <section key={group} className="grid gap-2">
              <h3 className="text-xs font-medium tracking-wide text-muted-foreground uppercase">{t(`sso.groups.${group}`)}</h3>
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                {templates
                  .filter((x) => x.group === group)
                  .map((template) => (
                    <Button key={template.id} variant="outline" className="h-auto justify-start py-3 text-left whitespace-normal" onClick={() => onPick(template)}>
                      {template.name}
                    </Button>
                  ))}
              </div>
            </section>
          ))}
        </div>
      </DialogContent>
    </Dialog>
  )
}

/** A new provider of the given kind, or a change of one */
function ProviderDialog({ template, provider, taken, localUsers, onClose, onDone }: {
  template: Template
  provider?: Provider
  taken: string[]
  localUsers: string[]
  onClose: () => void
  onDone: () => Promise<unknown>
}) {
  const { t } = useTranslation()
  const errorToast = useErrorToast()
  const create = $api.useMutation("post", "/api/sso/providers")
  const update = $api.useMutation("put", "/api/sso/providers/{id}")
  const firstId = taken.includes(template.id) ? `${template.id}-2` : template.id
  const [id, setId] = useState(provider?.id ?? firstId)
  const [name, setName] = useState(provider?.name ?? template.name)
  const [enabled, setEnabled] = useState(provider?.enabled ?? true)
  const [issuer, setIssuer] = useState(provider?.issuer ?? "")
  const [clientId, setClientId] = useState(provider?.clientId ?? "")
  const [secret, setSecret] = useState("")
  const [keyId, setKeyId] = useState(provider?.appleKeyId ?? "")
  const [teamId, setTeamId] = useState(provider?.appleTeamId ?? "")
  const [allowAll, setAllowAll] = useState(provider?.allowAll ?? false)
  const [roles, setRoles] = useState((provider?.allowRoles ?? []).join(", "))
  const [orgs, setOrgs] = useState((provider?.githubOrgs ?? []).join(", "))
  const [allowed, setAllowed] = useState<{ match: string; localUser: string }[]>(
    (provider?.allowed ?? []).map((a) => ({ match: a.match, localUser: a.localUser ?? NONE })),
  )
  const [scopes, setScopes] = useState((provider?.scopes ?? template.scopes).join(" "))
  const [claims, setClaims] = useState((provider?.roleClaims ?? template.roleClaims).join(", "))
  const [source, setSource] = useState<RoleSource>(provider?.roleSource ?? template.roleSource)
  const [test, setTest] = useState<Schemas["Discovered"] | null>(null)
  const [testing, setTesting] = useState(false)
  const busy = create.isPending || update.isPending
  const hint = t(`sso.hints.${template.id}` as "sso.hints.oidc", { defaultValue: "" })

  async function discover() {
    setTesting(true)
    setTest(null)
    try {
      const result = await fetchClient.POST("/api/sso/discover", { body: { issuer: issuer.trim() } })
      setTest(result.data ?? null)
    } catch (error) {
      errorToast(error)
    } finally {
      setTesting(false)
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    const body = {
      id: provider ? null : id.trim(),
      name: name.trim(),
      template: template.id,
      enabled,
      issuer: template.issuer ? null : issuer.trim(),
      clientId: clientId.trim(),
      // empty: unchanged when there is one
      clientSecret: secret.trim() === "" && provider ? null : secret,
      appleKeyId: template.appleKey ? keyId.trim() : null,
      appleTeamId: template.appleKey ? teamId.trim() : null,
      scopes: words(scopes),
      roleClaims: words(claims),
      roleSource: source,
      allowRoles: words(roles),
      allowAll,
      allowed: allowed.filter((a) => a.match.trim()).map((a) => ({ match: a.match.trim(), localUser: a.localUser === NONE ? null : a.localUser })),
      githubOrgs: words(orgs),
    }
    try {
      if (provider) {
        await update.mutateAsync({ params: { path: { id: provider.id } }, body })
      } else {
        await create.mutateAsync({ body })
      }
      toast.success(t("sso.saved", { name: body.name }))
      await onDone()
      onClose()
    } catch (error) {
      errorToast(error)
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[92svh] overflow-y-auto sm:max-w-2xl">
        <form onSubmit={(e) => void submit(e)} className="space-y-6">
          <DialogHeader>
            <DialogTitle>{provider ? t("sso.editTitle", { name: provider.name }) : t("sso.newTitle", { kind: template.name })}</DialogTitle>
            <DialogDescription>{provider ? t("sso.dialogDesc") : t("sso.newDesc")}</DialogDescription>
          </DialogHeader>

          <section className="grid gap-2 rounded-lg border bg-muted/40 p-3 text-sm">
            <span className="font-medium">{t("sso.setupTitle", { kind: template.name })}</span>
            {hint && <p className="text-muted-foreground">{hint}</p>}
            <div className="grid gap-1">
              <span className="text-muted-foreground">{t("sso.callback")}</span>
              <div className="flex min-w-0 items-center gap-1">
                <code className="truncate">{callbackUrl(id || "…")}</code>
                <CopyButton text={callbackUrl(id)} />
              </div>
            </div>
            {template.docs && (
              <a href={template.docs} target="_blank" rel="noopener noreferrer" className="underline underline-offset-3">
                {t("sso.docs", { kind: template.name })}
              </a>
            )}
          </section>

          <FieldGroup>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field>
                <FieldLabel htmlFor="sso-name">{t("sso.name")}</FieldLabel>
                <Input id="sso-name" required maxLength={64} value={name} onChange={(e) => setName(e.target.value)} />
                <FieldDescription>{t("sso.nameHint")}</FieldDescription>
              </Field>
              <Field>
                <FieldLabel htmlFor="sso-id">{t("sso.id")}</FieldLabel>
                <Input id="sso-id" required disabled={!!provider} pattern="[a-z0-9][a-z0-9\-]{0,31}" value={id} onChange={(e) => setId(e.target.value.toLowerCase())} />
                <FieldDescription>{t("sso.idHint")}</FieldDescription>
              </Field>
            </div>
            {!template.issuer && (
              <Field>
                <FieldLabel htmlFor="sso-issuer">{t("sso.issuer")}</FieldLabel>
                <div className="flex gap-2">
                  <Input id="sso-issuer" required type="url" placeholder={template.issuerExample ?? ""} value={issuer} onChange={(e) => setIssuer(e.target.value)} />
                  <Button type="button" variant="outline" disabled={!issuer.trim() || testing} onClick={() => void discover()}>
                    {testing && <Spinner />}
                    {t("sso.test")}
                  </Button>
                </div>
                {test && (
                  <FieldDescription className={test.ok ? "text-emerald-600 dark:text-emerald-400" : "text-destructive"}>
                    {test.ok ? (
                      <>
                        <CheckIcon className="inline size-4" /> {t("sso.testOk")}
                      </>
                    ) : (
                      t("sso.testFailed", { error: test.error })
                    )}
                  </FieldDescription>
                )}
                <FieldDescription>{t("sso.issuerHint")}</FieldDescription>
                {issuer.startsWith("http://") && <FieldDescription className="text-amber-700 dark:text-amber-400">{t("sso.httpWarning")}</FieldDescription>}
              </Field>
            )}
            <div className="grid gap-4 sm:grid-cols-2">
              <Field>
                <FieldLabel htmlFor="sso-client">{template.appleKey ? t("sso.servicesId") : t("sso.clientId")}</FieldLabel>
                <Input id="sso-client" required autoComplete="off" value={clientId} onChange={(e) => setClientId(e.target.value)} />
              </Field>
              {!template.appleKey && (
                <Field>
                  <FieldLabel htmlFor="sso-secret">{t("sso.clientSecret")}</FieldLabel>
                  <Input
                    id="sso-secret"
                    type="password"
                    autoComplete="new-password"
                    required={!provider?.secretSet}
                    placeholder={provider?.secretSet ? t("sso.secretKept") : ""}
                    value={secret}
                    onChange={(e) => setSecret(e.target.value)}
                  />
                </Field>
              )}
            </div>
            {template.appleKey && (
              <>
                <div className="grid gap-4 sm:grid-cols-2">
                  <Field>
                    <FieldLabel htmlFor="sso-team">{t("sso.appleTeam")}</FieldLabel>
                    <Input id="sso-team" required value={teamId} onChange={(e) => setTeamId(e.target.value)} />
                  </Field>
                  <Field>
                    <FieldLabel htmlFor="sso-key-id">{t("sso.appleKeyId")}</FieldLabel>
                    <Input id="sso-key-id" required value={keyId} onChange={(e) => setKeyId(e.target.value)} />
                  </Field>
                </div>
                <Field>
                  <FieldLabel htmlFor="sso-key">{t("sso.appleKey")}</FieldLabel>
                  <Textarea
                    id="sso-key"
                    rows={4}
                    className="font-mono text-xs"
                    required={!provider?.secretSet}
                    placeholder={provider?.secretSet ? t("sso.secretKept") : "-----BEGIN PRIVATE KEY-----"}
                    value={secret}
                    onChange={(e) => setSecret(e.target.value)}
                  />
                </Field>
              </>
            )}

            <section className="grid gap-4 rounded-lg border p-3">
              <div>
                <h3 className="font-medium">{t("sso.accessTitle")}</h3>
                <p className="text-sm text-muted-foreground">{t("sso.accessDesc")}</p>
              </div>
              <label className="flex items-start gap-3 text-sm">
                <Switch checked={allowAll} onCheckedChange={setAllowAll} />
                <span>
                  {t("sso.allowAll")}
                  <span className="block text-muted-foreground">{t(template.group === "SELF_HOSTED" || template.group === "ENTERPRISE" ? "sso.allowAllHint" : "sso.allowAllPublic")}</span>
                </span>
              </label>
              {template.roles && (
                <Field>
                  <FieldLabel htmlFor="sso-roles">{t("sso.roles")}</FieldLabel>
                  <Input id="sso-roles" placeholder="mosquitto-admin" value={roles} onChange={(e) => setRoles(e.target.value)} />
                  <FieldDescription>{t("sso.rolesHint")}</FieldDescription>
                </Field>
              )}
              {template.organisations && (
                <Field>
                  <FieldLabel htmlFor="sso-orgs">{t("sso.orgs")}</FieldLabel>
                  <Input id="sso-orgs" placeholder="my-org, my-org/admins" value={orgs} onChange={(e) => setOrgs(e.target.value)} />
                  <FieldDescription>{t("sso.orgsHint")}</FieldDescription>
                </Field>
              )}
              <div className="grid gap-2">
                <span className="text-sm font-medium">{t("sso.people")}</span>
                <span className="text-sm text-muted-foreground">{t("sso.peopleHint")}</span>
                {allowed.map((a, i) => (
                  <div key={i} className="flex flex-wrap items-center gap-2">
                    <Input
                      className="min-w-48 flex-1"
                      aria-label={t("sso.people")}
                      placeholder={template.organisations ? "octocat" : "alice@example.org, *@example.org"}
                      value={a.match}
                      onChange={(e) => setAllowed(allowed.map((x, j) => (j === i ? { ...x, match: e.target.value } : x)))}
                    />
                    <Select value={a.localUser} onValueChange={(v) => setAllowed(allowed.map((x, j) => (j === i ? { ...x, localUser: v } : x)))}>
                      <SelectTrigger className="w-44" aria-label={t("sso.asLocalUser")}>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value={NONE}>{t("sso.ownName")}</SelectItem>
                        {localUsers.map((u) => (
                          <SelectItem key={u} value={u}>
                            {t("sso.asUser", { user: u })}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <Button type="button" variant="ghost" size="icon-sm" aria-label={t("common.delete")} onClick={() => setAllowed(allowed.filter((_, j) => j !== i))}>
                      <Trash2Icon />
                    </Button>
                  </div>
                ))}
                <div>
                  <Button type="button" variant="outline" size="sm" onClick={() => setAllowed([...allowed, { match: "", localUser: NONE }])}>
                    <PlusIcon />
                    {t("sso.addPerson")}
                  </Button>
                </div>
              </div>
            </section>

            <details className="rounded-lg border p-3 text-sm">
              <summary className="cursor-pointer font-medium">{t("sso.advanced")}</summary>
              <div className="mt-4 grid gap-4">
                <Field>
                  <FieldLabel htmlFor="sso-scopes">{t("sso.scopes")}</FieldLabel>
                  <Input id="sso-scopes" value={scopes} onChange={(e) => setScopes(e.target.value)} />
                </Field>
                {template.roles && (
                  <>
                    <Field>
                      <FieldLabel htmlFor="sso-claims">{t("sso.claims")}</FieldLabel>
                      <Input id="sso-claims" value={claims} onChange={(e) => setClaims(e.target.value)} />
                      <FieldDescription>{t("sso.claimsHint")}</FieldDescription>
                    </Field>
                    <Field>
                      <FieldLabel>{t("sso.source")}</FieldLabel>
                      <Select value={source} onValueChange={(v) => setSource(v as RoleSource)}>
                        <SelectTrigger className="w-56">
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          {(["ID_TOKEN", "ACCESS_TOKEN", "USER_INFO"] as const).map((s) => (
                            <SelectItem key={s} value={s}>
                              {t(`sso.sources.${s}`)}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </Field>
                  </>
                )}
              </div>
            </details>

            <label className="flex items-center gap-3 text-sm">
              <Switch checked={enabled} onCheckedChange={setEnabled} />
              {t("sso.enabled")}
            </label>
          </FieldGroup>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={busy}>
              {busy && <Spinner />}
              {t("common.save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
