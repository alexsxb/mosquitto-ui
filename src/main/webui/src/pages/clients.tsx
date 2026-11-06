import { useQueryClient } from "@tanstack/react-query"
import { KeyRoundIcon, LockIcon, PencilIcon, PlusIcon, ScrollTextIcon, Trash2Icon, UnplugIcon } from "lucide-react"
import { useMemo, useState, type FormEvent } from "react"
import { useTranslation } from "react-i18next"
import { useNavigate, useSearchParams } from "react-router"
import { toast } from "sonner"

import { $api, type Schemas } from "@/api/client"
import { ConfirmDialog } from "@/components/confirm-dialog"
import { BadgeList } from "@/components/badge-list"
import { MultiSelect } from "@/components/multi-select"
import { PageToolbar } from "@/components/page-toolbar"
import { SortableHead } from "@/components/sortable-head"
import { TablePagination } from "@/components/table-pagination"
import { TokenClients } from "@/components/token-clients"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { Spinner } from "@/components/ui/spinner"
import { Switch } from "@/components/ui/switch"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Textarea } from "@/components/ui/textarea"
import { useErrorToast } from "@/lib/errors"
import { formatDateTime } from "@/lib/format"
import { useTable } from "@/lib/use-table"

type Client = Schemas["ClientView"]
type Status = "all" | "online" | "offline" | "disabled"

const clientsKey = ["get", "/api/clients"]
const statuses: Status[] = ["all", "online", "offline", "disabled"]
const ALL_ROLES = "*"
const columns = {
  username: (client: Client) => client.username,
  roles: (client: Client) => client.roles.length,
  connections: (client: Client) => client.connections.length,
  lastLogin: (client: Client) => (client.lastLogin ? Date.parse(client.lastLogin.lastSeen) : 0),
}

function hasStatus(client: Client, status: Status) {
  switch (status) {
    case "online":
      return client.connections.length > 0
    case "offline":
      return client.connections.length === 0
    case "disabled":
      return client.disabled
    default:
      return true
  }
}

export function ClientsPage() {
  const { t, i18n } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const clients = $api.useQuery("get", "/api/clients")
  // the same list as the card of clients with a token, for the roles to choose from
  const tokenClients = $api.useQuery("get", "/api/token-auth/seen")
  const deleteClient = $api.useMutation("delete", "/api/clients/{username}")
  const setEnabled = $api.useMutation("put", "/api/clients/{username}/enabled")
  const disconnect = $api.useMutation("post", "/api/clients/{username}/disconnect")

  const [search, setSearch] = useState("")
  // in the address, so that a link can lead to a filtered table
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const status = statuses.find((item) => item === params.get("status")) ?? "all"
  const [role, setRole] = useState(ALL_ROLES)
  const [editing, setEditing] = useState<Client | "new" | null>(null)
  const [changingPassword, setChangingPassword] = useState<Client | null>(null)
  const [deleting, setDeleting] = useState<Client | null>(null)
  const [disconnecting, setDisconnecting] = useState<Client | null>(null)

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase()
    return (clients.data ?? [])
      .filter((c) => [c.username, c.textname, ...c.roles, ...c.groups].some((v) => v?.toLowerCase().includes(term)))
      .filter((c) => hasStatus(c, status) && (role === ALL_ROLES || c.roles.includes(role)))
  }, [clients.data, search, status, role])
  const table = useTable("clients", filtered, columns, "username")
  const roleNames = useMemo(
    () => [...new Set([...(clients.data ?? []).flatMap((c) => c.roles), ...(tokenClients.data ?? []).flatMap((c) => c.roles)])].sort(),
    [clients.data, tokenClients.data],
  )

  async function confirmDelete() {
    if (!deleting) {
      return
    }
    try {
      await deleteClient.mutateAsync({ params: { path: { username: deleting.username } } })
      toast.success(t("clients.deleted", { name: deleting.username }))
      await queryClient.invalidateQueries({ queryKey: clientsKey })
    } catch (error) {
      errorToast(error)
    } finally {
      setDeleting(null)
    }
  }

  async function confirmDisconnect() {
    if (!disconnecting) {
      return
    }
    try {
      await disconnect.mutateAsync({ params: { path: { username: disconnecting.username } } })
      toast.success(t("clients.disconnected", { name: disconnecting.username }))
      await queryClient.invalidateQueries({ queryKey: clientsKey })
    } catch (error) {
      errorToast(error)
    } finally {
      setDisconnecting(null)
    }
  }

  async function toggle(client: Client, enabled: boolean) {
    try {
      await setEnabled.mutateAsync({ params: { path: { username: client.username } }, body: { enabled } })
      await queryClient.invalidateQueries({ queryKey: clientsKey })
    } catch (error) {
      errorToast(error)
    }
  }

  return (
    <>
      <PageToolbar
        search={search}
        onSearch={(value) => {
          setSearch(value)
          table.setPage(0)
        }}
        placeholder={t("clients.search")}
        count={clients.data && t("common.shown", { shown: filtered.length, total: clients.data.length })}
        refresh={[clients]}
      >
        <Select
          value={status}
          onValueChange={(value) => {
            setParams(value === "all" ? {} : { status: value }, { replace: true })
            table.setPage(0)
          }}
        >
          <SelectTrigger className="w-36" aria-label={t("clients.status")}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {statuses.map((item) => (
              <SelectItem key={item} value={item}>
                {t(`clients.statuses.${item}`)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select
          value={role}
          onValueChange={(value) => {
            setRole(value)
            table.setPage(0)
          }}
        >
          <SelectTrigger className="w-40" aria-label={t("nav.roles")}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL_ROLES}>{t("clients.allRoles")}</SelectItem>
            {roleNames.map((name) => (
              <SelectItem key={name} value={name}>
                {name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Button onClick={() => setEditing("new")}>
          <PlusIcon />
          {t("clients.add")}
        </Button>
      </PageToolbar>

      {clients.isPending ? (
        <Skeleton className="h-40 w-full" />
      ) : filtered.length === 0 ? (
        <p className="py-8 text-center text-sm text-muted-foreground">{t("clients.empty")}</p>
      ) : (
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <SortableHead active={table.sort === "username"} direction={table.direction} onSort={() => table.toggle("username")}>
                  {t("clients.username")}
                </SortableHead>
                <SortableHead active={table.sort === "roles"} direction={table.direction} onSort={() => table.toggle("roles")}>
                  {t("nav.roles")}
                </SortableHead>
                <TableHead className="hidden lg:table-cell">{t("nav.groups")}</TableHead>
                <SortableHead className="hidden md:table-cell" active={table.sort === "connections"} direction={table.direction} onSort={() => table.toggle("connections")}>
                  {t("clients.connections")}
                </SortableHead>
                <SortableHead className="hidden xl:table-cell" active={table.sort === "lastLogin"} direction={table.direction} onSort={() => table.toggle("lastLogin")}>
                  {t("clients.lastLogin")}
                </SortableHead>
                <TableHead>{t("clients.enabled")}</TableHead>
                <TableHead className="w-36" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {table.rows.map((client) => (
                <TableRow key={client.username}>
                  <TableCell className={client.disabled ? "font-medium text-muted-foreground" : "font-medium"}>
                    {client.username}
                    {client.locked && <LockIcon className="ml-2 inline size-3 text-muted-foreground" aria-label={t("common.locked")} />}
                    {client.textname && <span className="ml-2 font-normal text-muted-foreground">{client.textname}</span>}
                  </TableCell>
                  <TableCell>
                    <BadgeList names={client.roles} />
                  </TableCell>
                  <TableCell className="hidden lg:table-cell">
                    <BadgeList names={client.groups} />
                  </TableCell>
                  <TableCell className="hidden md:table-cell" title={client.connections.join(", ")}>
                    <span className="inline-flex items-center gap-2">
                      <span className={client.connections.length > 0 ? "size-2 rounded-full bg-emerald-500" : "size-2 rounded-full bg-muted-foreground/40"} />
                      {client.connections.length}
                    </span>
                  </TableCell>
                  <TableCell className="hidden xl:table-cell">
                    {client.lastLogin ? (
                      <>
                        <div className="text-sm">
                          {client.connections.length > 0
                            ? t("clients.connectedSince", { date: formatDateTime(client.lastLogin.connectedAt, i18n.language) })
                            : formatDateTime(client.lastLogin.lastSeen, i18n.language)}
                        </div>
                        <div className="font-mono text-xs text-muted-foreground">{client.lastLogin.address}</div>
                      </>
                    ) : (
                      <span className="text-muted-foreground">–</span>
                    )}
                  </TableCell>
                  <TableCell>
                    <Switch
                      aria-label={t("clients.enabled")}
                      checked={!client.disabled}
                      disabled={client.locked}
                      onCheckedChange={(enabled) => void toggle(client, enabled)}
                    />
                  </TableCell>
                  <TableCell>
                    <div className="flex justify-end">
                      <Button variant="ghost" size="icon-sm" aria-label={t("common.edit")} disabled={client.locked} onClick={() => setEditing(client)}>
                        <PencilIcon />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label={t("clients.changePassword")}
                        title={t("clients.changePassword")}
                        disabled={client.locked}
                        onClick={() => setChangingPassword(client)}
                      >
                        <KeyRoundIcon />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label={t("clients.disconnect")}
                        title={t("clients.disconnect")}
                        disabled={client.locked || client.connections.length === 0}
                        onClick={() => setDisconnecting(client)}
                      >
                        <UnplugIcon />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label={t("clients.logs", { name: client.username })}
                        title={t("clients.logs", { name: client.username })}
                        onClick={() => {
                          const query = new URLSearchParams({ user: client.username })
                          client.connections.forEach((id) => query.append("id", id))
                          void navigate(`/logs?${query.toString()}`)
                        }}
                      >
                        <ScrollTextIcon />
                      </Button>
                      <Button variant="ghost" size="icon-sm" aria-label={t("common.delete")} disabled={client.locked} onClick={() => setDeleting(client)}>
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
      <TablePagination page={table.page} pageSize={table.pageSize} total={table.total} onPage={table.setPage} onPageSize={table.setPageSize} allowAll />
      <TokenClients filter={{ search, status, role: role === ALL_ROLES ? null : role }} />

      {editing !== null && <ClientDialog client={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
      {changingPassword !== null && <PasswordDialog client={changingPassword} onClose={() => setChangingPassword(null)} />}
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={t("clients.deleteTitle", { name: deleting?.username })}
        description={t("clients.deleteDesc")}
        onConfirm={() => void confirmDelete()}
      />
      <ConfirmDialog
        open={disconnecting !== null}
        onOpenChange={(open) => !open && setDisconnecting(null)}
        title={t("clients.disconnectTitle", { name: disconnecting?.username })}
        description={t("clients.disconnectDesc")}
        confirmLabel={t("clients.disconnect")}
        onConfirm={() => void confirmDisconnect()}
      />
    </>
  )
}

function ClientDialog({ client, onClose }: { client: Client | null; onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const allRoles = $api.useQuery("get", "/api/roles")
  const allGroups = $api.useQuery("get", "/api/groups")
  const create = $api.useMutation("post", "/api/clients")
  const update = $api.useMutation("put", "/api/clients/{username}")

  const [username, setUsername] = useState(client?.username ?? "")
  const [password, setPassword] = useState("")
  const [textname, setTextname] = useState(client?.textname ?? "")
  const [textdescription, setTextdescription] = useState(client?.textdescription ?? "")
  const [roles, setRoles] = useState(client?.roles ?? [])
  const [groups, setGroups] = useState(client?.groups ?? [])

  async function submit(event: FormEvent) {
    event.preventDefault()
    try {
      if (client) {
        await update.mutateAsync({ params: { path: { username: client.username } }, body: { textname, textdescription, roles, groups } })
      } else {
        await create.mutateAsync({ body: { username, password, textname, textdescription, roles, groups } })
      }
      toast.success(t("clients.saved"))
      await queryClient.invalidateQueries({ queryKey: clientsKey })
      onClose()
    } catch (error) {
      errorToast(error)
    }
  }

  const saving = create.isPending || update.isPending

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <form onSubmit={submit} className="space-y-6">
          <DialogHeader>
            <DialogTitle>{client ? t("clients.editTitle", { name: client.username }) : t("clients.addTitle")}</DialogTitle>
          </DialogHeader>
          <FieldGroup>
            {!client && (
              <div className="grid gap-4 sm:grid-cols-2">
                <Field>
                  <FieldLabel htmlFor="client-username">{t("clients.username")}</FieldLabel>
                  <Input id="client-username" required autoComplete="off" pattern="[^\/]+" value={username} onChange={(e) => setUsername(e.target.value)} />
                </Field>
                <Field>
                  <FieldLabel htmlFor="client-password">{t("clients.password")}</FieldLabel>
                  <Input id="client-password" type="password" required autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
                </Field>
              </div>
            )}
            <Field>
              <FieldLabel htmlFor="client-textname">{t("common.name")}</FieldLabel>
              <Input id="client-textname" value={textname} onChange={(e) => setTextname(e.target.value)} />
            </Field>
            <Field>
              <FieldLabel htmlFor="client-textdescription">{t("common.description")}</FieldLabel>
              <Textarea id="client-textdescription" rows={2} value={textdescription} onChange={(e) => setTextdescription(e.target.value)} />
            </Field>
            <Field>
              <FieldLabel>{t("nav.roles")}</FieldLabel>
              <MultiSelect idPrefix="client-role" label={t("nav.roles")} names={allRoles.data?.map((r) => r.rolename) ?? []} selected={roles} onChange={setRoles} />
              <FieldDescription>{t("clients.rolesHint")}</FieldDescription>
            </Field>
            <Field>
              <FieldLabel>{t("nav.groups")}</FieldLabel>
              <MultiSelect idPrefix="client-group" label={t("nav.groups")} names={allGroups.data?.map((g) => g.groupname) ?? []} selected={groups} onChange={setGroups} />
            </Field>
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

function PasswordDialog({ client, onClose }: { client: Client; onClose: () => void }) {
  const { t } = useTranslation()
  const errorToast = useErrorToast()
  const change = $api.useMutation("put", "/api/clients/{username}/password")
  const [password, setPassword] = useState("")

  async function submit(event: FormEvent) {
    event.preventDefault()
    try {
      await change.mutateAsync({ params: { path: { username: client.username } }, body: { password } })
      toast.success(t("clients.passwordChanged"))
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
            <DialogTitle>{t("clients.passwordTitle", { name: client.username })}</DialogTitle>
            <DialogDescription>{t("clients.passwordDesc")}</DialogDescription>
          </DialogHeader>
          <Field>
            <FieldLabel htmlFor="client-new-password">{t("clients.password")}</FieldLabel>
            <Input id="client-new-password" type="password" required autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
          </Field>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={change.isPending}>
              {change.isPending && <Spinner />}
              {t("common.save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
