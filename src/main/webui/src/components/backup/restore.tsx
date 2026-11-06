import { DownloadIcon, TriangleAlertIcon } from "lucide-react"
import { useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"

import { $api, apiFetch, toApiError } from "@/api/client"
import { ConfirmDialog } from "@/components/confirm-dialog"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Field, FieldDescription, FieldGroup, FieldLabel, FieldLegend, FieldSet } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Spinner } from "@/components/ui/spinner"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { downloadArchive, inspectArchive, restoreArchive, type ArchiveInfo, type ArchiveRestoreReport, type BackupSection } from "@/lib/backup-api"
import { allSections, defaultSections, encryptedOnlySections } from "@/lib/backup-sections"
import { useErrorToast } from "@/lib/errors"
import { formatDateTime } from "@/lib/format"

import type { Target } from "./targets"
import { SectionLabel } from "./section-label"

/** Download of an archive with the chosen parts, right now. */
export function DownloadCard() {
  const { t } = useTranslation()
  const errorToast = useErrorToast()
  const [sections, setSections] = useState<BackupSection[]>(defaultSections)
  const [password, setPassword] = useState("")
  const [busy, setBusy] = useState(false)

  async function download() {
    setBusy(true)
    try {
      await downloadArchive(
        sections.filter((s) => password.length > 0 || !encryptedOnlySections.includes(s)),
        password || null,
      )
    } catch (error) {
      errorToast(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("backups.download.title")}</CardTitle>
        <CardDescription>{t("backups.download.desc")}</CardDescription>
      </CardHeader>
      <CardContent>
        <FieldGroup>
          <SectionChecks value={sections} onChange={setSections} idPrefix="download" encrypted={password.length > 0} />
          <Field>
            <FieldLabel htmlFor="download-password">{t("backups.download.password")}</FieldLabel>
            <Input id="download-password" type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
            <FieldDescription>{t("backups.download.passwordHint")}</FieldDescription>
          </Field>
          <div>
            <Button disabled={busy || sections.length === 0 || (password.length > 0 && password.length < 12)} onClick={() => void download()}>
              {busy ? <Spinner /> : <DownloadIcon />}
              {t("backups.download.button")}
            </Button>
          </div>
        </FieldGroup>
      </CardContent>
    </Card>
  )
}

function SectionChecks({
  value,
  onChange,
  idPrefix,
  encrypted,
}: {
  value: BackupSection[]
  onChange: (v: BackupSection[]) => void
  idPrefix: string
  encrypted: boolean
}) {
  const { t } = useTranslation()
  return (
    <FieldSet>
      <FieldLegend variant="label">{t("backups.jobs.content")}</FieldLegend>
      <div className="grid gap-2 sm:grid-cols-3">
        {allSections.map((s) => {
          const locked = !encrypted && encryptedOnlySections.includes(s)
          return (
            <Field key={s} orientation="horizontal" data-disabled={locked || undefined}>
              <Checkbox
                id={`${idPrefix}-${s}`}
                disabled={locked}
                checked={value.includes(s) && !locked}
                onCheckedChange={(v) => onChange(v === true ? [...value, s] : value.filter((x) => x !== s))}
              />
              <FieldLabel htmlFor={`${idPrefix}-${s}`} className="font-normal">
                <SectionLabel section={s}>
                  {locked && <span className="text-muted-foreground">({t("backups.sections.encryptedOnly")})</span>}
                </SectionLabel>
              </FieldLabel>
            </Field>
          )
        })}
      </div>
    </FieldSet>
  )
}

type Source = "upload" | "target"

/** Opens a backup from a file or a target, shows its parts and restores the chosen ones. */
export function RestoreCard({ targets }: { targets: Target[] }) {
  const { t, i18n } = useTranslation()
  const errorToast = useErrorToast()
  const stored = targets.filter((target) => target.type !== "EMAIL")
  const [source, setSource] = useState<Source>("upload")
  const [file, setFile] = useState<File | null>(null)
  const [targetId, setTargetId] = useState<number | null>(null)
  const [fileName, setFileName] = useState<string | null>(null)
  const [password, setPassword] = useState("")
  const [info, setInfo] = useState<ArchiveInfo | null>(null)
  const [chosen, setChosen] = useState<BackupSection[]>([])
  const [trust, setTrust] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [report, setReport] = useState<ArchiveRestoreReport | null>(null)
  const files = $api.useQuery("get", "/api/backup/targets/{id}/files", { params: { path: { id: targetId ?? 0 } } }, { enabled: targetId !== null, retry: false })

  const ownFiles = files.data?.filter((item) => item.own)

  function forget() {
    setInfo(null)
    setReport(null)
    setTrust(false)
  }

  async function post<T>(url: string, body: unknown) {
    const response = await apiFetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) })
    if (!response.ok) {
      throw await toApiError(response)
    }
    return (await response.json()) as T
  }

  const targetFile = () => `/api/backup/targets/${targetId}/files/${encodeURIComponent(fileName ?? "")}`

  async function open() {
    setBusy(true)
    forget()
    try {
      const opened = source === "upload" && file ? await inspectArchive(file, password) : await post<ArchiveInfo>(`${targetFile()}/inspect`, { password })
      setInfo(opened)
      setChosen(opened.sections.map((section) => section.section))
    } catch (error) {
      errorToast(error)
    } finally {
      setBusy(false)
    }
  }

  async function restore() {
    setConfirming(false)
    setBusy(true)
    try {
      const result = source === "upload" && file ? await restoreArchive(file, password, chosen, trust) : await post<ArchiveRestoreReport>(`${targetFile()}/restore`, { password, sections: chosen, trustUnsigned: trust })
      setReport(result)
      if (!result.error) {
        toast.success(t("backups.restore.done"))
      }
    } catch (error) {
      errorToast(error)
    } finally {
      setBusy(false)
    }
  }

  const ready = source === "upload" ? file !== null : fileName !== null
  const signed = info?.signature === "VALID"

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("backups.restore.title")}</CardTitle>
        <CardDescription>{t("backups.restore.desc")}</CardDescription>
      </CardHeader>
      <CardContent>
        <FieldGroup>
          <Tabs
            value={source}
            onValueChange={(value) => {
              setSource(value as Source)
              forget()
            }}
          >
            <TabsList>
              <TabsTrigger value="upload">{t("backups.restore.fromUpload")}</TabsTrigger>
              <TabsTrigger value="target" disabled={stored.length === 0}>
                {t("backups.restore.fromTarget")}
              </TabsTrigger>
            </TabsList>
          </Tabs>
          {source === "upload" ? (
            <Field>
              <FieldLabel htmlFor="restore-file">{t("backups.restore.file")}</FieldLabel>
              <Input
                id="restore-file"
                type="file"
                onChange={(e) => {
                  setFile(e.target.files?.[0] ?? null)
                  forget()
                }}
              />
              <FieldDescription>{t("backups.restore.fileHint")}</FieldDescription>
            </Field>
          ) : (
            <div className="grid gap-4 sm:grid-cols-2">
              <Field>
                <FieldLabel>{t("backups.jobs.target")}</FieldLabel>
                <Select
                  value={targetId === null ? "" : String(targetId)}
                  onValueChange={(value) => {
                    setTargetId(Number(value))
                    setFileName(null)
                    forget()
                  }}
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {stored.map((target) => (
                      <SelectItem key={target.id} value={String(target.id)}>
                        {target.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </Field>
              <Field>
                <FieldLabel>{t("backups.restore.backupFile")}</FieldLabel>
                <Select
                  value={fileName ?? ""}
                  disabled={!ownFiles || ownFiles.length === 0}
                  onValueChange={(value) => {
                    setFileName(value)
                    forget()
                  }}
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {ownFiles?.map(({ name }) => (
                      <SelectItem key={name} value={name}>
                        {name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {files.isError && <FieldDescription>{t("backups.restore.filesFailed")}</FieldDescription>}
                {ownFiles?.length === 0 && <FieldDescription>{t("backups.restore.noFiles")}</FieldDescription>}
              </Field>
            </div>
          )}
          <Field>
            <FieldLabel htmlFor="restore-password">{t("backups.restore.password")}</FieldLabel>
            <Input id="restore-password" type="password" autoComplete="off" value={password} onChange={(e) => setPassword(e.target.value)} />
            <FieldDescription>{t("backups.restore.passwordHint")}</FieldDescription>
          </Field>
          <div>
            <Button variant="outline" disabled={busy || !ready} onClick={() => void open()}>
              {busy && !info && <Spinner />}
              {t("backups.restore.inspect")}
            </Button>
          </div>
          {info && (
            <>
              <p className="text-sm">{t("backups.restore.contains", { date: formatDateTime(info.createdAt, i18n.language), by: info.createdBy ?? "?" })}</p>
              {signed ? (
                <p className="text-sm text-muted-foreground">{t("backups.restore.signed")}</p>
              ) : (
                <Alert variant="destructive">
                  <TriangleAlertIcon />
                  <AlertTitle>{t("backups.restore.unsignedTitle")}</AlertTitle>
                  <AlertDescription>
                    <p>{t(info.signature === "INVALID" ? "backups.restore.signatureInvalid" : "backups.restore.signatureMissing")}</p>
                    <Field orientation="horizontal" className="mt-2">
                      <Checkbox id="restore-trust" checked={trust} onCheckedChange={(value) => setTrust(value === true)} />
                      <FieldLabel htmlFor="restore-trust" className="font-normal">
                        {t("backups.restore.trustUnsigned")}
                      </FieldLabel>
                    </Field>
                  </AlertDescription>
                </Alert>
              )}
              <FieldSet>
                <FieldLegend variant="label">{t("backups.restore.parts")}</FieldLegend>
                <div className="grid gap-2 sm:grid-cols-2">
                  {info.sections.map(({ section, count }) => (
                    <Field key={section} orientation="horizontal">
                      <Checkbox
                        id={`restore-${section}`}
                        checked={chosen.includes(section)}
                        onCheckedChange={(value) => setChosen(value === true ? [...chosen, section] : chosen.filter((item) => item !== section))}
                      />
                      <FieldLabel htmlFor={`restore-${section}`} className="font-normal">
                        <SectionLabel section={section}>
                          {count !== null && count !== undefined && <span className="text-muted-foreground">({count})</span>}
                        </SectionLabel>
                      </FieldLabel>
                    </Field>
                  ))}
                </div>
                <FieldDescription>{t("backups.restore.replaceHint")}</FieldDescription>
              </FieldSet>
              <div>
                <Button variant="destructive" disabled={busy || chosen.length === 0 || (!signed && !trust)} onClick={() => setConfirming(true)}>
                  {busy && <Spinner />}
                  {t("backups.restore.apply")}
                </Button>
              </div>
            </>
          )}
          {report?.error && (
            <Alert variant="destructive">
              <TriangleAlertIcon />
              <AlertTitle>{t("backups.restore.failed")}</AlertTitle>
              <AlertDescription className="break-words">{report.error}</AlertDescription>
            </Alert>
          )}
          {report && report.restored.length > 0 && (
            <p className="text-sm">{t("backups.restore.report", { parts: report.restored.map((section) => t(`backups.sections.${section}`)).join(", ") })}</p>
          )}
        </FieldGroup>
      </CardContent>
      <ConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title={t("backups.restore.confirmTitle")}
        description={t("backups.restore.confirmDesc")}
        confirmLabel={t("backups.restore.apply")}
        onConfirm={() => void restore()}
      />
    </Card>
  )
}
