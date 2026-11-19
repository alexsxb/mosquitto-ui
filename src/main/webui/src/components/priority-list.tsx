import { XIcon } from "lucide-react"
import { useState } from "react"
import { useTranslation } from "react-i18next"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { priorityOf } from "@/lib/priorities"

/** Chosen roles or groups with their priorities; the broker checks the higher one first. */
export function PriorityList({
  idPrefix,
  names,
  priorities,
  onPriorities,
  onRemove,
}: {
  idPrefix: string
  names: string[]
  priorities: Record<string, number>
  onPriorities: (priorities: Record<string, number>) => void
  onRemove: (role: string) => void
}) {
  const { t } = useTranslation()
  if (names.length === 0) {
    return null
  }
  return (
    <ul className="divide-y rounded-lg border">
      {names.map((role) => (
        <li key={role} className="flex items-center gap-2 py-1 pr-1 pl-3">
          <span className="min-w-0 flex-1 truncate text-sm" title={role}>
            {role}
          </span>
          <label htmlFor={`${idPrefix}-${role}`} className="text-xs text-muted-foreground">
            {t("roles.priority")}
          </label>
          <PriorityInput id={`${idPrefix}-${role}`} value={priorityOf(priorities, role)} onChange={(value) => onPriorities({ ...priorities, [role]: value })} />
          <Button type="button" variant="ghost" size="icon-sm" aria-label={t("common.removeItem", { name: role })} onClick={() => onRemove(role)}>
            <XIcon />
          </Button>
        </li>
      ))}
    </ul>
  )
}

/** A whole number that may be typed through "-" or an empty field; what is not one goes back to the value on leaving. */
function PriorityInput({ id, value, onChange }: { id: string; value: number; onChange: (value: number) => void }) {
  const [draft, setDraft] = useState<string | null>(null)
  return (
    <Input
      id={id}
      type="number"
      step={1}
      className="h-7 w-20"
      value={draft ?? String(value)}
      onChange={(e) => {
        setDraft(e.target.value)
        if (/^-?\d+$/.test(e.target.value)) {
          onChange(Number.parseInt(e.target.value, 10))
        }
      }}
      onBlur={() => setDraft(null)}
    />
  )
}
