import { ChevronLeftIcon, ChevronRightIcon, ChevronsLeftIcon, ChevronsRightIcon } from "lucide-react"
import { useTranslation } from "react-i18next"

import { Button } from "@/components/ui/button"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { ALL, pageCount, pageSizes } from "@/lib/pagination"

interface TablePaginationProps {
  page: number
  pageSize: number
  total: number
  onPage: (page: number) => void
  onPageSize: (size: number) => void
  /** Offer "all" as page size; only for tables loaded completely. */
  allowAll?: boolean
}

/** Range of shown rows, page size and page buttons below a table; hidden while all rows fit on the smallest page. */
export function TablePagination({ page, pageSize, total, onPage, onPageSize, allowAll = false }: TablePaginationProps) {
  const { t } = useTranslation()
  if (total <= pageSizes[0]) return null
  const pages = pageCount(total, pageSize)
  const current = Math.min(page, pages - 1)
  const from = pageSize === ALL ? 1 : current * pageSize + 1
  const to = pageSize === ALL ? total : Math.min(total, (current + 1) * pageSize)

  function changeSize(value: string) {
    const size = Number(value)
    onPageSize(size)
    // Keep the first shown row on the new page
    onPage(size === ALL ? 0 : Math.floor((from - 1) / size))
  }

  return (
    <div className="mt-3 flex flex-wrap items-center justify-between gap-x-4 gap-y-2 text-sm">
      <span className="text-muted-foreground tabular-nums">{t("pagination.range", { from, to, total })}</span>
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-muted-foreground">{t("pagination.rows")}</span>
        <Select value={String(pageSize)} onValueChange={changeSize}>
          <SelectTrigger size="sm" className="w-24" aria-label={t("pagination.rows")}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {pageSizes.map((size) => (
              <SelectItem key={size} value={String(size)}>
                {size}
              </SelectItem>
            ))}
            {allowAll && <SelectItem value={String(ALL)}>{t("pagination.all")}</SelectItem>}
          </SelectContent>
        </Select>
        {pages > 1 && (
          <>
            <Button variant="outline" size="icon-sm" aria-label={t("pagination.first")} disabled={current === 0} onClick={() => onPage(0)}>
              <ChevronsLeftIcon />
            </Button>
            <Button variant="outline" size="icon-sm" aria-label={t("pagination.previous")} disabled={current === 0} onClick={() => onPage(current - 1)}>
              <ChevronLeftIcon />
            </Button>
            <span className="px-1 tabular-nums">{t("pagination.page", { page: current + 1, pages })}</span>
            <Button variant="outline" size="icon-sm" aria-label={t("pagination.next")} disabled={current + 1 >= pages} onClick={() => onPage(current + 1)}>
              <ChevronRightIcon />
            </Button>
            <Button variant="outline" size="icon-sm" aria-label={t("pagination.last")} disabled={current + 1 >= pages} onClick={() => onPage(pages - 1)}>
              <ChevronsRightIcon />
            </Button>
          </>
        )}
      </div>
    </div>
  )
}
