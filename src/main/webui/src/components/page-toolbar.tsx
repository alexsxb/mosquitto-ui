import type { ComponentProps, ReactNode } from "react"

import { RefreshButton } from "@/components/refresh-button"
import { SearchInput } from "@/components/search-input"

/** Search field and refresh on the left, actions on the right. */
export function PageToolbar({
  search,
  onSearch,
  placeholder,
  count,
  refresh,
  children,
}: {
  search?: string
  onSearch?: (value: string) => void
  placeholder?: string
  /** Text about the shown rows, next to the search */
  count?: string
  /** Queries behind the page, which a button loads again */
  refresh?: ComponentProps<typeof RefreshButton>["queries"]
  children?: ReactNode
}) {
  return (
    <div className="mb-4 flex flex-wrap items-center gap-2">
      {onSearch && (
        <SearchInput className="max-w-xs" value={search ?? ""} placeholder={placeholder} onValueChange={onSearch} />
      )}
      {count && <span className="text-sm text-muted-foreground">{count}</span>}
      {refresh && <RefreshButton queries={refresh} />}
      <div className="ml-auto flex flex-wrap items-center justify-end gap-2">{children}</div>
    </div>
  )
}
