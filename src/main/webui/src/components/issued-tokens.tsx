import { useQueryClient } from "@tanstack/react-query"
import { CopyIcon, PencilIcon, PlusIcon, Undo2Icon } from "lucide-react"
import { useState, type FormEvent } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"

import { $api, type Schemas } from "@/api/client"
import { BadgeList } from "@/components/badge-list"
import { ConfirmDialog } from "@/components/confirm-dialog"
import { MultiSelect } from "@/components/multi-select"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Spinner } from "@/components/ui/spinner"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Textarea } from "@/components/ui/textarea"
import { useErrorToast } from "@/lib/errors"
import { formatDateTime } from "@/lib/format"

type Issued = Schemas["IssuedToken"]
type Result = Schemas["IssueResult"]

const settingsKey = { queryKey: ["get", "/api/token-auth"] }
/** How long a new token holds, in days */
const lifetimes = [30, 90, 365, 1825]

/** The form for a new token, then the token itself, shown this once */
function IssueDialog({ roles, onClose: close }: { roles: string[]; onClose: () => void }) {
  const { t, i18n } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const issue = $api.useMutation("post", "/api/token-auth/issued")
  const [username, setUsername] = useState("")
  const [selected, setSelected] = useState<string[]>([])
  const [days, setDays] = useState("365")
  const [note, setNote] = useState("")
  const [result, setResult] = useState<Result | null>(null)

  function onClose() {
    close()
    if (result) {
      void queryClient.invalidateQueries(settingsKey)
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    // not on to a form around the dialog, see the page Token login
    event.stopPropagation()
    try {
      const expires = new Date(Date.now() + Number(days) * 86_400_000).toISOString()
      const answer = await issue.mutateAsync({ body: { username, roles: selected, expires, note: note || null } })
      // the list is loaded again only when the dialog closes: the first token turns token login on, which builds the page
      // anew, and the token, shown this once, would be gone with the dialog
      setResult(answer)
    } catch (error) {
      errorToast(error)
    }
  }

  async function copy(text: string) {
    await navigator.clipboard.writeText(text)
    toast.success(t("issuedTokens.copied"))
  }

  if (result) {
    const example = `mosquitto_pub -h BROKER -p 8883 -i ${result.issued.username} -u token -P "$TOKEN" -t ... -m ...`
    return (
      <Dialog open onOpenChange={(open) => !open && onClose()}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>{t("issuedTokens.resultTitle", { name: result.issued.username })}</DialogTitle>
            <DialogDescription>{t("issuedTokens.resultDescription", { time: formatDateTime(result.issued.expires as string, i18n.language) })}</DialogDescription>
          </DialogHeader>
          <Alert variant="destructive">
            <AlertDescription>{t("issuedTokens.onlyNow")}</AlertDescription>
          </Alert>
          <Textarea readOnly aria-label={t("issuedTokens.token")} className="h-32 font-mono text-xs break-all" value={result.token} onFocus={(e) => e.target.select()} />
          <div className="space-y-1 text-sm">
            <div className="text-muted-foreground">{t("issuedTokens.example")}</div>
            <pre className="bg-muted overflow-x-auto rounded-md p-3 font-mono text-xs">{example}</pre>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => void copy(result.token)}>
              <CopyIcon />
              {t("issuedTokens.copy")}
            </Button>
            <Button type="button" onClick={onClose}>
              {t("issuedTokens.done")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    )
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <form onSubmit={submit} className="space-y-6">
          <DialogHeader>
            <DialogTitle>{t("issuedTokens.issueTitle")}</DialogTitle>
            <DialogDescription>{t("issuedTokens.issueDescription")}</DialogDescription>
          </DialogHeader>
          <FieldGroup>
            <Field>
              <FieldLabel htmlFor="issue-username">{t("issuedTokens.username")}</FieldLabel>
              <Input id="issue-username" required value={username} onChange={(e) => setUsername(e.target.value)} placeholder="meter-1" />
              <FieldDescription>{t("issuedTokens.usernameHint")}</FieldDescription>
            </Field>
            <Field>
              <FieldLabel>{t("issuedTokens.roles")}</FieldLabel>
              <MultiSelect idPrefix="issue-roles" label={t("issuedTokens.roles")} names={roles} selected={selected} onChange={setSelected} />
              <FieldDescription>{t("issuedTokens.rolesHint")}</FieldDescription>
            </Field>
            <Field>
              <FieldLabel>{t("issuedTokens.lifetime")}</FieldLabel>
              <Select value={days} onValueChange={setDays}>
                <SelectTrigger className="w-full" aria-label={t("issuedTokens.lifetime")}>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {lifetimes.map((d) => (
                    <SelectItem key={d} value={String(d)}>
                      {t("issuedTokens.days", { count: d })}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <Field>
              <FieldLabel htmlFor="issue-note">{t("issuedTokens.note")}</FieldLabel>
              <Input id="issue-note" value={note} onChange={(e) => setNote(e.target.value)} />
            </Field>
          </FieldGroup>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={issue.isPending || !username || selected.length === 0}>
              {issue.isPending && <Spinner />}
              {t("issuedTokens.issue")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function RolesDialog({ token, roles, onClose }: { token: Issued; roles: string[]; onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const change = $api.useMutation("put", "/api/token-auth/issued/{id}")
  const [selected, setSelected] = useState(token.roles)

  async function submit(event: FormEvent) {
    event.preventDefault()
    // not on to a form around the dialog, see the page Token login
    event.stopPropagation()
    try {
      await change.mutateAsync({ params: { path: { id: token.id } }, body: selected })
      toast.success(t("issuedTokens.rolesChanged", { name: token.username }))
      await queryClient.invalidateQueries(settingsKey)
      onClose()
    } catch (error) {
      errorToast(error)
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <form onSubmit={submit} className="space-y-6">
          <DialogHeader>
            <DialogTitle>{t("issuedTokens.rolesTitle", { name: token.username })}</DialogTitle>
            <DialogDescription>{t("issuedTokens.rolesDescription")}</DialogDescription>
          </DialogHeader>
          <MultiSelect idPrefix="issued-roles" label={t("issuedTokens.roles")} names={roles} selected={selected} onChange={setSelected} />
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={change.isPending || selected.length === 0}>
              {change.isPending && <Spinner />}
              {t("common.save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/** Tokens this application signed itself, with their roles kept here: change them, or take a token back. */
export function IssuedTokensCard({ issued, roles, enabled }: { issued: Issued[]; roles: string[]; enabled: boolean }) {
  const { t, i18n } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const revoke = $api.useMutation("delete", "/api/token-auth/issued/{id}")
  const [issuing, setIssuing] = useState(false)
  const [editing, setEditing] = useState<Issued | null>(null)
  const [revoking, setRevoking] = useState<Issued | null>(null)

  async function confirmRevoke() {
    if (!revoking) {
      return
    }
    try {
      await revoke.mutateAsync({ params: { path: { id: revoking.id } } })
      toast.success(t("issuedTokens.revoked", { name: revoking.username }))
      await queryClient.invalidateQueries(settingsKey)
    } catch (error) {
      errorToast(error)
    } finally {
      setRevoking(null)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("issuedTokens.title")}</CardTitle>
        <CardDescription>{t("issuedTokens.description")}</CardDescription>
        <CardAction>
          <Button type="button" variant="outline" size="sm" onClick={() => setIssuing(true)}>
            <PlusIcon />
            {t("issuedTokens.issue")}
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="space-y-3 overflow-x-auto">
        {!enabled && issued.length > 0 && (
          <Alert variant="destructive">
            <AlertDescription>{t("issuedTokens.disabled")}</AlertDescription>
          </Alert>
        )}
        {issued.length === 0 ? (
          <p className="text-muted-foreground text-sm">{t("issuedTokens.none")}</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("issuedTokens.username")}</TableHead>
                <TableHead>{t("issuedTokens.roles")}</TableHead>
                <TableHead>{t("issuedTokens.expires")}</TableHead>
                <TableHead>{t("issuedTokens.note")}</TableHead>
                <TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {issued.map((token) => (
                <TableRow key={token.id}>
                  <TableCell className="font-medium">{token.username}</TableCell>
                  <TableCell>
                    <BadgeList names={token.roles} />
                  </TableCell>
                  <TableCell className="whitespace-nowrap">{formatDateTime(token.expires as string, i18n.language)}</TableCell>
                  <TableCell>
                    <div>{token.note ?? "–"}</div>
                    <div className="text-muted-foreground text-xs">{t("tokenBlocks.by", { name: token.createdBy, time: formatDateTime(token.created as string, i18n.language) })}</div>
                  </TableCell>
                  <TableCell>
                    <div className="flex gap-1">
                      <Button type="button" variant="ghost" size="icon-sm" aria-label={t("issuedTokens.rolesTitle", { name: token.username })} onClick={() => setEditing(token)}>
                        <PencilIcon />
                      </Button>
                      <Button type="button" variant="ghost" size="icon-sm" aria-label={t("issuedTokens.revoke")} title={t("issuedTokens.revoke")} onClick={() => setRevoking(token)}>
                        <Undo2Icon />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        {issuing && <IssueDialog roles={roles} onClose={() => setIssuing(false)} />}
        {editing && <RolesDialog token={editing} roles={roles} onClose={() => setEditing(null)} />}
        <ConfirmDialog
          open={revoking !== null}
          onOpenChange={(open) => !open && setRevoking(null)}
          title={t("issuedTokens.revokeTitle", { name: revoking?.username })}
          description={t("issuedTokens.revokeDescription")}
          confirmLabel={t("issuedTokens.revoke")}
          onConfirm={() => void confirmRevoke()}
        />
      </CardContent>
    </Card>
  )
}
