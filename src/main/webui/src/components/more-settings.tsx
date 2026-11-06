import { ChevronRightIcon } from "lucide-react"
import type { ReactNode } from "react"

import { cn } from "@/lib/utils"

/** Settings most people leave alone, closed until someone needs them. */
export function MoreSettings({ label, open, className, children }: { label: string; open?: boolean; className?: string; children: ReactNode }) {
  return (
    <details open={open} className={cn("group", className)}>
      <summary className="flex w-fit cursor-pointer list-none items-center gap-1 text-sm font-medium [&::-webkit-details-marker]:hidden">
        <ChevronRightIcon className="size-4 transition-transform group-open:rotate-90" />
        {label}
      </summary>
      <div className="pt-3">{children}</div>
    </details>
  )
}
