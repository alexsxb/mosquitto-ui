/** The options of a bridge that have a field, in the order they are written. Passwords stay out: a field would show them. */
const FIELDS = [
  "bridge_cafile",
  "bridge_capath",
  "bridge_certfile",
  "bridge_keyfile",
  "bridge_insecure",
  "bridge_tls_version",
  "round_robin",
  "idle_timeout",
  "threshold",
  "notifications",
  "notifications_local_only",
  "notification_topic",
]

export type BridgeOptions = Record<string, string>

/** A repeated option stays a line: which one counts is up to the broker, and nothing may get lost here. */
export function splitOptions(lines: string[]) {
  const fields: BridgeOptions = {}
  const others: string[] = []
  for (const line of lines) {
    const [key, ...value] = line.trim().split(/\s+/)
    if (FIELDS.includes(key) && !(key in fields)) {
      fields[key] = value.join(" ")
    } else if (line.trim() !== "") {
      others.push(line)
    }
  }
  return { fields, others }
}

export function joinOptions(fields: BridgeOptions, others: string) {
  const known = FIELDS.filter((key) => fields[key]).map((key) => `${key} ${fields[key]}`)
  return [...known, ...others.split("\n").filter((line) => line.trim() !== "")]
}
