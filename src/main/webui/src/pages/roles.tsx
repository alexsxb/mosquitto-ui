import { useQueryClient } from "@tanstack/react-query"
import { ListChecksIcon, LockIcon, PencilIcon, PlusIcon, Trash2Icon } from "lucide-react"
import { useMemo, useState, type FormEvent } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"

import { $api, type Schemas } from "@/api/client"
import { ChipsInput } from "@/components/chips-input"
import { ConfirmDialog } from "@/components/confirm-dialog"
import { DisconnectHint } from "@/components/disconnect-hint"
import { PageToolbar } from "@/components/page-toolbar"
import { SortableHead } from "@/components/sortable-head"
import { TablePagination } from "@/components/table-pagination"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Field, FieldContent, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { Spinner } from "@/components/ui/spinner"
import { Switch } from "@/components/ui/switch"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Textarea } from "@/components/ui/textarea"
import { connectedAmong, holdersOf } from "@/lib/disconnects"
import { useErrorToast } from "@/lib/errors"
import { useTable } from "@/lib/use-table"

type Role = Schemas["RoleView"]
/** A role with how many clients and groups have it. */
type UsedRole = Role & { used: number; usedBy: string[] }

const rolesKey = ["get", "/api/roles"]
const columns = {
  rolename: (role: UsedRole) => role.rolename,
  acls: (role: UsedRole) => role.acls.length,
  used: (role: UsedRole) => role.used,
}
const aclTypes = ["publishClientSend", "publishClientReceive", "subscribeLiteral", "subscribePattern", "unsubscribeLiteral", "unsubscribePattern"] as const
/** What a new role without wildcards can grant on its topics right away. */
const grantTypes = ["subscribeLiteral", "publishClientReceive", "publishClientSend"] as const
/** In the rules table: one choice that adds everything to publish and subscribe on a topic. */
const pubSub = "pubSub"

/** The rule types behind a choice; subscribing to a topic with + or # needs a pattern rule. */
function aclTypesOf(choice: string, topic: string) {
  if (choice !== pubSub) {
    return [choice]
  }
  return ["publishClientSend", "publishClientReceive", /[+#]/.test(topic) ? "subscribePattern" : "subscribeLiteral"]
}

export function RolesPage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const roles = $api.useQuery("get", "/api/roles")
  const clients = $api.useQuery("get", "/api/clients")
  const groups = $api.useQuery("get", "/api/groups")
  const deleteRole = $api.useMutation("delete", "/api/roles/{rolename}")

  const [search, setSearch] = useState("")
  const [editing, setEditing] = useState<Role | "new" | null>(null)
  const [aclsOf, setAclsOf] = useState<string | null>(null)
  const [deleting, setDeleting] = useState<Role | null>(null)

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase()
    return (roles.data ?? [])
      .filter((r) => [r.rolename, r.textname, r.textdescription, ...r.acls.map((acl) => acl.topic)].some((v) => v?.toLowerCase().includes(term)))
      .map((role) => {
        const usedBy = [
          ...(clients.data ?? []).filter((client) => client.roles.includes(role.rolename)).map((client) => client.username),
          ...(groups.data ?? []).filter((group) => group.roles.includes(role.rolename)).map((group) => group.groupname),
        ]
        return { ...role, used: usedBy.length, usedBy }
      })
  }, [roles.data, clients.data, groups.data, search])
  const table = useTable("roles", filtered, columns, "rolename")

  async function confirmDelete() {
    if (!deleting) {
      return
    }
    try {
      await deleteRole.mutateAsync({ params: { path: { rolename: deleting.rolename } } })
      toast.success(t("roles.deleted", { name: deleting.rolename }))
      await queryClient.invalidateQueries({ queryKey: rolesKey })
    } catch (error) {
      errorToast(error)
    } finally {
      setDeleting(null)
    }
  }

  // from the query, so that the dialog shows changes to the list
  const aclRole = roles.data?.find((role) => role.rolename === aclsOf)

  return (
    <>
      <PageToolbar
        search={search}
        onSearch={(value) => {
          setSearch(value)
          table.setPage(0)
        }}
        placeholder={t("roles.search")}
        count={roles.data && t("common.shown", { shown: filtered.length, total: roles.data.length })}
        refresh={[roles, clients, groups]}
      >
        <Button onClick={() => setEditing("new")}>
          <PlusIcon />
          {t("roles.add")}
        </Button>
      </PageToolbar>

      {roles.isPending ? (
        <Skeleton className="h-40 w-full" />
      ) : filtered.length === 0 ? (
        <p className="py-8 text-center text-sm text-muted-foreground">{t("roles.empty")}</p>
      ) : (
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <SortableHead active={table.sort === "rolename"} direction={table.direction} onSort={() => table.toggle("rolename")}>
                  {t("roles.rolename")}
                </SortableHead>
                <TableHead className="hidden md:table-cell">{t("common.description")}</TableHead>
                <SortableHead active={table.sort === "acls"} direction={table.direction} onSort={() => table.toggle("acls")}>
                  {t("roles.acls")}
                </SortableHead>
                <SortableHead className="hidden sm:table-cell" active={table.sort === "used"} direction={table.direction} onSort={() => table.toggle("used")}>
                  {t("roles.usedBy")}
                </SortableHead>
                <TableHead className="w-28" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {table.rows.map((role) => (
                <TableRow key={role.rolename}>
                  <TableCell className="font-medium">
                    {role.rolename}
                    {role.locked && <LockIcon className="ml-2 inline size-3 text-muted-foreground" aria-label={t("common.locked")} />}
                  </TableCell>
                  <TableCell className="hidden max-w-md truncate md:table-cell" title={role.textdescription}>
                    {role.textdescription || role.textname || t("common.none")}
                  </TableCell>
                  <TableCell>{role.acls.length}</TableCell>
                  <TableCell className="hidden sm:table-cell" title={role.usedBy.join(", ")}>
                    {role.used === 0 ? <span className="text-muted-foreground">{t("roles.unused")}</span> : role.used}
                  </TableCell>
                  <TableCell>
                    <div className="flex justify-end">
                      <Button variant="ghost" size="icon-sm" aria-label={t("roles.acls")} title={t("roles.acls")} onClick={() => setAclsOf(role.rolename)}>
                        <ListChecksIcon />
                      </Button>
                      <Button variant="ghost" size="icon-sm" aria-label={t("common.edit")} disabled={role.locked} onClick={() => setEditing(role)}>
                        <PencilIcon />
                      </Button>
                      <Button variant="ghost" size="icon-sm" aria-label={t("common.delete")} disabled={role.locked} onClick={() => setDeleting(role)}>
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

      {editing !== null && <RoleDialog role={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
      {aclRole && <AclDialog role={aclRole} onClose={() => setAclsOf(null)} />}
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={t("roles.deleteTitle", { name: deleting?.rolename })}
        description={t("roles.deleteDesc")}
        onConfirm={() => void confirmDelete()}
      />
    </>
  )
}

/** Who the broker disconnects when the rules of this role change: its connected holders, and with the anonymous group everyone without a login. */
function useRoleHolders(rolename: string | undefined) {
  const clients = $api.useQuery("get", "/api/clients")
  const groups = $api.useQuery("get", "/api/groups")
  if (!rolename) {
    return { connected: 0, anonymous: false }
  }
  return {
    connected: connectedAmong(clients.data, holdersOf(rolename, clients.data, groups.data)),
    anonymous: (groups.data ?? []).some((group) => group.anonymous && group.roles.includes(rolename)),
  }
}

function RoleDialog({ role, onClose }: { role: Role | null; onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const create = $api.useMutation("post", "/api/roles")
  const update = $api.useMutation("put", "/api/roles/{rolename}")
  const affected = useRoleHolders(role?.rolename)

  const [rolename, setRolename] = useState(role?.rolename ?? "")
  const [textname, setTextname] = useState(role?.textname ?? "")
  const [textdescription, setTextdescription] = useState(role?.textdescription ?? "")
  const [allowwildcardsubs, setAllowwildcardsubs] = useState(role?.allowwildcardsubs ?? true)
  const [topics, setTopics] = useState<string[]>([])
  const [grants, setGrants] = useState<string[]>(["subscribeLiteral"])

  // only when creating a role without wildcards; the rules table stays the place for everything else
  const granting = !role && !allowwildcardsubs
  const acls = granting ? topics.flatMap((topic) => grants.map((acltype) => ({ acltype, topic, priority: 0, allow: true }))) : []

  async function submit(event: FormEvent) {
    event.preventDefault()
    try {
      if (role) {
        await update.mutateAsync({ params: { path: { rolename: role.rolename } }, body: { textname, textdescription, allowwildcardsubs } })
      } else {
        await create.mutateAsync({ body: { rolename, textname, textdescription, allowwildcardsubs, acls } })
      }
      toast.success(t("roles.saved"))
      await queryClient.invalidateQueries({ queryKey: rolesKey })
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
            <DialogTitle>{role ? t("roles.editTitle", { name: role.rolename }) : t("roles.addTitle")}</DialogTitle>
          </DialogHeader>
          <FieldGroup>
            {!role && (
              <Field>
                <FieldLabel htmlFor="role-rolename">{t("roles.rolename")}</FieldLabel>
                <Input id="role-rolename" required autoComplete="off" pattern="[^\/]+" value={rolename} onChange={(e) => setRolename(e.target.value)} />
              </Field>
            )}
            <Field>
              <FieldLabel htmlFor="role-textname">{t("common.name")}</FieldLabel>
              <Input id="role-textname" value={textname} onChange={(e) => setTextname(e.target.value)} />
            </Field>
            <Field>
              <FieldLabel htmlFor="role-textdescription">{t("common.description")}</FieldLabel>
              <Textarea id="role-textdescription" rows={2} value={textdescription} onChange={(e) => setTextdescription(e.target.value)} />
            </Field>
            <Field orientation="horizontal">
              <Switch id="role-wildcards" checked={allowwildcardsubs} onCheckedChange={setAllowwildcardsubs} />
              <FieldContent>
                <FieldLabel htmlFor="role-wildcards">{t("roles.allowWildcards")}</FieldLabel>
                <FieldDescription>{t("roles.allowWildcardsHint")}</FieldDescription>
              </FieldContent>
            </Field>
            {granting && (
              <Field>
                <FieldLabel htmlFor="role-topics">{t("roles.grantTopics")}</FieldLabel>
                <ChipsInput id="role-topics" className="font-mono" placeholder="home/livingroom/temperature" value={topics} onChange={setTopics} />
                <div className="flex flex-wrap gap-x-4 gap-y-2">
                  {grantTypes.map((type) => (
                    <Field key={type} orientation="horizontal" className="w-auto">
                      <Checkbox
                        id={`role-grant-${type}`}
                        checked={grants.includes(type)}
                        onCheckedChange={(checked) => setGrants((current) => (checked === true ? [...current, type] : current.filter((g) => g !== type)))}
                      />
                      <FieldLabel htmlFor={`role-grant-${type}`} className="font-normal">
                        {t(`roles.aclTypes.${type}`)}
                      </FieldLabel>
                    </Field>
                  ))}
                </div>
                <FieldDescription>{t("roles.grantTopicsHint")}</FieldDescription>
              </Field>
            )}
          </FieldGroup>
          {/* name and description alone disconnect nobody, switching wildcards does */}
          {role && allowwildcardsubs !== role.allowwildcardsubs && <DisconnectHint count={affected.connected} anonymous={affected.anonymous} />}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={saving || (granting && topics.length > 0 && grants.length === 0)}>
              {saving && <Spinner />}
              {t("common.save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function AclDialog({ role, onClose }: { role: Role; onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const add = $api.useMutation("post", "/api/roles/{rolename}/acls")
  const affected = useRoleHolders(role.rolename)
  const remove = $api.useMutation("delete", "/api/roles/{rolename}/acls")

  const [acltype, setAcltype] = useState<string>(aclTypes[0])
  const [topic, setTopic] = useState("")
  const [priority, setPriority] = useState(0)
  const [allow, setAllow] = useState(true)

  const sorted = [...role.acls].sort((a, b) => (b.priority ?? 0) - (a.priority ?? 0) || a.topic.localeCompare(b.topic))

  async function submit(event: FormEvent) {
    event.preventDefault()
    try {
      // rules the role has already would fail, so Pub/Sub adds only what is missing
      const types = aclTypesOf(acltype, topic).filter((type) => acltype !== pubSub || !role.acls.some((acl) => acl.acltype === type && acl.topic === topic))
      for (const type of types) {
        await add.mutateAsync({ params: { path: { rolename: role.rolename } }, body: { acltype: type, topic, priority, allow } })
      }
      setTopic("")
    } catch (error) {
      errorToast(error)
    } finally {
      await queryClient.invalidateQueries({ queryKey: rolesKey })
    }
  }

  async function removeAcl(acl: Schemas["Acl"]) {
    try {
      await remove.mutateAsync({ params: { path: { rolename: role.rolename }, query: { acltype: acl.acltype, topic: acl.topic } } })
      await queryClient.invalidateQueries({ queryKey: rolesKey })
    } catch (error) {
      errorToast(error)
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{t("roles.aclsTitle", { name: role.rolename })}</DialogTitle>
          <DialogDescription>{role.locked ? t("common.lockedHint") : t("roles.aclsDesc")}</DialogDescription>
        </DialogHeader>
        {!role.locked && <DisconnectHint rules count={affected.connected} anonymous={affected.anonymous} />}
        {sorted.length === 0 ? (
          <p className="py-4 text-center text-sm text-muted-foreground">{t("roles.aclsEmpty")}</p>
        ) : (
          <div className="max-h-72 overflow-y-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("roles.aclType")}</TableHead>
                  <TableHead>{t("roles.topic")}</TableHead>
                  <TableHead>{t("roles.priority")}</TableHead>
                  <TableHead />
                  <TableHead className="w-10" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {sorted.map((acl) => (
                  <TableRow key={`${acl.acltype} ${acl.topic}`}>
                    <TableCell>{t(`roles.aclTypes.${acl.acltype as (typeof aclTypes)[number]}`, { defaultValue: acl.acltype })}</TableCell>
                    <TableCell className="font-mono text-xs">{acl.topic}</TableCell>
                    <TableCell>{acl.priority}</TableCell>
                    <TableCell>
                      <Badge variant={acl.allow ? "secondary" : "destructive"}>{acl.allow ? t("roles.allow") : t("roles.deny")}</Badge>
                    </TableCell>
                    <TableCell>
                      <Button variant="ghost" size="icon-sm" aria-label={t("common.delete")} disabled={role.locked} onClick={() => void removeAcl(acl)}>
                        <Trash2Icon />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
        {!role.locked && (
          <form onSubmit={submit} className="grid items-end gap-3 sm:grid-cols-[1fr_1fr_5rem_auto]">
            <Field>
              <FieldLabel>{t("roles.aclType")}</FieldLabel>
              <Select value={acltype} onValueChange={setAcltype}>
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={pubSub}>{t("roles.pubSub")}</SelectItem>
                  {aclTypes.map((type) => (
                    <SelectItem key={type} value={type}>
                      {t(`roles.aclTypes.${type}`)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <Field>
              <FieldLabel htmlFor="acl-topic">{t("roles.topic")}</FieldLabel>
              <Input id="acl-topic" required className="font-mono" placeholder="sensors/+/state" value={topic} onChange={(e) => setTopic(e.target.value)} />
            </Field>
            <Field>
              <FieldLabel htmlFor="acl-priority">{t("roles.priority")}</FieldLabel>
              <Input id="acl-priority" type="number" value={priority} onChange={(e) => setPriority(Number(e.target.value))} />
            </Field>
            <div className="flex items-center gap-2">
              <Select value={allow ? "allow" : "deny"} onValueChange={(effect) => setAllow(effect === "allow")}>
                <SelectTrigger aria-label={t("roles.effect")} className="w-32">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="allow">{t("roles.allow")}</SelectItem>
                  <SelectItem value="deny">{t("roles.deny")}</SelectItem>
                </SelectContent>
              </Select>
              <Button type="submit" disabled={add.isPending}>
                {add.isPending ? <Spinner /> : <PlusIcon />}
                {t("common.add")}
              </Button>
            </div>
          </form>
        )}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>
            {t("common.close")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
