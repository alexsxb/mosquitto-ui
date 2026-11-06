import { useMemo, useState } from "react"
import { useTranslation } from "react-i18next"

import { $api, type Schemas } from "@/api/client"
import { PageToolbar } from "@/components/page-toolbar"
import { SortableHead } from "@/components/sortable-head"
import { TablePagination } from "@/components/table-pagination"
import { Badge } from "@/components/ui/badge"
import { Skeleton } from "@/components/ui/skeleton"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { formatDateTime } from "@/lib/format"
import { useTable } from "@/lib/use-table"

type Entry = Schemas["AuditEntry"]

const columns = {
  time: (entry: Entry) => entry.time,
  user: (entry: Entry) => entry.user,
}

function detailsOf(entry: Entry) {
  return Object.entries(entry.details ?? {})
    .map(([key, value]) => `${key}: ${value}`)
    .join(", ")
}

export function AuditPage() {
  const { t, i18n } = useTranslation()
  const audit = $api.useQuery("get", "/api/audit")
  const [search, setSearch] = useState("")
  /** A new action on an older page has no translation yet: its name still says what happened. */
  const actionOf = (entry: Entry) => t(`audit.actions.${entry.action}` as never, { defaultValue: entry.action })

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase()
    return (audit.data ?? []).filter((entry) => [entry.user, entry.action, actionOf(entry), detailsOf(entry)].some((value) => value.toLowerCase().includes(term)))
    // the translation only changes with the language
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [audit.data, search, i18n.language])
  const table = useTable("audit", filtered, columns, "time", "desc")

  return (
    <>
      <PageToolbar
        search={search}
        onSearch={(value) => {
          setSearch(value)
          table.setPage(0)
        }}
        placeholder={t("audit.search")}
        count={audit.data && t("common.shown", { shown: filtered.length, total: audit.data.length })}
        refresh={[audit]}
      />
      <p className="text-sm text-muted-foreground">{t("audit.hint")}</p>

      {audit.isPending ? (
        <Skeleton className="h-40 w-full" />
      ) : filtered.length === 0 ? (
        <p className="py-8 text-center text-sm text-muted-foreground">{t("audit.empty")}</p>
      ) : (
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <SortableHead active={table.sort === "time"} direction={table.direction} onSort={() => table.toggle("time")}>
                  {t("audit.time")}
                </SortableHead>
                <SortableHead active={table.sort === "user"} direction={table.direction} onSort={() => table.toggle("user")}>
                  {t("audit.user")}
                </SortableHead>
                <TableHead>{t("audit.action")}</TableHead>
                <TableHead className="hidden md:table-cell">{t("audit.details")}</TableHead>
                <TableHead>{t("audit.result")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {table.rows.map((entry, index) => (
                <TableRow key={`${entry.time}-${index}`}>
                  <TableCell className="whitespace-nowrap tabular-nums">{formatDateTime(entry.time, i18n.language)}</TableCell>
                  <TableCell>{entry.user}</TableCell>
                  <TableCell>{actionOf(entry)}</TableCell>
                  <TableCell className="hidden max-w-sm truncate font-mono text-xs md:table-cell" title={detailsOf(entry)}>
                    {detailsOf(entry)}
                  </TableCell>
                  <TableCell>
                    {entry.error ? (
                      <Badge variant="destructive" title={entry.error}>
                        {t("audit.failed")}
                      </Badge>
                    ) : (
                      <Badge variant="secondary">{t("audit.ok")}</Badge>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      <TablePagination page={table.page} pageSize={table.pageSize} total={table.total} onPage={table.setPage} onPageSize={table.setPageSize} allowAll />
    </>
  )
}
