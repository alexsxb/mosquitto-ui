import { useMemo, useState } from "react"

import { pageOf, usePageSize } from "@/lib/pagination"

export type SortDirection = "asc" | "desc"

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: "base" })

/** Natural order, so that sensor-2 comes before sensor-10; numbers by size. */
function compare(a: string | number, b: string | number) {
  return typeof a === "number" && typeof b === "number" ? a - b : collator.compare(String(a), String(b))
}

/**
 * Sorting and paging of rows that are completely loaded, for a table with SortableHead and TablePagination.
 *
 * @param columns what each sortable column sorts by
 */
export function useTable<T, K extends string>(table: string, rows: T[], columns: Record<K, (row: T) => string | number>, initial: NoInfer<K>, initialDirection: SortDirection = "asc") {
  const [sort, setSort] = useState<K>(initial)
  const [direction, setDirection] = useState(initialDirection)
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = usePageSize(table, 25)

  const sorted = useMemo(() => {
    const value = columns[sort]
    const factor = direction === "asc" ? 1 : -1
    return [...rows].sort((a, b) => factor * compare(value(a), value(b)))
    // the columns are constants of the page
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, sort, direction])

  /** The same column again turns the order around; a new one starts ascending, numbers with the largest. */
  function toggle(column: K) {
    if (column === sort) {
      setDirection(direction === "asc" ? "desc" : "asc")
    } else {
      setSort(column)
      setDirection(rows.length > 0 && typeof columns[column](rows[0]) === "number" ? "desc" : "asc")
    }
    setPage(0)
  }

  return { rows: pageOf(sorted, page, pageSize), sort, direction, toggle, page, setPage, pageSize, setPageSize, total: rows.length }
}
