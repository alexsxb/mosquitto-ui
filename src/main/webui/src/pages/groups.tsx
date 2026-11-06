import { useQueryClient } from "@tanstack/react-query"
import { LockIcon, PencilIcon, PlusIcon, Trash2Icon } from "lucide-react"
import { useMemo, useState, type FormEvent } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"

import { $api, type Schemas } from "@/api/client"
import { ConfirmDialog } from "@/components/confirm-dialog"
import { BadgeList } from "@/components/badge-list"
import { MultiSelect } from "@/components/multi-select"
import { PageToolbar } from "@/components/page-toolbar"
import { SortableHead } from "@/components/sortable-head"
import { TablePagination } from "@/components/table-pagination"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import { Spinner } from "@/components/ui/spinner"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Textarea } from "@/components/ui/textarea"
import { useErrorToast } from "@/lib/errors"
import { useTable } from "@/lib/use-table"

type Group = Schemas["GroupView"]

const groupsKey = ["get", "/api/groups"]
const columns = {
  groupname: (group: Group) => group.groupname,
  roles: (group: Group) => group.roles.length,
  clients: (group: Group) => group.clients.length,
}

export function GroupsPage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const groups = $api.useQuery("get", "/api/groups")
  const deleteGroup = $api.useMutation("delete", "/api/groups/{groupname}")

  const [search, setSearch] = useState("")
  const [editing, setEditing] = useState<Group | "new" | null>(null)
  const [deleting, setDeleting] = useState<Group | null>(null)

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase()
    return (groups.data ?? []).filter((g) => [g.groupname, g.textname, ...g.roles, ...g.clients].some((v) => v?.toLowerCase().includes(term)))
  }, [groups.data, search])
  const table = useTable("groups", filtered, columns, "groupname")

  async function confirmDelete() {
    if (!deleting) {
      return
    }
    try {
      await deleteGroup.mutateAsync({ params: { path: { groupname: deleting.groupname } } })
      toast.success(t("groups.deleted", { name: deleting.groupname }))
      await queryClient.invalidateQueries({ queryKey: groupsKey })
    } catch (error) {
      errorToast(error)
    } finally {
      setDeleting(null)
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
        placeholder={t("groups.search")}
        count={groups.data && t("common.shown", { shown: filtered.length, total: groups.data.length })}
        refresh={[groups]}
      >
        <Button onClick={() => setEditing("new")}>
          <PlusIcon />
          {t("groups.add")}
        </Button>
      </PageToolbar>

      {groups.isPending ? (
        <Skeleton className="h-40 w-full" />
      ) : filtered.length === 0 ? (
        <p className="py-8 text-center text-sm text-muted-foreground">{t("groups.empty")}</p>
      ) : (
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <SortableHead active={table.sort === "groupname"} direction={table.direction} onSort={() => table.toggle("groupname")}>
                  {t("groups.groupname")}
                </SortableHead>
                <SortableHead active={table.sort === "roles"} direction={table.direction} onSort={() => table.toggle("roles")}>
                  {t("nav.roles")}
                </SortableHead>
                <SortableHead className="hidden md:table-cell" active={table.sort === "clients"} direction={table.direction} onSort={() => table.toggle("clients")}>
                  {t("nav.clients")}
                </SortableHead>
                <TableHead className="w-20" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {table.rows.map((group) => (
                <TableRow key={group.groupname}>
                  <TableCell className="font-medium">
                    {group.groupname}
                    {group.locked && <LockIcon className="ml-2 inline size-3 text-muted-foreground" aria-label={t("common.locked")} />}
                    {group.anonymous && (
                      <Badge variant="outline" className="ml-2" title={t("groups.anonymousHint")}>
                        {t("groups.anonymous")}
                      </Badge>
                    )}
                    {group.textname && <span className="ml-2 font-normal text-muted-foreground">{group.textname}</span>}
                  </TableCell>
                  <TableCell>
                    <BadgeList names={group.roles} />
                  </TableCell>
                  <TableCell className="hidden md:table-cell" title={group.clients.join(", ")}>
                    {group.clients.length}
                  </TableCell>
                  <TableCell>
                    <div className="flex justify-end">
                      <Button variant="ghost" size="icon-sm" aria-label={t("common.edit")} disabled={group.locked} onClick={() => setEditing(group)}>
                        <PencilIcon />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label={t("common.delete")}
                        title={group.anonymous ? t("groups.anonymousHint") : undefined}
                        disabled={group.locked || group.anonymous}
                        onClick={() => setDeleting(group)}
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
      <TablePagination page={table.page} pageSize={table.pageSize} total={table.total} onPage={table.setPage} onPageSize={table.setPageSize} allowAll />

      {editing !== null && <GroupDialog group={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={t("groups.deleteTitle", { name: deleting?.groupname })}
        description={t("groups.deleteDesc")}
        onConfirm={() => void confirmDelete()}
      />
    </>
  )
}

function GroupDialog({ group, onClose }: { group: Group | null; onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const allRoles = $api.useQuery("get", "/api/roles")
  const allClients = $api.useQuery("get", "/api/clients")
  const create = $api.useMutation("post", "/api/groups")
  const update = $api.useMutation("put", "/api/groups/{groupname}")

  const [groupname, setGroupname] = useState(group?.groupname ?? "")
  const [textname, setTextname] = useState(group?.textname ?? "")
  const [textdescription, setTextdescription] = useState(group?.textdescription ?? "")
  const [roles, setRoles] = useState(group?.roles ?? [])
  const [clients, setClients] = useState(group?.clients ?? [])
  // the clients of this application cannot change their groups
  const clientNames = (allClients.data ?? []).filter((client) => !client.locked).map((client) => client.username)

  async function submit(event: FormEvent) {
    event.preventDefault()
    try {
      if (group) {
        await update.mutateAsync({ params: { path: { groupname: group.groupname } }, body: { textname, textdescription, roles, clients } })
      } else {
        await create.mutateAsync({ body: { groupname, textname, textdescription, roles, clients } })
      }
      toast.success(t("groups.saved"))
      await queryClient.invalidateQueries({ queryKey: groupsKey })
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
            <DialogTitle>{group ? t("groups.editTitle", { name: group.groupname }) : t("groups.addTitle")}</DialogTitle>
          </DialogHeader>
          <FieldGroup>
            {!group && (
              <Field>
                <FieldLabel htmlFor="group-groupname">{t("groups.groupname")}</FieldLabel>
                <Input id="group-groupname" required autoComplete="off" pattern="[^\/]+" value={groupname} onChange={(e) => setGroupname(e.target.value)} />
              </Field>
            )}
            <Field>
              <FieldLabel htmlFor="group-textname">{t("common.name")}</FieldLabel>
              <Input id="group-textname" value={textname} onChange={(e) => setTextname(e.target.value)} />
            </Field>
            <Field>
              <FieldLabel htmlFor="group-textdescription">{t("common.description")}</FieldLabel>
              <Textarea id="group-textdescription" rows={2} value={textdescription} onChange={(e) => setTextdescription(e.target.value)} />
            </Field>
            <Field>
              <FieldLabel>{t("nav.roles")}</FieldLabel>
              <MultiSelect idPrefix="group-role" label={t("nav.roles")} names={allRoles.data?.map((r) => r.rolename) ?? []} selected={roles} onChange={setRoles} />
            </Field>
            <Field>
              <FieldLabel>{t("nav.clients")}</FieldLabel>
              <MultiSelect idPrefix="group-client" label={t("nav.clients")} names={[...new Set([...clientNames, ...clients])]} selected={clients} onChange={setClients} />
              <FieldDescription>{t("groups.membersHint")}</FieldDescription>
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
