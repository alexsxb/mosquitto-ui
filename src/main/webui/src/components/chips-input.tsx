import { XIcon } from "lucide-react"
import { useState, type KeyboardEvent } from "react"

import { cn } from "@/lib/utils"

/**
 * A list of short values (e-mail addresses, host names) as chips: Enter, comma, semicolon, space or leaving the field
 * turns the typed text into a chip, × or Backspace in the empty field removes one.
 */
export function ChipsInput({
  id,
  value,
  onChange,
  placeholder,
  className,
}: {
  id?: string
  value: string[]
  onChange: (value: string[]) => void
  placeholder?: string
  className?: string
}) {
  const [draft, setDraft] = useState("")

  function commit(text: string) {
    const added = text
      .split(/[,;\s]+/)
      .map((s) => s.trim())
      .filter((s) => s.length > 0 && !value.includes(s))
    if (added.length > 0) {
      onChange([...value, ...new Set(added)])
    }
    setDraft("")
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Enter" || event.key === "," || event.key === ";" || event.key === " ") {
      if (draft.trim().length > 0) {
        event.preventDefault()
        commit(draft)
      } else if (event.key !== "Enter") {
        event.preventDefault()
      }
    } else if (event.key === "Backspace" && draft.length === 0 && value.length > 0) {
      onChange(value.slice(0, -1))
    }
  }

  return (
    <div
      className={cn(
        "flex min-h-9 w-full flex-wrap items-center gap-1 rounded-md border border-input bg-transparent px-2 py-1 text-sm shadow-xs dark:bg-input/30",
        "focus-within:border-ring focus-within:ring-[3px] focus-within:ring-ring/50",
        className,
      )}
    >
      {value.map((item) => (
        <span key={item} className="chip inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-xs">
          {item}
          <button
            type="button"
            className="rounded-sm opacity-60 hover:opacity-100"
            aria-label={`${item} ×`}
            onClick={() => onChange(value.filter((v) => v !== item))}
          >
            <XIcon className="size-3" />
          </button>
        </span>
      ))}
      <input
        id={id}
        className="min-w-32 flex-1 bg-transparent py-0.5 outline-none placeholder:text-muted-foreground"
        value={draft}
        placeholder={value.length === 0 ? placeholder : undefined}
        onChange={(e) => {
          const text = e.target.value
          // Pasted lists become chips at once
          if (/[,;\s]/.test(text) && text.trim().length > 0) {
            commit(text)
          } else {
            setDraft(text)
          }
        }}
        onKeyDown={onKeyDown}
        onBlur={() => draft.trim() && commit(draft)}
      />
    </div>
  )
}
