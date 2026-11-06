import { useTranslation } from "react-i18next"

import { Badge } from "@/components/ui/badge"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"

const SHOWN = 2

/** The first names and "+3" for the others, so that a long list does not make its row high. */
export function BadgeList({ names }: { names: string[] }) {
  const { t } = useTranslation()
  if (names.length === 0) {
    return <span className="text-muted-foreground">{t("common.none")}</span>
  }
  const rest = names.slice(SHOWN)
  return (
    <div className="flex items-center gap-1">
      {names.slice(0, SHOWN).map((name) => (
        <Badge key={name} variant="secondary" className="max-w-40 truncate" title={name}>
          {name}
        </Badge>
      ))}
      {rest.length > 0 && (
        <Popover>
          <PopoverTrigger asChild>
            <button type="button" aria-label={t("common.showAll", { count: names.length })}>
              <Badge variant="outline">+{rest.length}</Badge>
            </button>
          </PopoverTrigger>
          <PopoverContent className="flex max-h-64 w-72 flex-wrap gap-1 overflow-y-auto">
            {names.map((name) => (
              <Badge key={name} variant="secondary">
                {name}
              </Badge>
            ))}
          </PopoverContent>
        </Popover>
      )}
    </div>
  )
}
