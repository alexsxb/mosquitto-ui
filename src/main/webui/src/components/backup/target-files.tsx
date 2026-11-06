import { DownloadIcon, Trash2Icon } from "lucide-react"
import { useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"

import { $api, type Schemas } from "@/api/client"
import type { Target } from "@/components/backup/targets"
import { ConfirmDialog } from "@/components/confirm-dialog"
import { RefreshButton } from "@/components/refresh-button"
import { SortableHead } from "@/components/sortable-head"
import { TablePagination } from "@/components/table-pagination"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Skeleton } from "@/components/ui/skeleton"
import { Table, TableBody, TableCell, TableHeader, TableRow, TableHead } from "@/components/ui/table"
import { useErrorMessage, useErrorToast } from "@/lib/errors"
import { formatBytes, formatDateTime } from "@/lib/format"
import { useTable } from "@/lib/use-table"

type TargetFile = Schemas["TargetFile"]

const NONE: TargetFile[] = []
const columns = {
  name: (file: TargetFile) => file.name,
  size: (file: TargetFile) => file.size ?? -1,
  modified: (file: TargetFile) => (file.modified ? Date.parse(file.modified) : 0),
}

/** What is on a target, also what does not come from this application. */
export function TargetFilesDialog({ target, onClose }: { target: Target; onClose: () => void }) {
  const { t, i18n } = useTranslation()
  const errorToast = useErrorToast()
  const errorMessage = useErrorMessage()
  const params = { params: { path: { id: target.id } } }
  const files = $api.useQuery("get", "/api/backup/targets/{id}/files", params, { retry: false })
  const remove = $api.useMutation("delete", "/api/backup/targets/{id}/files/{name}")
  const [deleting, setDeleting] = useState<TargetFile | null>(null)
  const rows = files.data ?? NONE
  const table = useTable("backup-files", rows, columns, "modified", "desc")
  const bytes = rows.reduce((sum, file) => sum + (file.size ?? 0), 0)

  async function confirmDelete() {
    if (!deleting) {
      return
    }
    try {
      await remove.mutateAsync({ params: { path: { id: target.id, name: deleting.name } } })
      toast.success(t("backups.files.deleted", { name: deleting.name }))
      await files.refetch()
    } catch (error) {
      errorToast(error)
    } finally {
      setDeleting(null)
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>{t("backups.files.title", { name: target.name })}</DialogTitle>
        </DialogHeader>
        <div className="flex min-h-9 items-center justify-between gap-2">
          <span className="text-sm text-muted-foreground">
            {files.data && files.data.length > 0 && t("backups.files.summary", { count: files.data.length, size: formatBytes(bytes, i18n.language) })}
          </span>
          <RefreshButton queries={[files]} />
        </div>
        {files.isPending && <Skeleton className="h-40 w-full" />}
        {files.isError && (
          <p className="rounded-lg border p-6 text-center text-sm text-destructive">
            {t("backups.files.failed")}: {errorMessage(files.error)}
          </p>
        )}
        {files.data?.length === 0 && <p className="rounded-lg border p-6 text-center text-sm text-muted-foreground">{t("backups.files.empty")}</p>}
        {rows.length > 0 && (
          <div className="max-h-[60svh] overflow-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <SortableHead active={table.sort === "name"} direction={table.direction} onSort={() => table.toggle("name")}>
                    {t("backups.files.name")}
                  </SortableHead>
                  <SortableHead active={table.sort === "size"} direction={table.direction} onSort={() => table.toggle("size")}>
                    {t("backups.files.size")}
                  </SortableHead>
                  <SortableHead active={table.sort === "modified"} direction={table.direction} onSort={() => table.toggle("modified")}>
                    {t("backups.files.modified")}
                  </SortableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {table.rows.map((file) => (
                  <TableRow key={file.name}>
                    <TableCell className="max-w-xs whitespace-normal">
                      <span className="font-mono text-xs break-all">{file.name}</span>
                      {!file.own && (
                        <Badge variant="outline" className="ml-2">
                          {t("backups.files.foreign")}
                        </Badge>
                      )}
                    </TableCell>
                    <TableCell className="text-sm whitespace-nowrap tabular-nums">{formatBytes(file.size, i18n.language)}</TableCell>
                    <TableCell className="text-sm whitespace-nowrap">{formatDateTime(file.modified, i18n.language)}</TableCell>
                    <TableCell>
                      <div className="flex justify-end">
                        <Button asChild variant="ghost" size="icon-sm">
                          <a href={`/api/backup/targets/${target.id}/files/${encodeURIComponent(file.name)}`} download aria-label={t("backups.files.download")} title={t("backups.files.download")}>
                            <DownloadIcon />
                          </a>
                        </Button>
                        <Button variant="ghost" size="icon-sm" aria-label={t("common.delete")} title={t("common.delete")} onClick={() => setDeleting(file)}>
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
        <ConfirmDialog
          open={deleting !== null}
          onOpenChange={(open) => !open && setDeleting(null)}
          title={t("backups.files.deleteTitle", { name: deleting?.name })}
          description={t(deleting?.own === false ? "backups.files.deleteForeign" : "backups.files.deleteDesc")}
          onConfirm={() => void confirmDelete()}
        />
      </DialogContent>
    </Dialog>
  )
}
