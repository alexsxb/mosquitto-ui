export function formatDateTime(value: string | null | undefined, language: string) {
  if (!value) {
    return "–"
  }
  return new Intl.DateTimeFormat(language, { dateStyle: "medium", timeStyle: "medium" }).format(new Date(value))
}

/** Without seconds, for lists. */
export function formatShort(value: string, language: string) {
  return new Intl.DateTimeFormat(language, { dateStyle: "short", timeStyle: "short" }).format(new Date(value))
}

const SIZE_UNITS = ["B", "KB", "MB", "GB", "TB"]

export function formatBytes(bytes: number | null | undefined, language: string) {
  if (bytes == null) {
    return "–"
  }
  const unit = bytes === 0 ? 0 : Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), SIZE_UNITS.length - 1)
  const value = bytes / 1024 ** unit
  return `${new Intl.NumberFormat(language, { maximumFractionDigits: unit === 0 || value >= 100 ? 0 : 1 }).format(value)} ${SIZE_UNITS[unit]}`
}
