/**
 * The rules of a role as text, one rule per line, to paste existing lists of topics and to edit all rules at once:
 *
 *     # a comment ("# " or "//" at the start; "home/#" alone is a topic)
 *     sensors/+/state                  the default actions
 *     pub      devices/door/cmd
 *     sub      home/#                  subscribePattern, as the topic has a wildcard
 *     deny pub home/secret prio 10
 *     topic read home/#                a line of a Mosquitto ACL file
 *
 * Keywords may stand anywhere in a line and are case-insensitive; what is left is the topic. A topic with spaces, commas
 * or semicolons, or one that reads like a keyword, goes in double quotes. Commas and semicolons separate rules within a
 * line, a rule without its own keywords takes them from the rule before it ("pub a, b" publishes on both).
 */

export const aclTypes = [
  "publishClientSend",
  "publishClientReceive",
  "subscribeLiteral",
  "subscribePattern",
  "unsubscribeLiteral",
  "unsubscribePattern",
] as const
export type AclType = (typeof aclTypes)[number]

export interface AclRule {
  acltype: string
  topic: string
  priority: number
  allow: boolean
}

export interface ParsedRule extends AclRule {
  /** 1-based line of the text the rule comes from. */
  line: number
}

export type TopicProblem = "hashNotLast" | "wildcardInLevel" | "nullChar" | "tooLong"

export type AclTextError =
  | { line: number; code: "noTopic" }
  | { line: number; code: "noAction" }
  | { line: number; code: "invalidTopic"; topic: string; reason: TopicProblem }
  | { line: number; code: "badPriority"; value: string }
  | { line: number; code: "unsupported"; keyword: string }
  /** The same action on the same topic with another priority or effect, in an earlier line. */
  | { line: number; code: "conflict"; other: number; acltype: string; topic: string }

export interface AclTextResult {
  rules: ParsedRule[]
  errors: AclTextError[]
}

/** Priority of "deny" lines from Mosquitto ACL files, as the importer gives them. */
const DENY_PRIORITY = 10
const MAX_TOPIC_BYTES = 65535

/** What lines without an action of their own can get, for a choice in the pages. */
export const defaultActions = ["sub", "pubsub", "pub", "recv"] as const

export function hasWildcard(topic: string) {
  return /[+#]/.test(topic)
}

/** Shorthands and the words of Mosquitto ACL files; sub and unsub take the pattern rule for topics with + or #. */
const shorthands: Record<string, (topic: string) => AclType[]> = {
  pub: () => ["publishClientSend"],
  recv: () => ["publishClientReceive"],
  sub: (topic) => [hasWildcard(topic) ? "subscribePattern" : "subscribeLiteral"],
  unsub: (topic) => [hasWildcard(topic) ? "unsubscribePattern" : "unsubscribeLiteral"],
  pubsub: (topic) => ["publishClientSend", "publishClientReceive", ...shorthands.sub(topic)],
  read: (topic) => ["publishClientReceive", ...shorthands.sub(topic)],
  write: () => ["publishClientSend"],
  readwrite: (topic) => shorthands.pubsub(topic),
}
const fullNames = new Map<string, AclType>(aclTypes.map((type) => [type.toLowerCase(), type]))
const effects = new Map([
  ["allow", true],
  ["deny", false],
])
const prioWords = new Set(["prio", "priority"])
const prioAssignment = /^(?:prio|priority)=(.*)$/i
/** Words that start lines of Mosquitto ACL files. */
const fileWords = new Set(["topic", "user", "pattern"])

function isAction(word: string) {
  return word in shorthands || fullNames.has(word)
}

/** The rule types behind an action keyword, a shorthand or a full name. */
export function expandAction(action: string, topic: string): AclType[] {
  const word = action.toLowerCase()
  const full = fullNames.get(word)
  if (full) {
    return [full]
  }
  return word in shorthands ? shorthands[word](topic) : []
}

/** Why the broker would refuse the topic of a rule, or null. */
export function topicProblem(topic: string): TopicProblem | null {
  if (topic.includes("\0")) {
    return "nullChar"
  }
  if (new TextEncoder().encode(topic).length > MAX_TOPIC_BYTES) {
    return "tooLong"
  }
  const levels = topic.split("/")
  for (let i = 0; i < levels.length; i++) {
    const level = levels[i]
    if ((level.includes("#") && level !== "#") || (level.includes("+") && level !== "+")) {
      return "wildcardInLevel"
    }
    if (level === "#" && i !== levels.length - 1) {
      return "hashNotLast"
    }
  }
  return null
}

interface Token {
  text: string
  quoted: boolean
}

/** Splits a line into rules at commas and semicolons and those into words, both outside of double quotes. */
function tokenize(line: string): Token[][] {
  const segments: Token[][] = [[]]
  let i = 0
  while (i < line.length) {
    const char = line[i]
    if (char === "," || char === ";") {
      segments.push([])
      i++
    } else if (/\s/.test(char)) {
      i++
    } else if (char === '"') {
      let text = ""
      i++
      while (i < line.length && line[i] !== '"') {
        if (line[i] === "\\" && i + 1 < line.length) {
          i++
        }
        text += line[i]
        i++
      }
      i++ // the closing quote, or the end of an unclosed one
      segments[segments.length - 1].push({ text, quoted: true })
    } else {
      let text = ""
      while (i < line.length && !/[\s,;"]/.test(line[i])) {
        text += line[i]
        i++
      }
      segments[segments.length - 1].push({ text, quoted: false })
    }
  }
  return segments.filter((segment) => segment.length > 0)
}

function isComment(line: string) {
  return line.startsWith("//") || /^#\s/.test(line)
}

/** "- a/b", "* a/b", "• a/b" and "1. a/b" as from lists in mails and documents. */
function stripBullet(line: string) {
  return line.replace(/^(?:[-*•]|\d+[.)])\s+/, "")
}

/**
 * The rules of a text. Lines without an action get the default actions: keywords as in the text, e.g. ["sub"] or
 * ["subscribeLiteral", "publishClientReceive"]. Rules that are in the text twice count once.
 */
export function parseAclText(text: string, defaults: readonly string[]): AclTextResult {
  const rules: ParsedRule[] = []
  const errors: AclTextError[] = []
  const seen = new Map<string, ParsedRule>()

  text.split(/\r?\n/).forEach((raw, index) => {
    const line = index + 1
    const trimmed = stripBullet(raw.trim())
    if (trimmed.length === 0 || isComment(trimmed)) {
      return
    }
    let inherited: { actions: string[]; allow: boolean | null; priority: number | null } = { actions: [], allow: null, priority: null }
    tokenize(trimmed).forEach((tokens, segmentIndex) => {
      let fileSyntax = false
      // "topic [read|write|readwrite|deny] x" of Mosquitto ACL files
      if (segmentIndex === 0 && tokens.length > 1 && !tokens[0].quoted && fileWords.has(tokens[0].text.toLowerCase())) {
        const word = tokens[0].text.toLowerCase()
        if (word !== "topic") {
          errors.push({ line, code: "unsupported", keyword: word })
          return
        }
        fileSyntax = true
        tokens = tokens.slice(1)
      }

      const actions: string[] = []
      const topicParts: string[] = []
      let allow: boolean | null = null
      let priority: number | null = null
      let badPriority: string | null = null
      for (let i = 0; i < tokens.length; i++) {
        const { text: word, quoted } = tokens[i]
        const lower = word.toLowerCase()
        if (quoted) {
          topicParts.push(word)
        } else if (isAction(lower)) {
          actions.push(lower)
        } else if (effects.has(lower)) {
          allow = effects.get(lower)!
        } else if (prioWords.has(lower) || prioAssignment.test(word)) {
          const value = prioWords.has(lower) ? tokens[++i]?.text : word.match(prioAssignment)![1]
          if (value !== undefined && /^-?\d+$/.test(value)) {
            priority = Number(value)
          } else {
            badPriority = value ?? ""
          }
        } else {
          topicParts.push(word)
        }
      }
      if (badPriority !== null) {
        errors.push({ line, code: "badPriority", value: badPriority })
        return
      }

      // without keywords of its own, a rule takes those of the rule before it in the same line
      if (actions.length === 0 && allow === null && priority === null) {
        actions.push(...inherited.actions)
        allow = inherited.allow
        priority = inherited.priority
      }
      inherited = { actions: [...actions], allow, priority }

      const topic = topicParts.join(" ")
      if (topic.length === 0) {
        errors.push({ line, code: "noTopic" })
        return
      }
      const reason = topicProblem(topic)
      if (reason) {
        errors.push({ line, code: "invalidTopic", topic, reason })
        return
      }
      // in ACL files no access word means readwrite, and deny takes away everything with a higher priority
      const chosen = actions.length > 0 ? actions : fileSyntax ? ["readwrite"] : defaults
      const types = [...new Set(chosen.flatMap((action) => expandAction(action, topic)))]
      if (types.length === 0) {
        errors.push({ line, code: "noAction" })
        return
      }
      const rulePriority = priority ?? (fileSyntax && allow === false ? DENY_PRIORITY : 0)
      for (const acltype of types) {
        const rule: ParsedRule = { acltype, topic, priority: rulePriority, allow: allow ?? true, line }
        const key = ruleKey(rule)
        const earlier = seen.get(key)
        if (!earlier) {
          seen.set(key, rule)
          rules.push(rule)
        } else if (earlier.priority !== rule.priority || earlier.allow !== rule.allow) {
          errors.push({ line, code: "conflict", other: earlier.line, acltype, topic })
        }
      }
    })
  })
  return { rules, errors }
}

/** Rules as the API gives them, where priority and effect may be left out (the broker always sends both). */
export function normalizeAcls(acls: readonly { acltype: string; topic: string; priority?: number; allow?: boolean }[]): AclRule[] {
  return acls.map(({ acltype, topic, priority, allow }) => ({ acltype, topic, priority: priority ?? 0, allow: allow ?? false }))
}

/** Whether a text differs from the rules it was made from, apart from blank lines and spaces at its ends. */
export function textDiffers(text: string | null, acls: readonly AclRule[]) {
  return text !== null && text.trim() !== serializeAcls(acls).trim()
}

/** The broker knows a rule by its type and topic. */
export function ruleKey(rule: Pick<AclRule, "acltype" | "topic">) {
  return `${rule.acltype}\u0000${rule.topic}`
}

/** Highest priority first, then by topic, as in the rules table. */
export function compareRules(a: AclRule, b: AclRule) {
  return b.priority - a.priority || a.topic.localeCompare(b.topic)
}

function quoteTopic(topic: string) {
  const plain =
    !/[\s,;"\\]/.test(topic) &&
    !topic.startsWith("//") &&
    !/^(?:[-*•]|\d+[.)])$/.test(topic) &&
    !isAction(topic.toLowerCase()) &&
    !effects.has(topic.toLowerCase()) &&
    !prioWords.has(topic.toLowerCase()) &&
    !prioAssignment.test(topic) &&
    !fileWords.has(topic.toLowerCase())
  return plain ? topic : `"${topic.replace(/[\\"]/g, "\\$&")}"`
}

/** The shortest keywords for the rule types of one topic. */
function actionWords(types: Set<string>, topic: string) {
  const words: string[] = []
  const take = (word: string) => {
    const expanded = expandAction(word, topic)
    if (expanded.every((type) => types.has(type))) {
      expanded.forEach((type) => types.delete(type))
      words.push(word)
    }
  }
  ;["pubsub", "pub", "recv", "sub", "unsub"].forEach(take)
  // what the shorthands cannot say, such as subscribeLiteral on a topic with a wildcard
  aclTypes.filter((type) => types.has(type)).forEach(take)
  // types the broker may know and this UI not
  words.push(...types)
  return words
}

/** The rules as text that parseAclText reads back to the same rules, whatever its defaults. */
export function serializeAcls(acls: readonly AclRule[]): string {
  const groups = new Map<string, { topic: string; priority: number; allow: boolean; types: Set<string> }>()
  for (const acl of [...acls].sort(compareRules)) {
    const key = `${acl.topic}\u0000${acl.priority}\u0000${acl.allow}`
    const group = groups.get(key) ?? { topic: acl.topic, priority: acl.priority, allow: acl.allow, types: new Set<string>() }
    group.types.add(acl.acltype)
    groups.set(key, group)
  }
  const lines = [...groups.values()].map((group) => ({
    head: [...(group.allow ? [] : ["deny"]), ...actionWords(group.types, group.topic)].join(" "),
    tail: quoteTopic(group.topic) + (group.priority !== 0 ? ` prio ${group.priority}` : ""),
  }))
  const width = Math.min(24, Math.max(0, ...lines.map((line) => line.head.length)))
  return lines.map((line) => `${line.head.padEnd(width)} ${line.tail}`).join("\n")
}

export interface AclDiff {
  added: AclRule[]
  removed: AclRule[]
  /** Same type and topic, another priority or effect: for the broker a removal and an addition. */
  changed: { before: AclRule; after: AclRule }[]
  unchanged: number
}

/** What changes from the rules of a role to those of a text. */
export function diffAcls(current: readonly AclRule[], next: readonly AclRule[]): AclDiff {
  const before = new Map(current.map((rule) => [ruleKey(rule), rule]))
  const diff: AclDiff = { added: [], removed: [], changed: [], unchanged: 0 }
  for (const rule of next) {
    const old = before.get(ruleKey(rule))
    before.delete(ruleKey(rule))
    if (!old) {
      diff.added.push(rule)
    } else if (old.priority !== rule.priority || old.allow !== rule.allow) {
      diff.changed.push({ before: old, after: rule })
    } else {
      diff.unchanged++
    }
  }
  diff.removed.push(...before.values())
  return diff
}
