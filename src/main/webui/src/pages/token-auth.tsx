import { useQueryClient } from "@tanstack/react-query"
import { BanIcon, PlusIcon, RefreshCwIcon, TrashIcon, UndoIcon } from "lucide-react"
import { useState, type FormEvent } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"

import { $api, type Schemas } from "@/api/client"
import { IssuedTokensCard } from "@/components/issued-tokens"
import { MultiSelect } from "@/components/multi-select"
import { TokenBlockDialog } from "@/components/token-block-dialog"
import { TokenRejectionsCard } from "@/components/token-rejections"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import { Spinner } from "@/components/ui/spinner"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Textarea } from "@/components/ui/textarea"
import { useErrorToast } from "@/lib/errors"
import { formatDateTime } from "@/lib/format"

type Settings = Schemas["TokenAuthSettings"]
type Issuer = Schemas["TokenAuthIssuer"]
type Status = Schemas["IssuerStatus"]
type KeySource = Schemas["KeySource"]

const keySources: KeySource[] = ["discovery", "jwks", "keys"]

/** An issuer as the form edits it: the mappings with a key of their own, so that rows keep their state when one goes. */
interface IssuerForm extends Omit<Issuer, "mappings"> {
  key: number
  mappings: { key: number; group: string; roles: string[] }[]
}

let nextKey = 0

function toForm(issuer: Issuer): IssuerForm {
  return { ...issuer, key: nextKey++, mappings: issuer.mappings.map((m) => ({ ...m, key: nextKey++ })) }
}

function emptyIssuer(): IssuerForm {
  return {
    key: nextKey++,
    issuer: "",
    audience: "",
    usernameClaim: "preferred_username",
    groupsClaim: "groups",
    usernamePrefix: "",
    bindClientId: true,
    keySource: "discovery",
    jwksUrl: "",
    staticKeys: "",
    mappings: [],
  }
}

function IssuerStatusLine({ status }: { status?: Status }) {
  const { t, i18n } = useTranslation()
  if (!status) {
    return <p className="text-muted-foreground text-sm">{t("tokenAuth.notFetched")}</p>
  }
  return (
    <div className="space-y-1 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-muted-foreground">{t("tokenAuth.keys", { count: status.keys })}</span>
        {status.kids.map((kid) => (
          <Badge key={kid} variant="outline" className="font-mono">
            {kid}
          </Badge>
        ))}
        {status.fetchedAt && <span className="text-muted-foreground">{t("tokenAuth.fetchedAt", { time: formatDateTime(status.fetchedAt as string, i18n.language) })}</span>}
      </div>
      {status.error && <p className="text-destructive">{t("tokenAuth.fetchError", { error: status.error })}</p>}
    </div>
  )
}

function IssuerCard({
  index,
  issuer,
  status,
  roles,
  onChange,
  onRemove,
}: {
  index: number
  issuer: IssuerForm
  status?: Status
  roles: string[]
  onChange: (issuer: IssuerForm) => void
  onRemove: () => void
}) {
  const { t } = useTranslation()
  const set = (change: Partial<IssuerForm>) => onChange({ ...issuer, ...change })
  const id = `issuer-${issuer.key}`
  const source: KeySource = issuer.keySource ?? "discovery"
  return (
    <Card>
      <CardHeader>
        <CardTitle>{issuer.issuer || t("tokenAuth.newIssuer")}</CardTitle>
        <CardDescription>
          <IssuerStatusLine status={status} />
        </CardDescription>
        <CardAction>
          <Button type="button" variant="ghost" size="icon" aria-label={t("tokenAuth.removeIssuer")} onClick={onRemove}>
            <TrashIcon />
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent>
        <FieldGroup>
          <Field>
            <FieldLabel>{t("tokenAuth.keySource")}</FieldLabel>
            <Select value={source} onValueChange={(v) => set({ keySource: v as KeySource })}>
              <SelectTrigger className="w-full" aria-label={t("tokenAuth.keySource")}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {keySources.map((s) => (
                  <SelectItem key={s} value={s}>
                    {t(`tokenAuth.keySources.${s}`)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <FieldDescription>{t(`tokenAuth.keySourceHints.${source}`)}</FieldDescription>
          </Field>
          <Field>
            <FieldLabel htmlFor={`${id}-url`}>{t("tokenAuth.issuer")}</FieldLabel>
            <Input
              id={`${id}-url`}
              required
              className="font-mono text-xs"
              placeholder={source === "discovery" ? "https://keycloak.example.com/realms/devices" : "fleet-signer"}
              value={issuer.issuer}
              onChange={(e) => set({ issuer: e.target.value })}
            />
            <FieldDescription>{t(source === "discovery" ? "tokenAuth.issuerHint" : "tokenAuth.issuerHintPlain")}</FieldDescription>
          </Field>
          {source === "jwks" && (
            <Field>
              <FieldLabel htmlFor={`${id}-jwks`}>{t("tokenAuth.jwksUrl")}</FieldLabel>
              <Input
                id={`${id}-jwks`}
                required
                className="font-mono text-xs"
                placeholder="https://signer.example.com/.well-known/jwks.json"
                value={issuer.jwksUrl ?? ""}
                onChange={(e) => set({ jwksUrl: e.target.value })}
              />
            </Field>
          )}
          {source === "keys" && (
            <Field>
              <FieldLabel htmlFor={`${id}-keys`}>{t("tokenAuth.staticKeys")}</FieldLabel>
              <Textarea
                id={`${id}-keys`}
                required
                className="h-40 font-mono text-xs"
                placeholder={"-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----"}
                value={issuer.staticKeys ?? ""}
                onChange={(e) => set({ staticKeys: e.target.value })}
              />
              <FieldDescription>{t("tokenAuth.staticKeysHint")}</FieldDescription>
            </Field>
          )}
          <div className="grid gap-3 sm:grid-cols-2">
            <Field>
              <FieldLabel htmlFor={`${id}-aud`}>{t("tokenAuth.audience")}</FieldLabel>
              <Input id={`${id}-aud`} placeholder="mqtt" value={issuer.audience ?? ""} onChange={(e) => set({ audience: e.target.value })} />
              <FieldDescription className={issuer.audience ? undefined : "text-destructive"}>
                {t(issuer.audience ? "tokenAuth.audienceHint" : "tokenAuth.audienceEmpty")}
              </FieldDescription>
            </Field>
            <Field>
              <FieldLabel htmlFor={`${id}-prefix`}>{t("tokenAuth.prefix")}</FieldLabel>
              <Input id={`${id}-prefix`} placeholder={index > 0 ? "idp2:" : ""} value={issuer.usernamePrefix ?? ""} onChange={(e) => set({ usernamePrefix: e.target.value })} />
              <FieldDescription>{t("tokenAuth.prefixHint")}</FieldDescription>
            </Field>
            <Field>
              <FieldLabel htmlFor={`${id}-user`}>{t("tokenAuth.usernameClaim")}</FieldLabel>
              <Input id={`${id}-user`} className="font-mono text-xs" value={issuer.usernameClaim} onChange={(e) => set({ usernameClaim: e.target.value })} />
            </Field>
            <Field>
              <FieldLabel htmlFor={`${id}-groups`}>{t("tokenAuth.groupsClaim")}</FieldLabel>
              <Input id={`${id}-groups`} className="font-mono text-xs" value={issuer.groupsClaim} onChange={(e) => set({ groupsClaim: e.target.value })} />
              <FieldDescription>{t("tokenAuth.groupsClaimHint")}</FieldDescription>
            </Field>
          </div>
          <label className="flex items-start justify-between gap-4">
            <span className="min-w-0">
              <span className="block text-sm font-medium">{t("tokenAuth.bindClientId")}</span>
              <span className="text-muted-foreground block text-sm">{t("tokenAuth.bindClientIdHint")}</span>
            </span>
            <Switch checked={issuer.bindClientId} onCheckedChange={(on) => set({ bindClientId: on })} aria-label={t("tokenAuth.bindClientId")} />
          </label>
          <div className="space-y-2">
            <div className="text-sm font-medium">{t("tokenAuth.mappings")}</div>
            <p className="text-muted-foreground text-sm">{t("tokenAuth.mappingsHint")}</p>
            {issuer.mappings.map((mapping, m) => (
              <div key={mapping.key} className="grid items-start gap-2 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.5fr)_auto]">
                <Input
                  aria-label={t("tokenAuth.group")}
                  placeholder={t("tokenAuth.group")}
                  value={mapping.group}
                  onChange={(e) => set({ mappings: issuer.mappings.map((x, i) => (i === m ? { ...x, group: e.target.value } : x)) })}
                />
                <MultiSelect
                  idPrefix={`${id}-map-${mapping.key}`}
                  label={t("tokenAuth.roles")}
                  names={roles}
                  selected={mapping.roles}
                  onChange={(selected) => set({ mappings: issuer.mappings.map((x, i) => (i === m ? { ...x, roles: selected } : x)) })}
                />
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  aria-label={t("tokenAuth.removeGroup")}
                  onClick={() => set({ mappings: issuer.mappings.filter((_, i) => i !== m) })}
                >
                  <TrashIcon />
                </Button>
              </div>
            ))}
            <Button type="button" variant="outline" size="sm" onClick={() => set({ mappings: [...issuer.mappings, { key: nextKey++, group: "", roles: [] }] })}>
              <PlusIcon />
              {t("tokenAuth.addGroup")}
            </Button>
          </div>
        </FieldGroup>
      </CardContent>
    </Card>
  )
}

function TokenAuthForm({ settings, roles }: { settings: Settings; roles: string[] }) {
  const { t, i18n } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const save = $api.useMutation("put", "/api/token-auth")
  const refresh = $api.useMutation("post", "/api/token-auth/refresh")
  const [enabled, setEnabled] = useState(settings.enabled)
  const [issuers, setIssuers] = useState(() => settings.issuers.map(toForm))

  async function done(result: Settings, message: string) {
    queryClient.setQueryData(["get", "/api/token-auth"], result)
    await queryClient.invalidateQueries({ queryKey: ["get", "/api/token-auth"] })
    const failed = result.status.find((s) => s.error)
    if (failed) {
      toast.warning(t("tokenAuth.fetchError", { error: failed.error }))
    } else {
      toast.success(message)
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    try {
      const result = await save.mutateAsync({
        body: {
          enabled,
          issuers: issuers.map((issuer) => ({
            issuer: issuer.issuer,
            audience: issuer.audience || null,
            usernameClaim: issuer.usernameClaim,
            groupsClaim: issuer.groupsClaim,
            usernamePrefix: issuer.usernamePrefix || null,
            bindClientId: issuer.bindClientId,
            keySource: issuer.keySource ?? "discovery",
            jwksUrl: issuer.keySource === "jwks" ? issuer.jwksUrl || null : null,
            staticKeys: issuer.keySource === "keys" ? issuer.staticKeys || null : null,
            mappings: issuer.mappings.map(({ group, roles }) => ({ group, roles })),
          })),
        },
      })
      await done(result, t("tokenAuth.saved"))
    } catch (error) {
      errorToast(error)
    }
  }

  async function fetchKeys() {
    try {
      await done(await refresh.mutateAsync({}), t("tokenAuth.refreshed"))
    } catch (error) {
      errorToast(error)
    }
  }

  // The cards with dialogs of their own stay outside the form: React passes a submit through portals to the elements around,
  // so a dialog in here would also save these settings, with whatever is not saved yet.
  return (
    <div className="flex flex-col gap-4">
      <form onSubmit={submit} className="flex flex-col gap-4">
        <Card>
          <CardHeader>
            <CardTitle>{t("tokenAuth.title")}</CardTitle>
            <CardDescription>{t("tokenAuth.description")}</CardDescription>
          </CardHeader>
          <CardContent>
            <FieldGroup>
              <label className="flex items-start justify-between gap-4">
                <span className="min-w-0">
                  <span className="block text-sm font-medium">{t("tokenAuth.enabled")}</span>
                  <span className="text-muted-foreground block text-sm">{t("tokenAuth.enabledHint")}</span>
                </span>
                <Switch checked={enabled} onCheckedChange={setEnabled} aria-label={t("tokenAuth.enabled")} />
              </label>
              {settings.written && <p className="text-muted-foreground text-sm">{t("tokenAuth.written", { time: formatDateTime(settings.written as string, i18n.language) })}</p>}
              {settings.writeError && (
                <Alert variant="destructive">
                  <AlertDescription>{t("tokenAuth.writeError", { error: settings.writeError })}</AlertDescription>
                </Alert>
              )}
              {settings.missingRoles.length > 0 && (
                <Alert variant="destructive">
                  <AlertDescription>{t("tokenAuth.missingRoles", { roles: settings.missingRoles.join(", ") })}</AlertDescription>
                </Alert>
              )}
            </FieldGroup>
          </CardContent>
        </Card>

        {issuers.map((issuer, index) => (
          <IssuerCard
            key={issuer.key}
            index={index}
            issuer={issuer}
            status={settings.status.find((s) => s.issuer === issuer.issuer)}
            roles={roles}
            onChange={(changed) => setIssuers(issuers.map((x, i) => (i === index ? changed : x)))}
            onRemove={() => setIssuers(issuers.filter((_, i) => i !== index))}
          />
        ))}

        <div className="flex flex-wrap items-center gap-2">
          <Button type="button" variant="outline" onClick={() => setIssuers([...issuers, emptyIssuer()])}>
            <PlusIcon />
            {t("tokenAuth.addIssuer")}
          </Button>
          <Button type="submit" disabled={save.isPending}>
            {save.isPending && <Spinner />}
            {t("common.save")}
          </Button>
          <Button type="button" variant="outline" disabled={refresh.isPending || settings.issuers.length === 0} onClick={() => void fetchKeys()}>
            {refresh.isPending ? <Spinner /> : <RefreshCwIcon />}
            {t("tokenAuth.refresh")}
          </Button>
        </div>
      </form>

      <IssuedTokensCard issued={settings.issued} roles={roles} enabled={settings.enabled} />

      <BlocksCard settings={settings} />

      <TokenRejectionsCard issuers={settings.issuers.map((i) => i.issuer)} />

      <Card>
        <CardHeader>
          <CardTitle>{t("tokenAuth.howTitle")}</CardTitle>
          <CardDescription>{t("tokenAuth.howDescription")}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          <pre className="bg-muted overflow-x-auto rounded-md p-3 font-mono text-xs">{`TOKEN=$(curl -s -d grant_type=client_credentials \\
  -d client_id=sensor-1 -d client_secret=... \\
  https://keycloak.example.com/realms/devices/protocol/openid-connect/token | jq -r .access_token)
mosquitto_pub -h broker.example.com -p 8883 -i service-account-sensor-1/main -u token -P "$TOKEN" \\
  -t sensors/service-account-sensor-1/temperature -m 21.5`}</pre>
          <ul className="text-muted-foreground list-disc space-y-1 pl-5">
            <li>{t("tokenAuth.howKeycloak")}</li>
            <li>{t("tokenAuth.howAudience")}</li>
            <li>{t("tokenAuth.howExpiry")}</li>
            <li>{t("tokenAuth.howClients")}</li>
          </ul>
        </CardContent>
      </Card>
    </div>
  )
}

/** User names, subjects and single tokens kept out, with who blocked them, why and until when. */
function BlocksCard({ settings }: { settings: Settings }) {
  const { t, i18n } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const unblock = $api.useMutation("delete", "/api/token-auth/blocks/{id}")
  const [adding, setAdding] = useState(false)

  async function lift(id: string, value: string) {
    try {
      await unblock.mutateAsync({ params: { path: { id } } })
      toast.success(t("tokenBlocks.lifted", { value }))
      await queryClient.invalidateQueries({ queryKey: ["get", "/api/token-auth"] })
    } catch (error) {
      errorToast(error)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("tokenBlocks.listTitle")}</CardTitle>
        <CardDescription>{t("tokenBlocks.listDescription")}</CardDescription>
        <CardAction>
          <Button type="button" variant="outline" size="sm" disabled={settings.issuers.length === 0} onClick={() => setAdding(true)}>
            <BanIcon />
            {t("tokenBlocks.add")}
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="overflow-x-auto">
        {settings.blocks.length === 0 ? (
          <p className="text-muted-foreground text-sm">{t("tokenBlocks.none")}</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("tokenBlocks.field")}</TableHead>
                <TableHead>{t("tokenBlocks.value")}</TableHead>
                <TableHead>{t("tokenBlocks.issuer")}</TableHead>
                <TableHead>{t("tokenBlocks.until")}</TableHead>
                <TableHead>{t("tokenBlocks.reason")}</TableHead>
                <TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {settings.blocks.map((block) => (
                <TableRow key={block.id}>
                  <TableCell>{t(`tokenBlocks.fields.${block.field}`)}</TableCell>
                  <TableCell className="max-w-56 truncate font-mono text-xs" title={block.value}>
                    {block.value}
                  </TableCell>
                  <TableCell className="text-muted-foreground max-w-48 truncate font-mono text-xs" title={block.issuer ?? undefined}>
                    {block.issuer ?? t("tokenBlocks.allIssuers")}
                  </TableCell>
                  <TableCell className="whitespace-nowrap">{block.until ? formatDateTime(block.until as string, i18n.language) : t("tokenBlocks.forGood")}</TableCell>
                  <TableCell>
                    <div>{block.reason ?? "–"}</div>
                    <div className="text-muted-foreground text-xs">
                      {t("tokenBlocks.by", { name: block.createdBy, time: formatDateTime(block.created as string, i18n.language) })}
                    </div>
                  </TableCell>
                  <TableCell>
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon-sm"
                      aria-label={t("tokenBlocks.lift")}
                      title={t("tokenBlocks.lift")}
                      disabled={unblock.isPending}
                      onClick={() => void lift(block.id, block.value)}
                    >
                      <UndoIcon />
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        {adding && <TokenBlockDialog issuers={settings.issuers.map((i) => i.issuer)} onClose={() => setAdding(false)} />}
      </CardContent>
    </Card>
  )
}

/** Login of MQTT clients with an access token: identity providers and which of their groups get which roles. */
export function TokenAuthPage() {
  const settings = $api.useQuery("get", "/api/token-auth")
  const roles = $api.useQuery("get", "/api/roles")
  if (!settings.data || !roles.data) {
    return <Skeleton className="h-96 w-full" />
  }
  return (
    <TokenAuthForm
      key={JSON.stringify(settings.data.issuers) + settings.data.enabled}
      settings={settings.data}
      roles={roles.data.filter((role) => !role.locked).map((role) => role.rolename)}
    />
  )
}
