import { ChevronDownIcon, XIcon } from "lucide-react"
import { useState } from "react"
import { useTranslation } from "react-i18next"

import { Badge } from "@/components/ui/badge"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"

/** From this many names on, the list can be searched. */
const SEARCH_FROM = 8

/** Choice of several names: a dropdown with checkboxes, what is chosen shows as chips that can be removed. */
export function MultiSelect({
  idPrefix,
  label,
  names,
  selected,
  onChange,
}: {
  idPrefix: string
  label: string
  names: string[]
  selected: string[]
  onChange: (selected: string[]) => void
}) {
  const { t } = useTranslation()
  const [search, setSearch] = useState("")
  const term = search.trim().toLowerCase()
  const shown = names.filter((name) => name.toLowerCase().includes(term))

  return (
    <div className="space-y-2">
      <Popover onOpenChange={() => setSearch("")}>
        <PopoverTrigger
          aria-label={label}
          className="flex h-9 w-full items-center justify-between rounded-lg border border-input bg-transparent px-3 text-sm text-muted-foreground shadow-xs outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
        >
          {selected.length > 0 ? t("common.selected", { count: selected.length }) : t("common.select")}
          <ChevronDownIcon className="size-4 opacity-50" />
        </PopoverTrigger>
        <PopoverContent>
          {names.length >= SEARCH_FROM && <Input aria-label={t("common.search")} placeholder={t("common.search")} className="mb-2" value={search} onChange={(e) => setSearch(e.target.value)} />}
          <div className="max-h-56 space-y-1 overflow-y-auto">
            {shown.length === 0 && <p className="p-2 text-sm text-muted-foreground">{t("common.noResults")}</p>}
            {shown.map((name) => (
              <div key={name} className="flex min-w-0 items-center gap-2 rounded-md p-1.5 hover:bg-muted">
                <Checkbox
                  id={`${idPrefix}-${name}`}
                  checked={selected.includes(name)}
                  onCheckedChange={(checked) => onChange(checked ? [...selected, name] : selected.filter((n) => n !== name))}
                />
                <Label htmlFor={`${idPrefix}-${name}`} className="min-w-0 flex-1 truncate font-normal">
                  {name}
                </Label>
              </div>
            ))}
          </div>
        </PopoverContent>
      </Popover>
      {selected.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {selected.map((name) => (
            <Badge key={name} variant="outline" className="chip max-w-full gap-1 pr-1">
              <span className="truncate">{name}</span>
              <button
                type="button"
                aria-label={t("common.removeItem", { name })}
                className="rounded-sm opacity-60 hover:opacity-100"
                onClick={() => onChange(selected.filter((n) => n !== name))}
              >
                <XIcon className="size-3" />
              </button>
            </Badge>
          ))}
        </div>
      )}
    </div>
  )
}
