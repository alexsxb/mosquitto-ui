import { XIcon } from "lucide-react"
import type { ComponentProps } from "react"
import { useTranslation } from "react-i18next"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { cn } from "@/lib/utils"

/** Text field for a search, with an X that empties it; Escape empties it too. `className` sizes the field. */
export function SearchInput({
  value,
  onValueChange,
  className,
  onKeyDown,
  ...props
}: Omit<ComponentProps<"input">, "value" | "onChange" | "type"> & {
  value: string
  onValueChange: (value: string) => void
}) {
  const { t } = useTranslation()
  return (
    <div className={cn("relative w-full", className)}>
      <Input
        aria-label={props.placeholder}
        {...props}
        type="text"
        className="pr-8"
        value={value}
        onChange={(event) => onValueChange(event.target.value)}
        onKeyDown={(event) => {
          onKeyDown?.(event)
          // only when there is something to empty, so that an empty field still lets Escape close a dialog or popover
          if (event.key === "Escape" && value) {
            event.preventDefault()
            event.stopPropagation()
            onValueChange("")
          }
        }}
      />
      {value && (
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="absolute top-1/2 right-1 size-6 -translate-y-1/2 text-muted-foreground"
          aria-label={t("common.clearSearch")}
          title={t("common.clearSearch")}
          onClick={() => onValueChange("")}
        >
          <XIcon />
        </Button>
      )}
    </div>
  )
}
