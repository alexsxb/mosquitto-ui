export interface TopicMessage {
  value: string | null
  binary: boolean
  size: number
  cut: boolean
  retained: boolean
  qos: number
  time: number
}

export interface TopicNode {
  name: string
  path: string
  parent: TopicNode | null
  children: Map<string, TopicNode>
  message: TopicMessage | null
  /** Newest first, as far as the browser saw them. */
  history: TopicMessage[]
  /** Oldest first: time and text of values that can be drawn, see numericFields. */
  points: [number, string][]
  /** Messages of this topic, as counted by the server. */
  count: number
  /** Topics with a value below and including this one. */
  topics: number
  /** Messages below and including this one. */
  messages: number
  /** When this topic or one below it got a value, for the flash in the tree. */
  touched: number
}

export interface Update extends TopicMessage {
  topic: string
  count: number
  removed: boolean
}

const MAX_HISTORY = 20
/** Every topic keeps some, as a chart is usually opened after the fact. */
const MAX_POINTS = 300
/** A day at a value per second, for what is being looked at. */
const MAX_WATCHED_POINTS = 86400
const MAX_POINT_TEXT = 500

export function createRoot(): TopicNode {
  return { name: "", path: "", parent: null, children: new Map(), message: null, history: [], points: [], count: 0, topics: 0, messages: 0, touched: 0 }
}

function nodeOf(root: TopicNode, topic: string) {
  let node = root
  for (const name of topic.split("/")) {
    let child = node.children.get(name)
    if (!child) {
      child = { ...createRoot(), name, path: node === root ? name : `${node.path}/${name}`, parent: node }
      node.children.set(name, child)
    }
    node = child
  }
  return node
}

function find(root: TopicNode, topic: string) {
  let node: TopicNode | undefined = root
  for (const name of topic.split("/")) {
    node = node?.children.get(name)
  }
  return node
}

function remove(node: TopicNode) {
  const hadValue = node.message !== null
  const messages = node.count
  node.message = null
  node.count = 0
  node.history = []
  node.points = []
  for (let up: TopicNode | null = node; up; up = up.parent) {
    up.topics -= hadValue ? 1 : 0
    up.messages -= messages
  }
  // branches without anything left go away, as they would not come back from the server either
  for (let up = node; up.parent && up.topics === 0; up = up.parent) {
    up.parent.children.delete(up.name)
  }
}

/** Takes over a batch of the server. */
export function apply(root: TopicNode, updates: Update[], watched: ReadonlySet<string> = new Set()) {
  for (const update of updates) {
    if (update.removed) {
      const node = find(root, update.topic)
      if (node) {
        remove(node)
      }
      continue
    }
    const node = nodeOf(root, update.topic)
    const added = update.count - node.count
    const isNew = node.message === null
    const { value, binary, size, cut, retained, qos, time } = update
    node.message = { value, binary, size, cut, retained, qos, time }
    node.history = [node.message, ...node.history].slice(0, MAX_HISTORY)
    node.count = update.count
    if (!binary && !cut && value !== null && value.length <= MAX_POINT_TEXT && Object.keys(numericFields(value)).length > 0) {
      node.points.push([time, value])
      const max = watched.has(node.path) ? MAX_WATCHED_POINTS : MAX_POINTS
      if (node.points.length > max) {
        node.points.splice(0, node.points.length - max)
      }
    }
    for (let up: TopicNode | null = node; up; up = up.parent) {
      up.topics += isNew ? 1 : 0
      up.messages += added
      up.touched = time
    }
  }
}

function collect(value: unknown, path: string, fields: Record<string, number>) {
  if (typeof value === "number" && Number.isFinite(value)) {
    fields[path] = value
  } else if (Array.isArray(value)) {
    value.forEach((item, index) => collect(item, path ? `${path}.${index}` : String(index), fields))
  } else if (value && typeof value === "object") {
    Object.entries(value).forEach(([key, item]) => collect(item, path ? `${path}.${key}` : key, fields))
  }
}

/** What can be drawn of a value: "" for a plain number, otherwise the paths of the numbers in JSON, like "value" of a GX device. */
export function numericFields(value: string): Record<string, number> {
  const fields: Record<string, number> = {}
  const text = value.trim()
  if (text !== "" && !Number.isNaN(Number(text))) {
    fields[""] = Number(text)
  } else if (text.startsWith("{") || text.startsWith("[")) {
    try {
      collect(JSON.parse(text), "", fields)
    } catch {
      // only looks like JSON
    }
  }
  return fields
}

/** The field a chart starts with: GX devices wrap their numbers as {"value": 12}. */
export function defaultField(fields: string[]) {
  return fields.includes("value") ? "value" : fields[0]
}

export function seriesOf(node: TopicNode, field: string): [number, number][] {
  const series: [number, number][] = []
  for (const [time, value] of node.points) {
    const number = numericFields(value)[field]
    if (number !== undefined) {
      series.push([time, number])
    }
  }
  return series
}

/** Numbers in their order, "10" after "9". */
export function sortedChildren(node: TopicNode) {
  return [...node.children.values()].sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }))
}

/** The paths with a hit and the branches that lead to them. */
export function matches(root: TopicNode, term: string) {
  const found = new Set<string>()
  const visit = (node: TopicNode) => {
    let hit = node.path.toLowerCase().includes(term) || (node.message?.value?.toLowerCase().includes(term) ?? false)
    for (const child of node.children.values()) {
      hit = visit(child) || hit
    }
    if (hit) {
      found.add(node.path)
    }
    return hit
  }
  visit(root)
  return found
}

/** These topics with all above them, the form of the result of matches. */
export function pathsOf(topics: string[]) {
  const found = new Set<string>([""])
  for (const topic of topics) {
    const names = topic.split("/")
    names.forEach((_, index) => found.add(names.slice(0, index + 1).join("/")))
  }
  return found
}

/** Pretty text if the value is a JSON object or array, null otherwise. */
export function prettyJson(value: string | null) {
  if (!value || !/^\s*[{[]/.test(value)) {
    return null
  }
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return null
  }
}

export function formatSize(bytes: number) {
  return bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(1)} KB`
}
