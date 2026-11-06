import { ArrowDownIcon, ArrowUpIcon, ChevronsUpDownIcon } from "lucide-react"
import type { ReactNode } from "react"

import { TableHead } from "@/components/ui/table"
import type { SortDirection } from "@/lib/use-table"

/** Column head that sorts by its column. */
export function SortableHead({ active, direction, onSort, className, children }: { active: boolean; direction: SortDirection; onSort: () => void; className?: string; children: ReactNode }) {
  const Icon = !active ? ChevronsUpDownIcon : direction === "asc" ? ArrowUpIcon : ArrowDownIcon
  return (
    <TableHead className={className} aria-sort={active ? (direction === "asc" ? "ascending" : "descending") : "none"}>
      <button type="button" className="inline-flex items-center gap-1 font-medium hover:text-foreground" onClick={onSort}>
        {children}
        <Icon className={active ? "size-3.5" : "size-3.5 text-muted-foreground"} />
      </button>
    </TableHead>
  )
}
