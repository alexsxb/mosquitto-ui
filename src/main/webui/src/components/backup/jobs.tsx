import { useQueryClient } from "@tanstack/react-query"
import { CopyIcon, PencilIcon, PlayIcon, PlusIcon, SquareIcon, Trash2Icon, XIcon } from "lucide-react"
import { useState, type FormEvent } from "react"
import type { TFunction } from "i18next"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"

import { $api, apiFetch, toApiError, type Schemas } from "@/api/client"
import { ConfirmDialog } from "@/components/confirm-dialog"
import { TablePagination } from "@/components/table-pagination"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Field, FieldDescription, FieldGroup, FieldLabel, FieldLegend, FieldSet } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Spinner } from "@/components/ui/spinner"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Textarea } from "@/components/ui/textarea"
import { useErrorToast } from "@/lib/errors"
import { formatDateTime } from "@/lib/format"
import { usePageSize } from "@/lib/pagination"

import { allSections, defaultSections, encryptedOnlySections } from "@/lib/backup-sections"
import { SectionLabel } from "./section-label"

import type { Target } from "./targets"

export type Job = Schemas["JobView"]
export type Run = Schemas["RunView"]
type Section = Schemas["BackupSection"]
type Schedule = Job["schedule"]
type Encryption = Job["encryption"]
type GpgKey = Schemas["GpgKeyView"]


export function formatBytes(bytes: number | null | undefined, locale: string) {
  if (bytes == null) {
    return "–"
  }
  const format = new Intl.NumberFormat(locale, { maximumFractionDigits: 1 })
  return bytes < 1024 * 1024 ? `${format.format(bytes / 1024)} kB` : `${format.format(bytes / 1024 / 1024)} MB`
}

function duration(run: Run) {
  if (!run.finishedAt) {
    return "–"
  }
  const seconds = Math.round((new Date(run.finishedAt).getTime() - new Date(run.startedAt).getTime()) / 1000)
  return seconds < 60 ? `${seconds} s` : `${Math.floor(seconds / 60)} min ${seconds % 60} s`
}

/** Status of a run: running, fine, cancelled or failed. */
export function RunStatus({ run }: { run: Run }) {
  const { t } = useTranslation()
  if (run.success == null) {
    return (
      <Badge variant="secondary">
        <Spinner />
        {t("backups.runs.running")}
      </Badge>
    )
  }
  if (run.success) {
    return <Badge className="bg-emerald-600 text-white hover:bg-emerald-600">{t("backups.runs.ok")}</Badge>
  }
  if (run.message?.startsWith("aborted")) {
    return <Badge className="bg-amber-500 text-white hover:bg-amber-500">{t("backups.runs.cancelled")}</Badge>
  }
  return <Badge variant="destructive">{t("backups.runs.failed")}</Badge>
}

const weekdays = [1, 2, 3, 4, 5, 6, 7] as const

export function scheduleText(job: Pick<Job, "schedule" | "time" | "weekday" | "monthDay">, t: TFunction) {
  switch (job.schedule) {
    case "DAILY":
      return t("backups.schedule.dailyText", { time: job.time })
    case "WEEKLY":
      return t("backups.schedule.weeklyText", { day: t(`backups.schedule.weekdays.${(job.weekday ?? 1) as (typeof weekdays)[number]}`), time: job.time })
    case "MONTHLY":
      return t("backups.schedule.monthlyText", { day: job.monthDay, time: job.time })
    default:
      return t("backups.schedule.manualText")
  }
}

export function JobsCard({
  jobs,
  targets,
  serverTime,
  timeZone,
  onChanged,
  onShowRun,
}: {
  jobs: Job[]
  targets: Target[]
  serverTime: string
  timeZone: string
  onChanged: () => Promise<void>
  onShowRun: (runId: number) => void
}) {
  const { t, i18n } = useTranslation()
  const errorToast = useErrorToast()
  const start = $api.useMutation("post", "/api/backup/jobs/{id}/start")
  const cancel = $api.useMutation("post", "/api/backup/jobs/{id}/cancel")
  const copy = $api.useMutation("post", "/api/backup/jobs/{id}/copy")
  const remove = $api.useMutation("delete", "/api/backup/jobs/{id}")
  const [editing, setEditing] = useState<Job | "new" | null>(null)
  const [deleting, setDeleting] = useState<Job | null>(null)

  async function run(job: Job) {
    try {
      const result = await start.mutateAsync({ params: { path: { id: job.id } } })
      toast.success(t("backups.jobs.started", { name: job.name }))
      await onChanged()
      onShowRun(result.runId)
    } catch (error) {
      errorToast(error)
    }
  }

  async function duplicate(job: Job) {
    try {
      const created = await copy.mutateAsync({ params: { path: { id: job.id } } })
      toast.success(t("backups.jobs.copied"))
      await onChanged()
      setEditing(created)
    } catch (error) {
      errorToast(error)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("backups.jobs.title")}</CardTitle>
        <CardDescription>
          {t("backups.jobs.serverTime", { zone: timeZone, time: serverTime.slice(11, 16) })}
        </CardDescription>
        <CardAction>
          <Button onClick={() => setEditing("new")} disabled={targets.length === 0} title={targets.length === 0 ? t("backups.jobs.needTarget") : undefined}>
            <PlusIcon />
            {t("backups.jobs.add")}
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent>
        {jobs.length === 0 ? (
          <p className="text-sm text-muted-foreground">{targets.length === 0 ? t("backups.jobs.needTarget") : t("backups.jobs.none")}</p>
        ) : (
          <div className="rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("backups.jobs.name")}</TableHead>
                  <TableHead>{t("backups.jobs.content")}</TableHead>
                  <TableHead>{t("backups.jobs.target")}</TableHead>
                  <TableHead>{t("backups.jobs.schedule")}</TableHead>
                  <TableHead>{t("backups.jobs.lastRun")}</TableHead>
                  <TableHead className="w-44" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {jobs.map((job) => (
                  <TableRow key={job.id}>
                    <TableCell>
                      <div className="font-medium">{job.name}</div>
                      <div className="text-xs text-muted-foreground">
                        {job.encryption === "GPG"
                          ? t("backups.jobs.gpgFor", { count: job.gpgRecipients.length })
                          : job.encryption === "PASSWORD"
                            ? t("backups.jobs.passwordEncrypted")
                            : t("backups.jobs.unencrypted")}
                      </div>
                    </TableCell>
                    <TableCell className="max-w-xs whitespace-normal">
                      <div className="flex flex-wrap gap-1">
                        {job.sections.map((s) => (
                          <Badge key={s} variant="outline" className="font-normal">
                            {t(`backups.sections.${s}`)}
                          </Badge>
                        ))}
                      </div>
                    </TableCell>
                    <TableCell>{job.targetName}</TableCell>
                    <TableCell className={job.enabled ? "" : "text-muted-foreground line-through"}>{scheduleText(job, t)}</TableCell>
                    <TableCell className="max-w-xs whitespace-normal">
                      {job.lastRun ? (
                        <button type="button" className="space-y-0.5 text-left" onClick={() => onShowRun(job.lastRun!.id)}>
                          <RunStatus run={job.lastRun} />
                          <div className="text-xs text-muted-foreground">
                            {formatDateTime(job.lastRun.startedAt, i18n.language)}
                            {job.lastRun.message && <> · {job.lastRun.message}</>}
                          </div>
                        </button>
                      ) : (
                        <span className="text-xs text-muted-foreground">{t("backups.jobs.neverRun")}</span>
                      )}
                    </TableCell>
                    <TableCell>
                      <div className="flex justify-end gap-1">
                        {job.running ? (
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            title={t("backups.jobs.cancel")}
                            aria-label={t("backups.jobs.cancel")}
                            onClick={() =>
                              cancel
                                .mutateAsync({ params: { path: { id: job.id } } })
                                .then(onChanged)
                                .catch((error: unknown) => errorToast(error))
                            }
                          >
                            <SquareIcon />
                          </Button>
                        ) : (
                          <Button variant="ghost" size="icon-sm" title={t("backups.jobs.runNow")} aria-label={t("backups.jobs.runNow")} onClick={() => void run(job)}>
                            <PlayIcon />
                          </Button>
                        )}
                        <Button variant="ghost" size="icon-sm" aria-label={t("common.edit")} onClick={() => setEditing(job)}>
                          <PencilIcon />
                        </Button>
                        <Button variant="ghost" size="icon-sm" title={t("backups.jobs.copy")} aria-label={t("backups.jobs.copy")} onClick={() => void duplicate(job)}>
                          <CopyIcon />
                        </Button>
                        <Button variant="ghost" size="icon-sm" aria-label={t("common.delete")} disabled={job.running} onClick={() => setDeleting(job)}>
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
      {editing !== null && (
        <JobDialog
          key={editing === "new" ? "new" : editing.id}
          job={editing === "new" ? null : editing}
          targets={targets}
          timeZone={timeZone}
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
        title={t("backups.jobs.deleteTitle", { name: deleting?.name })}
        description={t("backups.jobs.deleteDesc")}
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

/** A recipient in the job dialog: stored (by fingerprint) or new (armored key). */
interface Recipient {
  fingerprint: string
  name: string
  problem: string | null
  armored: string | null
  detail: string | null
}

function JobDialog({
  job,
  targets,
  timeZone,
  onClose,
  onSaved,
}: {
  job: Job | null
  targets: Target[]
  timeZone: string
  onClose: () => void
  onSaved: () => Promise<void>
}) {
  const { t, i18n } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const create = $api.useMutation("post", "/api/backup/jobs")
  const update = $api.useMutation("put", "/api/backup/jobs/{id}")
  const [name, setName] = useState(job?.name ?? "")
  const [enabled, setEnabled] = useState(job?.enabled ?? true)
  const [sections, setSections] = useState<Section[]>(job?.sections ?? defaultSections)
  const [targetId, setTargetId] = useState(String(job?.targetId ?? targets[0]?.id ?? ""))
  const [schedule, setSchedule] = useState<Schedule>(job?.schedule ?? "DAILY")
  const [time, setTime] = useState(job?.time ?? "01:15")
  const [weekday, setWeekday] = useState(String(job?.weekday ?? 7))
  const [monthDay, setMonthDay] = useState(String(job?.monthDay ?? 1))
  const [encryption, setEncryption] = useState<Encryption>(job?.encryption ?? "PASSWORD")
  const [password, setPassword] = useState("")
  const [recipients, setRecipients] = useState<Recipient[]>(
    (job?.gpgRecipients ?? []).map((r) => ({ ...r, armored: null, detail: null })),
  )

  const passwordMissing = encryption === "PASSWORD" && !password && !job?.passwordSet
  const gpgMissing = encryption === "GPG" && recipients.length === 0

  async function submit(event: FormEvent) {
    event.preventDefault()
    const body = {
      name,
      enabled,
      sections: sections.filter((s) => encryption !== "NONE" || !encryptedOnlySections.includes(s)),
      targetId: Number(targetId),
      schedule,
      time: schedule === "MANUAL" ? null : time,
      weekday: schedule === "WEEKLY" ? Number(weekday) : null,
      monthDay: schedule === "MONTHLY" ? Number(monthDay) : null,
      encryption,
      password: encryption === "PASSWORD" ? password || null : "",
      gpgRecipients: encryption === "GPG" ? recipients.map((r) => r.armored ?? r.fingerprint) : [],
    }
    try {
      if (job) {
        await update.mutateAsync({ params: { path: { id: job.id } }, body })
      } else {
        await create.mutateAsync({ body })
      }
      toast.success(t("backups.jobs.saved"))
      await queryClient.invalidateQueries({ queryKey: ["get", "/api/backup"] })
      await onSaved()
    } catch (error) {
      errorToast(error)
    }
  }

  const saving = create.isPending || update.isPending

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-2xl">
        <form onSubmit={submit} className="space-y-6">
          <DialogHeader>
            <DialogTitle>{job ? t("backups.jobs.editTitle", { name: job.name }) : t("backups.jobs.addTitle")}</DialogTitle>
            <DialogDescription>{t("backups.jobs.dialogDesc")}</DialogDescription>
          </DialogHeader>
          <FieldGroup>
            <div className="grid gap-3 sm:grid-cols-[1fr_auto] sm:items-end">
              <Field>
                <FieldLabel htmlFor="job-name">{t("backups.jobs.name")}</FieldLabel>
                <Input id="job-name" required maxLength={100} placeholder={t("backups.jobs.namePlaceholder")} value={name} onChange={(e) => setName(e.target.value)} />
              </Field>
              <Field orientation="horizontal" className="pb-2">
                <Checkbox id="job-enabled" checked={enabled} onCheckedChange={(v) => setEnabled(v === true)} />
                <FieldLabel htmlFor="job-enabled">{t("backups.jobs.enabled")}</FieldLabel>
              </Field>
            </div>
            <FieldSet>
              <FieldLegend variant="label">{t("backups.jobs.content")}</FieldLegend>
              <div className="grid gap-2 sm:grid-cols-2">
                {allSections.map((s) => {
                  const locked = encryption === "NONE" && encryptedOnlySections.includes(s)
                  return (
                    <Field key={s} orientation="horizontal" data-disabled={locked || undefined}>
                      <Checkbox
                        id={`job-section-${s}`}
                        disabled={locked}
                        checked={sections.includes(s) && !locked}
                        onCheckedChange={(v) => setSections(v === true ? [...sections, s] : sections.filter((x) => x !== s))}
                      />
                      <FieldLabel htmlFor={`job-section-${s}`} className="font-normal">
                        <SectionLabel section={s}>
                          {locked && <span className="text-muted-foreground">({t("backups.sections.encryptedOnly")})</span>}
                        </SectionLabel>
                      </FieldLabel>
                    </Field>
                  )
                })}
              </div>
              <FieldDescription>{t("backups.jobs.secretsHint")}</FieldDescription>
            </FieldSet>
            <Field>
              <FieldLabel>{t("backups.jobs.target")}</FieldLabel>
              <Select value={targetId} onValueChange={setTargetId}>
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {targets.map((target) => (
                    <SelectItem key={target.id} value={String(target.id)}>
                      {target.name} ({t(`backups.targets.types.${target.type}`)})
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <div className="grid gap-3 sm:grid-cols-3">
              <Field>
                <FieldLabel>{t("backups.jobs.schedule")}</FieldLabel>
                <Select value={schedule} onValueChange={(v) => setSchedule(v as Schedule)}>
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {(["MANUAL", "DAILY", "WEEKLY", "MONTHLY"] as Schedule[]).map((s) => (
                      <SelectItem key={s} value={s}>
                        {t(`backups.schedule.${s}`)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </Field>
              {schedule === "WEEKLY" && (
                <Field>
                  <FieldLabel>{t("backups.schedule.weekday")}</FieldLabel>
                  <Select value={weekday} onValueChange={setWeekday}>
                    <SelectTrigger className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {weekdays.map((d) => (
                        <SelectItem key={d} value={String(d)}>
                          {t(`backups.schedule.weekdays.${d}`)}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </Field>
              )}
              {schedule === "MONTHLY" && (
                <Field>
                  <FieldLabel>{t("backups.schedule.monthDay")}</FieldLabel>
                  <Select value={monthDay} onValueChange={setMonthDay}>
                    <SelectTrigger className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {Array.from({ length: 28 }, (_, i) => i + 1).map((d) => (
                        <SelectItem key={d} value={String(d)}>
                          {d}.
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </Field>
              )}
              {schedule !== "MANUAL" && (
                <Field>
                  <FieldLabel htmlFor="job-time">{t("backups.schedule.time")}</FieldLabel>
                  <Input id="job-time" type="time" required value={time} onChange={(e) => setTime(e.target.value)} />
                  <FieldDescription>{t("backups.schedule.timeHint", { zone: timeZone })}</FieldDescription>
                </Field>
              )}
            </div>
            <Field>
              <FieldLabel>{t("backups.jobs.encryption")}</FieldLabel>
              <Select value={encryption} onValueChange={(v) => setEncryption(v as Encryption)}>
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {(["NONE", "PASSWORD", "GPG"] as Encryption[]).map((e) => (
                    <SelectItem key={e} value={e}>
                      {t(`backups.encryption.${e}`)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {encryption === "NONE" && <FieldDescription className="text-amber-600 dark:text-amber-400">{t("backups.encryption.noneHint")}</FieldDescription>}
            </Field>
            {encryption === "PASSWORD" && (
              <Field>
                <FieldLabel htmlFor="job-password">{t("backups.jobs.password")}</FieldLabel>
                <Input
                  id="job-password"
                  type="password"
                  autoComplete="new-password"
                  minLength={12}
                  placeholder={job?.passwordSet ? t("backups.targets.unchanged") : ""}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
                <FieldDescription>{t("backups.encryption.passwordHint")}</FieldDescription>
              </Field>
            )}
            {encryption === "GPG" && <GpgRecipients recipients={recipients} onChange={setRecipients} locale={i18n.language} />}
          </FieldGroup>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={saving || passwordMissing || gpgMissing || sections.length === 0}>
              {saving && <Spinner />}
              {t("common.save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function fingerprintGroups(fingerprint: string) {
  return fingerprint.toUpperCase().replace(/(.{4})/g, "$1 ").trim()
}

/** GPG recipients: search on the keyserver (e-mail, fingerprint, key id) or paste a public key. */
function GpgRecipients({ recipients, onChange, locale }: { recipients: Recipient[]; onChange: (r: Recipient[]) => void; locale: string }) {
  const { t } = useTranslation()
  const errorToast = useErrorToast()
  const [query, setQuery] = useState("")
  const [pasting, setPasting] = useState(false)
  const [armored, setArmored] = useState("")
  const [busy, setBusy] = useState(false)

  function add(key: GpgKey) {
    if (recipients.some((r) => r.fingerprint.toUpperCase() === key.fingerprint.toUpperCase())) {
      toast.info(t("backups.gpg.already"))
      return
    }
    const date = (v: string | null) => (v ? new Date(v).toLocaleDateString(locale) : null)
    onChange([
      ...recipients,
      {
        fingerprint: key.fingerprint,
        name: key.names.join(", "),
        problem: key.problem,
        armored: key.armored,
        detail: t("backups.gpg.newKey", {
          algorithm: key.algorithm,
          created: date(key.createdAt),
          until: key.validUntil ? t("backups.gpg.until", { date: date(key.validUntil) }) : "",
        }),
      },
    ])
  }

  async function search() {
    setBusy(true)
    try {
      const response = await apiFetch(`/api/backup/gpg/search?q=${encodeURIComponent(query.trim())}`)
      if (response.status === 404) {
        toast.error(t("backups.gpg.notFound"))
        return
      }
      if (!response.ok) {
        throw await toApiError(response)
      }
      add((await response.json()) as GpgKey)
      setQuery("")
    } catch (error) {
      errorToast(error)
    } finally {
      setBusy(false)
    }
  }

  async function paste() {
    setBusy(true)
    try {
      const response = await apiFetch("/api/backup/gpg/key", {
        method: "POST",
        headers: { "Content-Type": "text/plain" },
        body: armored,
      })
      if (!response.ok) {
        throw await toApiError(response)
      }
      add((await response.json()) as GpgKey)
      setArmored("")
      setPasting(false)
    } catch (error) {
      errorToast(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <FieldSet>
      <FieldLegend variant="label">{t("backups.gpg.recipients")}</FieldLegend>
      {recipients.length > 0 && (
        <ul className="divide-y rounded-lg border">
          {recipients.map((r) => (
            <li key={r.fingerprint} className="flex items-start gap-2 p-2 text-sm">
              <div className="min-w-0 flex-1">
                <div className="font-medium">{r.name}</div>
                <div className="font-mono text-xs text-muted-foreground">{fingerprintGroups(r.fingerprint)}</div>
                {r.detail && <div className="text-xs text-muted-foreground">{r.detail}</div>}
                {r.problem && <div className="text-xs text-destructive">{r.problem}</div>}
              </div>
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label={t("common.delete")}
                onClick={() => onChange(recipients.filter((x) => x.fingerprint !== r.fingerprint))}
              >
                <XIcon />
              </Button>
            </li>
          ))}
        </ul>
      )}
      <div className="flex gap-2">
        <Input placeholder={t("backups.gpg.searchPlaceholder")} value={query} onChange={(e) => setQuery(e.target.value)} />
        <Button type="button" variant="outline" disabled={busy || !query.trim()} onClick={() => void search()}>
          {busy && <Spinner />}
          {t("backups.gpg.search")}
        </Button>
      </div>
      {pasting ? (
        <div className="space-y-2">
          <Textarea rows={6} className="max-h-48 font-mono text-xs [field-sizing:fixed]" placeholder="-----BEGIN PGP PUBLIC KEY BLOCK-----" value={armored} onChange={(e) => setArmored(e.target.value)} />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => setPasting(false)}>
              {t("common.cancel")}
            </Button>
            <Button type="button" variant="outline" disabled={busy || !armored.trim()} onClick={() => void paste()}>
              {t("backups.gpg.addPasted")}
            </Button>
          </div>
        </div>
      ) : (
        <button type="button" className="self-start text-sm underline underline-offset-2" onClick={() => setPasting(true)}>
          {t("backups.gpg.paste")}
        </button>
      )}
      <FieldDescription>{t("backups.gpg.hint")}</FieldDescription>
    </FieldSet>
  )
}

/** Runs of all jobs, newest first, with retention of the log. */
export function RunsCard({ onShowRun }: { onShowRun: (runId: number) => void }) {
  const { t, i18n } = useTranslation()
  const [page, setPage] = useState(0)
  const [size, setSize] = usePageSize("backupRuns", 25)
  const runs = $api.useQuery(
    "get",
    "/api/backup/runs",
    { params: { query: { limit: size, offset: page * size } } },
    { refetchInterval: (q) => (q.state.data?.items.some((r) => r.success == null) ? 3000 : false) },
  )

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("backups.runs.title")}</CardTitle>
        <CardDescription>{t("backups.runs.desc")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {!runs.data?.items.length ? (
          <p className="text-sm text-muted-foreground">{t("backups.runs.none")}</p>
        ) : (
          <div className="rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("backups.runs.started")}</TableHead>
                  <TableHead>{t("backups.runs.job")}</TableHead>
                  <TableHead className="hidden md:table-cell">{t("backups.runs.triggeredBy")}</TableHead>
                  <TableHead className="hidden md:table-cell">{t("backups.runs.duration")}</TableHead>
                  <TableHead className="hidden md:table-cell">{t("backups.runs.size")}</TableHead>
                  <TableHead>{t("backups.runs.result")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {runs.data.items.map((run) => (
                  <TableRow key={run.id} className="cursor-pointer" onClick={() => onShowRun(run.id)}>
                    <TableCell className="text-sm">{formatDateTime(run.startedAt, i18n.language)}</TableCell>
                    <TableCell>{run.jobName}</TableCell>
                    <TableCell className="hidden text-sm md:table-cell">{run.triggeredBy === "schedule" ? t("backups.runs.bySchedule") : run.triggeredBy}</TableCell>
                    <TableCell className="hidden text-sm md:table-cell">{duration(run)}</TableCell>
                    <TableCell className="hidden text-sm md:table-cell">{formatBytes(run.bytes, i18n.language)}</TableCell>
                    <TableCell className="max-w-sm whitespace-normal">
                      <RunStatus run={run} />
                      {run.message && <div className="text-xs text-muted-foreground">{run.message}</div>}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
        <TablePagination page={page} pageSize={size} total={runs.data?.total ?? 0} onPage={setPage} onPageSize={setSize} />
      </CardContent>
    </Card>
  )
}

/** Log of a run, refreshed while it runs. */
export function RunLogDialog({ runId, onClose, onCancel }: { runId: number; onClose: () => void; onCancel: (jobId: number) => void }) {
  const { t, i18n } = useTranslation()
  const run = $api.useQuery(
    "get",
    "/api/backup/runs/{id}",
    { params: { path: { id: runId } } },
    { refetchInterval: (q) => (q.state.data && q.state.data.success == null ? 1500 : false) },
  )
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            {t("backups.runs.logTitle", { job: run.data?.jobName ?? "" })}
            {run.data && <RunStatus run={run.data} />}
          </DialogTitle>
          <DialogDescription>
            {run.data &&
              t("backups.runs.logDesc", {
                started: formatDateTime(run.data.startedAt, i18n.language),
                by: run.data.triggeredBy === "schedule" ? t("backups.runs.bySchedule") : run.data.triggeredBy,
                duration: duration(run.data),
                size: formatBytes(run.data.bytes, i18n.language),
              })}
          </DialogDescription>
        </DialogHeader>
        <pre className="max-h-[60svh] overflow-auto rounded-md bg-muted p-3 font-mono text-xs whitespace-pre-wrap">{run.data?.log || "…"}</pre>
        {run.data && run.data.success == null && (
          <DialogFooter>
            <Button variant="destructive" onClick={() => onCancel(run.data!.jobId)}>
              {t("backups.jobs.cancel")}
            </Button>
          </DialogFooter>
        )}
      </DialogContent>
    </Dialog>
  )
}
