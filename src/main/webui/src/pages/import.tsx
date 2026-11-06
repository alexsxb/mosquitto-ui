import { useQueryClient } from "@tanstack/react-query"
import { TriangleAlertIcon } from "lucide-react"
import { useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"

import { $api, type Schemas } from "@/api/client"
import { ConfirmDialog } from "@/components/confirm-dialog"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Field, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Spinner } from "@/components/ui/spinner"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { useErrorToast } from "@/lib/errors"

type Analysis = Schemas["ImportAnalysis"]
type Upload = Schemas["ImportFile"]
type Kind = "PASSWORD" | "ACL" | "DB" | "CONF" | "UNKNOWN"
type ClientStatus = "NEW" | "EXISTS" | "UNSUPPORTED"
type BridgeStatus = "NEW" | "EXISTS" | "INVALID" | "LOOP"
type SkipReason = "already_set" | "auth" | "listener_exists" | "listener_in_use" | "orphan" | "persistence" | "plugin" | "system"
type BridgeWarning = "echo" | "overlap"

/** Rows of the preview, the file itself can be much longer. */
const MAX_ROWS = 50
/** String.fromCharCode takes its arguments on the stack. */
const CHUNK = 0x8000

async function toUpload(file: File): Promise<Upload> {
  const bytes = new Uint8Array(await file.arrayBuffer())
  let binary = ""
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK))
  }
  return { name: file.name, content: btoa(binary) }
}

export function ImportPage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const analyze = $api.useMutation("post", "/api/import/analyze")
  const run = $api.useMutation("post", "/api/import")

  const [uploads, setUploads] = useState<Upload[]>([])
  const [analysis, setAnalysis] = useState<Analysis | null>(null)
  const [auth, setAuth] = useState(true)
  const [retained, setRetained] = useState(true)
  const [conf, setConf] = useState(true)
  const [confirming, setConfirming] = useState(false)
  const [outcome, setOutcome] = useState<Schemas["ImportOutcome"] | null>(null)

  async function choose(files: FileList | null) {
    setAnalysis(null)
    setOutcome(null)
    setUploads(await Promise.all(Array.from(files ?? []).map(toUpload)))
  }

  async function check() {
    try {
      setOutcome(null)
      setAnalysis(await analyze.mutateAsync({ body: { files: uploads } }))
    } catch (error) {
      setAnalysis(null)
      errorToast(error)
    }
  }

  async function runImport() {
    setConfirming(false)
    try {
      const result = await run.mutateAsync({ body: { files: uploads, auth, retained, conf } })
      setOutcome(result)
      setAnalysis(null)
      if (!result.error) {
        toast.success(t("import.title"))
      }
      await queryClient.invalidateQueries()
    } catch (error) {
      errorToast(error)
    }
  }

  const authReady = (analysis?.auth?.clients.filter((client) => client.status === "NEW").length ?? 0) > 0
  const retainedReady = (analysis?.retained?.messages.length ?? 0) > 0
  const confReady = (analysis?.conf?.bridges.some((bridge) => bridge.status === "NEW") ?? false) || (analysis?.conf?.options.length ?? 0) > 0
  const anything = (auth && authReady) || (retained && retainedReady) || (conf && confReady)
  const restarts = (auth && authReady) || (conf && (analysis?.conf?.restartRequired ?? false))

  return (
    <div className="max-w-4xl space-y-6">
      <Card>
        <CardHeader>
          <CardTitle>{t("import.title")}</CardTitle>
          <CardDescription>{t("import.desc")}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <Field className="max-w-md">
            <FieldLabel htmlFor="import-files">{t("import.choose")}</FieldLabel>
            <Input id="import-files" type="file" multiple onChange={(e) => void choose(e.target.files)} />
          </Field>
          <Button variant="outline" disabled={uploads.length === 0 || analyze.isPending} onClick={() => void check()}>
            {analyze.isPending && <Spinner />}
            {t("import.check")}
          </Button>
          {outcome && <Outcome outcome={outcome} />}
          {analysis && <FileList files={analysis.files} />}
        </CardContent>
      </Card>

      {analysis?.auth && (
        <Part id="auth" title={t("import.authTitle")} description={t("import.authDesc")} checked={auth && authReady} disabled={!authReady} onChange={setAuth}>
          <AuthPreview plan={analysis.auth} />
        </Part>
      )}
      {analysis?.retained && (
        <Part id="retained" title={t("import.dbTitle")} description={t("import.dbDesc")} checked={retained && retainedReady} disabled={!retainedReady} onChange={setRetained}>
          <RetainedPreview plan={analysis.retained} />
        </Part>
      )}
      {analysis?.conf && (
        <Part id="conf" title={t("import.confTitle")} checked={conf && confReady} disabled={!confReady} onChange={setConf}>
          <ConfPreview plan={analysis.conf} />
        </Part>
      )}

      {analysis && (
        <div className="flex justify-end">
          <Button disabled={!anything || run.isPending} onClick={() => setConfirming(true)}>
            {run.isPending && <Spinner />}
            {t("import.confirmRun")}
          </Button>
        </div>
      )}
      <ConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title={t("import.confirmRunTitle")}
        description={restarts ? t("import.confirmRestartHint") : t("import.dbDesc")}
        confirmLabel={t("import.confirmRun")}
        destructive={restarts}
        onConfirm={() => void runImport()}
      />
    </div>
  )
}

function Outcome({ outcome }: { outcome: Schemas["ImportOutcome"] }) {
  const { t } = useTranslation()
  return (
    <>
      {outcome.error && (
        <Alert variant="destructive">
          <TriangleAlertIcon />
          <AlertTitle>{t("import.failed")}</AlertTitle>
          <AlertDescription className="font-mono text-xs whitespace-pre-wrap">{outcome.error}</AlertDescription>
        </Alert>
      )}
      {(outcome.auth || outcome.retained || outcome.conf) && (
        <Alert>
          <AlertDescription>
            <ul className="list-inside list-disc">
              {outcome.auth && <li>{t("import.done", { clients: outcome.auth.clients, roles: outcome.auth.roles })}</li>}
              {outcome.auth && <li>{t("import.doneHint")}</li>}
              {outcome.retained && <li>{t("import.donePublished", { count: outcome.retained.published })}</li>}
              {outcome.conf && <li>{t("import.doneConf", { bridges: outcome.conf.bridges, options: outcome.conf.options })}</li>}
            </ul>
          </AlertDescription>
        </Alert>
      )}
    </>
  )
}

function FileList({ files }: { files: Schemas["ImportFileInfo"][] }) {
  const { t } = useTranslation()
  return (
    <div className="max-h-72 overflow-y-auto rounded-lg border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{t("import.file")}</TableHead>
            <TableHead>{t("import.kind")}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {files.map((file, index) => (
            <TableRow key={index}>
              <TableCell className="font-mono text-xs">{file.name}</TableCell>
              <TableCell>
                <div className="flex flex-wrap items-center gap-2">
                  <Badge variant={file.kind === "UNKNOWN" ? "destructive" : "secondary"}>{t(`import.kinds.${file.kind as Kind}`)}</Badge>
                  {file.error && <span className="text-xs text-muted-foreground">{t(`import.fileError.${file.error as "second_db"}`, { defaultValue: file.error })}</span>}
                </div>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

function Part({
  id,
  title,
  description,
  checked,
  disabled,
  onChange,
  children,
}: {
  id: string
  title: string
  description?: string
  checked: boolean
  disabled: boolean
  onChange: (checked: boolean) => void
  children: React.ReactNode
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Checkbox id={`part-${id}`} checked={checked} disabled={disabled} onCheckedChange={(value) => onChange(value === true)} />
          <Label htmlFor={`part-${id}`} className="text-base font-medium">
            {title}
          </Label>
        </CardTitle>
        {description && <CardDescription>{description}</CardDescription>}
      </CardHeader>
      <CardContent className="space-y-4">{children}</CardContent>
    </Card>
  )
}

function AuthPreview({ plan }: { plan: Schemas["AuthImportPlan"] }) {
  const { t } = useTranslation()
  return (
    <>
      {plan.warnings.length > 0 && (
        <Alert>
          <TriangleAlertIcon />
          <AlertDescription>
            <ul className="list-inside list-disc">
              {plan.warnings.map((warning, index) => (
                <li key={index}>{t(`import.warnings.${warning.code as "invalid_line"}`, { subject: warning.subject })}</li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}
      {plan.clients.length > 0 && (
        <div className="max-h-96 overflow-y-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("import.client")}</TableHead>
                <TableHead>{t("import.hash")}</TableHead>
                <TableHead>{t("import.rules")}</TableHead>
                <TableHead>{t("import.status")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {plan.clients.map((client) => (
                <TableRow key={client.username}>
                  <TableCell className="font-medium">{client.username}</TableCell>
                  <TableCell>{client.hashType ?? t("common.none")}</TableCell>
                  <TableCell>{client.rules}</TableCell>
                  <TableCell>
                    <Badge variant={client.status === "NEW" ? "default" : client.status === "EXISTS" ? "secondary" : "destructive"}>
                      {t(`import.statuses.${client.status as ClientStatus}`)}
                    </Badge>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      <div className="text-sm text-muted-foreground">
        {plan.patternRules > 0 && <p>{t("import.patternRules", { count: plan.patternRules })}</p>}
        {plan.anonymousRules > 0 && <p>{t("import.anonymousRules", { count: plan.anonymousRules })}</p>}
      </div>
    </>
  )
}

function RetainedPreview({ plan }: { plan: Schemas["RetainedPlan"] }) {
  const { t } = useTranslation()
  return (
    <>
      <div className="text-sm text-muted-foreground">
        <p>{t("import.messages", { count: plan.messages.length })}</p>
        {plan.skipped > 0 && <p>{t("import.skipped", { count: plan.skipped })}</p>}
        {plan.withProperties > 0 && <p>{t("import.withProperties", { count: plan.withProperties })}</p>}
      </div>
      {plan.messages.length > 0 && (
        <div className="max-h-96 overflow-y-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("import.topic")}</TableHead>
                <TableHead>{t("import.payload")}</TableHead>
                <TableHead>{t("import.size")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {plan.messages.slice(0, MAX_ROWS).map((message) => (
                <TableRow key={message.topic}>
                  <TableCell className="font-mono text-xs">{message.topic}</TableCell>
                  <TableCell className="max-w-64 truncate font-mono text-xs">{message.preview ?? t("import.binary")}</TableCell>
                  <TableCell>{message.size}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {plan.messages.length > MAX_ROWS && <p className="text-sm text-muted-foreground">{t("import.moreMessages", { count: plan.messages.length - MAX_ROWS })}</p>}
    </>
  )
}

function ConfPreview({ plan }: { plan: Schemas["ConfImportPlan"] }) {
  const { t } = useTranslation()
  const warnings = plan.bridges.flatMap((bridge) => bridge.warnings.map((warning) => t(`bridges.warnings.${warning as BridgeWarning}`, { name: bridge.name })))
  return (
    <>
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
      {plan.bridges.length > 0 && (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("import.bridges")}</TableHead>
                <TableHead>{t("bridges.address")}</TableHead>
                <TableHead>{t("bridges.topics")}</TableHead>
                <TableHead>{t("import.status")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {plan.bridges.map((bridge) => (
                <TableRow key={bridge.name}>
                  <TableCell className="font-medium">{bridge.name}</TableCell>
                  <TableCell className="font-mono text-xs">{bridge.address}</TableCell>
                  <TableCell>{bridge.topics}</TableCell>
                  <TableCell>
                    <Badge variant={bridge.status === "NEW" ? "default" : bridge.status === "EXISTS" ? "secondary" : "destructive"}>
                      {t(`import.bridgeStatuses.${bridge.status as BridgeStatus}`)}
                    </Badge>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {plan.options.length > 0 && (
        <div className="space-y-1 text-sm">
          <p className="text-muted-foreground">{t("import.confOptions")}</p>
          <pre className="overflow-x-auto rounded-lg border p-3 font-mono text-xs">{plan.options.join("\n")}</pre>
        </div>
      )}
      {plan.skipped.length > 0 && (
        <div className="space-y-1 text-sm">
          <p className="text-muted-foreground">{t("import.confSkipped")}</p>
          <ul className="space-y-1 rounded-lg border p-3 text-xs">
            {plan.skipped.map((skipped, index) => (
              <li key={index} className="flex flex-wrap justify-between gap-2">
                <span className="font-mono break-all">{skipped.line}</span>
                <span className="text-muted-foreground">{t(`import.skipReasons.${skipped.reason as SkipReason}`)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </>
  )
}
