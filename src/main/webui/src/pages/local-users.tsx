import { useQueryClient } from "@tanstack/react-query"
import { KeyRoundIcon, LogOutIcon, PlusIcon, Trash2Icon } from "lucide-react"
import { useState, type FormEvent } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"

import { $api, type Schemas } from "@/api/client"
import { ConfirmDialog } from "@/components/confirm-dialog"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import { Spinner } from "@/components/ui/spinner"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { useErrorToast } from "@/lib/errors"
import { formatDateTime } from "@/lib/format"

type LocalUser = Schemas["LocalUserView"]

const MIN_PASSWORD = 12

/** The users of the local login; each of them manages the broker. People of a provider can log in as one of them (page Login). */
export function LocalUsersPage() {
  const { t, i18n } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const users = $api.useQuery("get", "/api/local-users")
  const remove = $api.useMutation("delete", "/api/local-users/{username}")
  const endSessions = $api.useMutation("post", "/api/local-users/{username}/end-sessions")
  const [adding, setAdding] = useState(false)
  const [password, setPassword] = useState<LocalUser | null>(null)
  const [deleting, setDeleting] = useState<LocalUser | null>(null)
  const [ending, setEnding] = useState<LocalUser | null>(null)

  const reload = () => queryClient.invalidateQueries({ queryKey: ["get", "/api/local-users"] })

  async function confirmDelete() {
    if (!deleting) {
      return
    }
    try {
      await remove.mutateAsync({ params: { path: { username: deleting.username } } })
      toast.success(t("localUsers.deleted", { name: deleting.username }))
      await reload()
    } catch (error) {
      errorToast(error)
    }
    setDeleting(null)
  }

  async function confirmEnd() {
    if (!ending) {
      return
    }
    try {
      await endSessions.mutateAsync({ params: { path: { username: ending.username } } })
      toast.success(t("localUsers.sessionsEnded", { name: ending.username }))
    } catch (error) {
      errorToast(error)
    }
    setEnding(null)
  }

  return (
    <>
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <p className="text-muted-foreground max-w-2xl flex-1 text-sm">{t("localUsers.description")}</p>
        <Button onClick={() => setAdding(true)}>
          <PlusIcon />
          {t("localUsers.add")}
        </Button>
      </div>
      {!users.data ? (
        <Skeleton className="h-40 w-full" />
      ) : (
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("localUsers.username")}</TableHead>
                <TableHead className="hidden sm:table-cell">{t("localUsers.created")}</TableHead>
                <TableHead>{t("localUsers.lastLogin")}</TableHead>
                <TableHead className="w-32" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {users.data.map((user) => (
                <TableRow key={user.username}>
                  <TableCell className="font-medium">
                    {user.username}
                    {user.self && (
                      <Badge variant="secondary" className="ml-2">
                        {t("localUsers.you")}
                      </Badge>
                    )}
                  </TableCell>
                  <TableCell className="text-muted-foreground hidden sm:table-cell">{formatDateTime(user.created as string, i18n.language)}</TableCell>
                  <TableCell className="text-muted-foreground">{user.lastLogin ? formatDateTime(user.lastLogin as string, i18n.language) : "–"}</TableCell>
                  <TableCell>
                    <div className="flex justify-end">
                      <Button variant="ghost" size="icon-sm" aria-label={t("localUsers.changePassword")} title={t("localUsers.changePassword")} onClick={() => setPassword(user)}>
                        <KeyRoundIcon />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label={t("localUsers.endSessions")}
                        title={t("localUsers.endSessions")}
                        disabled={user.self}
                        onClick={() => setEnding(user)}
                      >
                        <LogOutIcon />
                      </Button>
                      <Button variant="ghost" size="icon-sm" aria-label={t("common.delete")} disabled={user.self} onClick={() => setDeleting(user)}>
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
      {adding && <UserDialog onClose={() => setAdding(false)} onDone={reload} />}
      {password && <UserDialog user={password} onClose={() => setPassword(null)} onDone={reload} />}
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={t("localUsers.deleteTitle", { name: deleting?.username })}
        description={t("localUsers.deleteDesc")}
        confirmLabel={t("common.delete")}
        onConfirm={() => void confirmDelete()}
      />
      <ConfirmDialog
        open={ending !== null}
        onOpenChange={(open) => !open && setEnding(null)}
        title={t("localUsers.endTitle", { name: ending?.username })}
        description={t("localUsers.endDesc")}
        confirmLabel={t("localUsers.endSessions")}
        onConfirm={() => void confirmEnd()}
      />
    </>
  )
}

/** A new user, or a new password for one: user given */
function UserDialog({ user, onClose, onDone }: { user?: LocalUser; onClose: () => void; onDone: () => Promise<unknown> }) {
  const { t } = useTranslation()
  const errorToast = useErrorToast()
  const create = $api.useMutation("post", "/api/local-users")
  const change = $api.useMutation("put", "/api/local-users/{username}/password")
  const [username, setUsername] = useState("")
  const [password, setPassword] = useState("")
  const [repeat, setRepeat] = useState("")
  const busy = create.isPending || change.isPending
  const mismatch = repeat.length > 0 && repeat !== password

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (password !== repeat) {
      return
    }
    try {
      if (user) {
        await change.mutateAsync({ params: { path: { username: user.username } }, body: { password } })
        toast.success(t("localUsers.passwordChanged", { name: user.username }))
      } else {
        await create.mutateAsync({ body: { username: username.trim(), password } })
        toast.success(t("localUsers.createdToast", { name: username.trim() }))
      }
      await onDone()
      onClose()
    } catch (error) {
      errorToast(error)
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <form onSubmit={(e) => void submit(e)} className="space-y-6">
          <DialogHeader>
            <DialogTitle>{user ? t("localUsers.passwordTitle", { name: user.username }) : t("localUsers.addTitle")}</DialogTitle>
            <DialogDescription>{user ? (user.self ? t("localUsers.passwordSelf") : t("localUsers.passwordOther")) : t("localUsers.addDesc")}</DialogDescription>
          </DialogHeader>
          <FieldGroup>
            {!user && (
              <Field>
                <FieldLabel htmlFor="local-user-name">{t("localUsers.username")}</FieldLabel>
                <Input id="local-user-name" required pattern="[A-Za-z0-9._@\-]{1,64}" autoComplete="off" value={username} onChange={(e) => setUsername(e.target.value)} />
              </Field>
            )}
            <Field>
              <FieldLabel htmlFor="local-user-password">{t("localUsers.password")}</FieldLabel>
              <Input
                id="local-user-password"
                type="password"
                required
                minLength={MIN_PASSWORD}
                autoComplete="new-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
              <FieldDescription>{t("localUsers.passwordHint", { count: MIN_PASSWORD })}</FieldDescription>
            </Field>
            <Field>
              <FieldLabel htmlFor="local-user-repeat">{t("localUsers.repeat")}</FieldLabel>
              <Input id="local-user-repeat" type="password" required autoComplete="new-password" value={repeat} onChange={(e) => setRepeat(e.target.value)} />
              {mismatch && <FieldDescription className="text-destructive">{t("localUsers.mismatch")}</FieldDescription>}
            </Field>
          </FieldGroup>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={busy || mismatch}>
              {busy && <Spinner />}
              {t("common.save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
