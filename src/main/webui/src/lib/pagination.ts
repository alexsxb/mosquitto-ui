import { useState } from "react"

/** Choosable page sizes; 0 shows all rows. */
export const pageSizes = [25, 50, 100, 200]
export const ALL = 0

function storageKey(table: string) {
  return `mosquitto-ui.pageSize.${table}`
}

/** Page size of a table, remembered per table in this browser. */
export function usePageSize(table: string, fallback = 50): [number, (size: number) => void] {
  const [size, setSize] = useState(() => {
    try {
      const stored = localStorage.getItem(storageKey(table))
      const size = Number(stored)
      return stored !== null && (size === ALL || pageSizes.includes(size)) ? size : fallback
    } catch {
      return fallback
    }
  })
  function change(next: number) {
    setSize(next)
    try {
      localStorage.setItem(storageKey(table), String(next))
    } catch {
      // Not remembered, e.g. in a private window
    }
  }
  return [size, change]
}

/** Number of pages, at least one. */
export function pageCount(total: number, pageSize: number) {
  return pageSize === ALL ? 1 : Math.max(1, Math.ceil(total / pageSize))
}

/** The rows of a page; a page beyond the end shows the last one. */
export function pageOf<T>(items: T[], page: number, pageSize: number): T[] {
  if (pageSize === ALL) return items
  const current = Math.min(page, pageCount(items.length, pageSize) - 1)
  return items.slice(current * pageSize, (current + 1) * pageSize)
}
