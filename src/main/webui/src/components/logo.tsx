import { cn } from "@/lib/utils"

/** Logo: a publishing client with its signal. Dark tile in the light theme, light tile in the dark theme; same drawing as public/favicon.svg. */
export function Logo({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 64 64" aria-hidden="true" className={cn("size-8 shrink-0", className)}>
      <rect width="64" height="64" rx="14" className="fill-foreground" />
      <circle cx="20" cy="44" r="6" className="fill-background" />
      <path d="M18 28a18 18 0 0 1 18 18" fill="none" strokeWidth="6" strokeLinecap="round" className="stroke-background" />
      <path d="M18 14a32 32 0 0 1 32 32" fill="none" strokeWidth="6" strokeLinecap="round" className="stroke-background" />
    </svg>
  )
}
