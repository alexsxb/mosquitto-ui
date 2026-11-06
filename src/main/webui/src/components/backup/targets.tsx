import { useQueryClient } from "@tanstack/react-query"
import { FolderOpenIcon, PencilIcon, PlugZapIcon, PlusIcon, Trash2Icon } from "lucide-react"
import { useState, type FormEvent } from "react"
import type { TFunction } from "i18next"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"

import { $api, type Schemas } from "@/api/client"
import { TargetFilesDialog } from "@/components/backup/target-files"
import { ConfirmDialog } from "@/components/confirm-dialog"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Spinner } from "@/components/ui/spinner"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { useErrorToast } from "@/lib/errors"
import { s3Endpoint, s3Endpoints, s3ProviderOf } from "@/lib/s3-providers"

export type Target = Schemas["TargetView"]
export type TargetType = Target["type"]

const types: TargetType[] = ["SFTP", "FTP", "S3", "LOCAL", "EMAIL"]
const defaultPort: Partial<Record<TargetType, number>> = { SFTP: 22, FTP: 21, S3: 443 }

/** "user@host:port → path" and the like, for the table and the job list. */
export function targetAddress(target: Target, t: TFunction) {
  switch (target.type) {
    case "EMAIL":
      return t("backups.targets.emailAddress", { recipients: target.recipients, mb: target.maxMb ?? 20 })
    case "LOCAL":
      return target.path ?? ""
    case "S3":
      return `${target.host}${target.port ? `:${target.port}` : ""} → ${target.bucket}${target.path ? `/${target.path}` : ""}`
    default:
      return `${target.username}@${target.host}:${target.port || defaultPort[target.type]}${target.path ? ` → ${target.path}` : ""}`
  }
}

export function TargetsCard({ targets, onChanged }: { targets: Target[]; onChanged: () => Promise<void> }) {
  const { t } = useTranslation()
  const errorToast = useErrorToast()
  const test = $api.useMutation("post", "/api/backup/targets/{id}/test")
  const remove = $api.useMutation("delete", "/api/backup/targets/{id}")
  const forgetKey = $api.useMutation("delete", "/api/backup/targets/{id}/host-key")
  const [editing, setEditing] = useState<Target | "new" | null>(null)
  const [deleting, setDeleting] = useState<Target | null>(null)
  const [browsing, setBrowsing] = useState<Target | null>(null)
  const [testing, setTesting] = useState<number | null>(null)

  async function runTest(target: Target) {
    setTesting(target.id)
    try {
      const result = await test.mutateAsync({ params: { path: { id: target.id } } })
      if (result.success) {
        toast.success(t("backups.targets.testOk"), { description: result.message })
      } else {
        toast.error(t("backups.targets.testFailed"), { description: result.message })
      }
      await onChanged()
    } catch (error) {
      errorToast(error)
    } finally {
      setTesting(null)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("backups.targets.title")}</CardTitle>
        <CardDescription>{t("backups.targets.desc")}</CardDescription>
        <CardAction>
          <Button onClick={() => setEditing("new")}>
            <PlusIcon />
            {t("backups.targets.add")}
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent>
        {targets.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("backups.targets.none")}</p>
        ) : (
          <div className="rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("backups.targets.name")}</TableHead>
                  <TableHead>{t("backups.targets.type")}</TableHead>
                  <TableHead>{t("backups.targets.address")}</TableHead>
                  <TableHead>{t("backups.targets.retention")}</TableHead>
                  <TableHead className="w-40" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {targets.map((target) => (
                  <TableRow key={target.id}>
                    <TableCell className="font-medium">{target.name}</TableCell>
                    <TableCell>
                      {target.type === "FTP" && target.tls ? "FTPS" : t(`backups.targets.types.${target.type}`)}
                      {((target.type === "FTP" && !target.tls) || (target.type === "S3" && !target.tls)) && (
                        <div className="text-xs text-amber-600 dark:text-amber-400">{t("backups.targets.unencrypted")}</div>
                      )}
                    </TableCell>
                    <TableCell className="max-w-md font-mono text-xs whitespace-normal">
                      {targetAddress(target, t)}
                      {(target.type === "SFTP" || (target.type === "FTP" && target.tls && target.hostKey)) && (
                        <div className="mt-0.5 font-sans text-muted-foreground">
                          {target.hostKey ? (
                            <>
                              {t(target.type === "SFTP" ? "backups.targets.hostKey" : "backups.targets.certificate", {
                                key: target.hostKey,
                              })}{" "}
                              <button
                                type="button"
                                className="underline underline-offset-2"
                                onClick={() =>
                                  forgetKey
                                    .mutateAsync({ params: { path: { id: target.id } } })
                                    .then(onChanged)
                                    .catch((error: unknown) => errorToast(error))
                                }
                              >
                                {t("backups.targets.forgetKey")}
                              </button>
                            </>
                          ) : (
                            t("backups.targets.hostKeyLearn")
                          )}
                        </div>
                      )}
                    </TableCell>
                    <TableCell className="text-sm">
                      {target.type === "EMAIL"
                        ? "–"
                        : target.retentionDays === 0
                          ? t("backups.targets.forever")
                          : t("backups.targets.days", { count: target.retentionDays })}
                    </TableCell>
                    <TableCell>
                      <div className="flex justify-end gap-1">
                        {target.type !== "EMAIL" && (
                          <Button variant="ghost" size="icon-sm" aria-label={t("backups.files.open")} title={t("backups.files.open")} onClick={() => setBrowsing(target)}>
                            <FolderOpenIcon />
                          </Button>
                        )}
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          aria-label={t("backups.targets.test")}
                          title={t("backups.targets.test")}
                          disabled={testing !== null}
                          onClick={() => void runTest(target)}
                        >
                          {testing === target.id ? <Spinner /> : <PlugZapIcon />}
                        </Button>
                        <Button variant="ghost" size="icon-sm" aria-label={t("common.edit")} onClick={() => setEditing(target)}>
                          <PencilIcon />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          aria-label={t("common.delete")}
                          disabled={target.jobCount > 0}
                          title={target.jobCount > 0 ? t("backups.targets.inUse") : undefined}
                          onClick={() => setDeleting(target)}
                        >
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
      </CardContent>
      {browsing && <TargetFilesDialog target={browsing} onClose={() => setBrowsing(null)} />}
      {editing !== null && (
        <TargetDialog
          target={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            setEditing(null)
            await onChanged()
          }}
        />
      )}
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={t("backups.targets.deleteTitle", { name: deleting?.name })}
        description={t("backups.targets.deleteDesc")}
        onConfirm={() => {
          if (deleting) {
            remove
              .mutateAsync({ params: { path: { id: deleting.id } } })
              .then(onChanged)
              .catch((error: unknown) => errorToast(error))
              .finally(() => setDeleting(null))
          }
        }}
      />
    </Card>
  )
}

function TargetDialog({ target, onClose, onSaved }: { target: Target | null; onClose: () => void; onSaved: () => Promise<void> }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const create = $api.useMutation("post", "/api/backup/targets")
  const update = $api.useMutation("put", "/api/backup/targets/{id}")
  const [type, setType] = useState<TargetType>(target?.type ?? "SFTP")
  const [name, setName] = useState(target?.name ?? "")
  const [host, setHost] = useState(target?.host ?? "")
  const [port, setPort] = useState(String(target ? target.port || defaultPort[target.type] || "" : 22))
  const [username, setUsername] = useState(target?.username ?? "")
  const [password, setPassword] = useState("")
  const [path, setPath] = useState(target?.path ?? "")
  const [tls, setTls] = useState(target?.tls ?? false)
  const [region, setRegion] = useState(target?.region ?? "")
  const [bucket, setBucket] = useState(target?.bucket ?? "")
  const [recipients, setRecipients] = useState(target?.recipients ?? "")
  const [maxMb, setMaxMb] = useState(String(target?.maxMb ?? 20))
  const [retention, setRetention] = useState(String(target ? target.retentionDays : 90))

  function changeType(next: TargetType) {
    // Keep a port the user entered, move a default port along
    if (port === "" || Number(port) === defaultPort[type]) {
      setPort(defaultPort[next] ? String(defaultPort[next]) : "")
    }
    if (next === "S3") {
      setTls(true)
    }
    setType(next)
  }

  // Known S3 providers: the endpoint gives the region, a region chosen in the list gives the endpoint
  const provider = type === "S3" ? (s3ProviderOf(host)?.provider ?? null) : null

  function changeHost(next: string) {
    setHost(next)
    const known = type === "S3" ? s3ProviderOf(next) : null
    if (known?.region) {
      setRegion(known.region)
    }
  }

  function changeRegion(next: string) {
    setRegion(next)
    if (provider) {
      setHost(s3Endpoint(provider, next))
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    const remote = type === "SFTP" || type === "FTP" || type === "S3"
    const body = {
      name,
      type,
      host: remote ? host : null,
      port: remote && port ? Number(port) : null,
      username: remote ? username : null,
      password: password || (target ? null : remote ? "" : null),
      path: type === "EMAIL" ? null : path,
      tls: type === "FTP" || type === "S3" ? tls : false,
      region: type === "S3" ? region : null,
      bucket: type === "S3" ? bucket : null,
      recipients: type === "EMAIL" ? recipients : null,
      maxMb: type === "EMAIL" && maxMb ? Number(maxMb) : null,
      retentionDays: type === "EMAIL" ? 0 : Number(retention || 0),
    }
    try {
      if (target) {
        await update.mutateAsync({ params: { path: { id: target.id } }, body })
      } else {
        await create.mutateAsync({ body })
      }
      toast.success(t("backups.targets.saved"))
      await queryClient.invalidateQueries({ queryKey: ["get", "/api/backup"] })
      await onSaved()
    } catch (error) {
      errorToast(error)
    }
  }

  const saving = create.isPending || update.isPending
  const s3 = type === "S3"

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <form onSubmit={submit} className="space-y-6">
          <DialogHeader>
            <DialogTitle>{target ? t("backups.targets.editTitle", { name: target.name }) : t("backups.targets.add")}</DialogTitle>
            <DialogDescription>{t("backups.targets.dialogDesc")}</DialogDescription>
          </DialogHeader>
          <FieldGroup>
            <Field>
              <FieldLabel htmlFor="target-name">{t("backups.targets.name")}</FieldLabel>
              <Input id="target-name" required maxLength={100} placeholder={t("backups.targets.namePlaceholder")} value={name} onChange={(e) => setName(e.target.value)} />
            </Field>
            <Field>
              <FieldLabel>{t("backups.targets.type")}</FieldLabel>
              <Select value={type} onValueChange={(v) => changeType(v as TargetType)}>
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {types.map((k) => (
                    <SelectItem key={k} value={k}>
                      {t(`backups.targets.typeOptions.${k}`)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            {type === "EMAIL" ? (
              <>
                <Field>
                  <FieldLabel htmlFor="target-recipients">{t("backups.targets.recipients")}</FieldLabel>
                  <Input id="target-recipients" required value={recipients} onChange={(e) => setRecipients(e.target.value)} />
                  <FieldDescription>{t("backups.targets.recipientsHint")}</FieldDescription>
                </Field>
                <Field>
                  <FieldLabel htmlFor="target-maxmb">{t("backups.targets.maxMb")}</FieldLabel>
                  <Input id="target-maxmb" type="number" min={1} max={50} value={maxMb} onChange={(e) => setMaxMb(e.target.value)} />
                </Field>
              </>
            ) : (
              <>
                {type !== "LOCAL" && (
                  <>
                    <div className="grid grid-cols-[1fr_7rem] gap-3">
                      <Field>
                        <FieldLabel htmlFor="target-host">{s3 ? t("backups.targets.endpoint") : t("backups.targets.host")}</FieldLabel>
                        <Input
                          id="target-host"
                          required
                          list={s3 ? "target-s3-endpoints" : undefined}
                          placeholder={s3 ? "s3.eu-central-1.amazonaws.com" : undefined}
                          value={host}
                          onChange={(e) => changeHost(e.target.value)}
                        />
                        {s3 && (
                          <datalist id="target-s3-endpoints">
                            {s3Endpoints.map((endpoint) => (
                              <option key={endpoint.value} value={endpoint.value} label={endpoint.label} />
                            ))}
                          </datalist>
                        )}
                      </Field>
                      <Field>
                        <FieldLabel htmlFor="target-port">{t("backups.targets.port")}</FieldLabel>
                        <Input id="target-port" type="number" min={0} max={65535} value={port} onChange={(e) => setPort(e.target.value)} />
                      </Field>
                    </div>
                    {s3 && (
                      <div className="grid grid-cols-2 gap-3">
                        <Field>
                          <FieldLabel htmlFor="target-bucket">{t("backups.targets.bucket")}</FieldLabel>
                          <Input id="target-bucket" required value={bucket} onChange={(e) => setBucket(e.target.value)} />
                        </Field>
                        <Field>
                          <FieldLabel htmlFor="target-region">{t("backups.targets.region")}</FieldLabel>
                          {provider ? (
                            <Select value={region} onValueChange={changeRegion}>
                              <SelectTrigger id="target-region" className="w-full">
                                <SelectValue placeholder="us-east-1" />
                              </SelectTrigger>
                              <SelectContent>
                                {(provider.regions.some((r) => r.id === region) || !region ? provider.regions : [{ id: region, label: undefined }, ...provider.regions]).map((r) => (
                                  <SelectItem key={r.id} value={r.id}>
                                    {r.label ? `${r.id} · ${r.label}` : r.id}
                                  </SelectItem>
                                ))}
                              </SelectContent>
                            </Select>
                          ) : (
                            <Input id="target-region" placeholder="us-east-1" value={region} onChange={(e) => setRegion(e.target.value)} />
                          )}
                          {provider && <FieldDescription>{provider.name}</FieldDescription>}
                        </Field>
                      </div>
                    )}
                    <Field>
                      <FieldLabel htmlFor="target-user">{s3 ? t("backups.targets.accessKey") : t("backups.targets.user")}</FieldLabel>
                      <Input id="target-user" required autoComplete="off" value={username} onChange={(e) => setUsername(e.target.value)} />
                    </Field>
                    <Field>
                      <FieldLabel htmlFor="target-password">{s3 ? t("backups.targets.secretKey") : t("backups.targets.password")}</FieldLabel>
                      <Input
                        id="target-password"
                        type="password"
                        autoComplete="new-password"
                        placeholder={target?.passwordSet ? t("backups.targets.unchanged") : ""}
                        value={password}
                        onChange={(e) => setPassword(e.target.value)}
                      />
                    </Field>
                    {(type === "FTP" || s3) && (
                      <Field orientation="horizontal">
                        <Checkbox id="target-tls" checked={tls} onCheckedChange={(v) => setTls(v === true)} />
                        <FieldLabel htmlFor="target-tls">{s3 ? t("backups.targets.https") : t("backups.targets.ftps")}</FieldLabel>
                      </Field>
                    )}
                  </>
                )}
                <Field>
                  <FieldLabel htmlFor="target-path">{s3 ? t("backups.targets.prefix") : t("backups.targets.path")}</FieldLabel>
                  <Input id="target-path" required={type === "LOCAL"} className="font-mono" value={path} onChange={(e) => setPath(e.target.value)} />
                  <FieldDescription>{t(`backups.targets.pathHints.${type}`)}</FieldDescription>
                </Field>
                <Field>
                  <FieldLabel htmlFor="target-retention">{t("backups.targets.retentionDays")}</FieldLabel>
                  <Input id="target-retention" type="number" min={0} max={3650} value={retention} onChange={(e) => setRetention(e.target.value)} />
                  <FieldDescription>{t("backups.targets.retentionHint")}</FieldDescription>
                </Field>
              </>
            )}
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
      </DialogContent>
    </Dialog>
  )
}
