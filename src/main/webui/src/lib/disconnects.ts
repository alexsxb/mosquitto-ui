/**
 * Mosquitto's dynamic security disconnects clients when their rights change: every client of a role whose rules change,
 * every member of a group on each change of it, a client on each change of it, and with the anonymous group everyone
 * without a login. These count who is connected among them, so the dialogs can say so before saving.
 */
type Known = { username: string; groups: string[]; roles: string[]; connections: string[] }

export function sameNames(a: readonly string[], b: readonly string[]) {
  const left = new Set(a)
  const right = new Set(b)
  return left.size === right.size && [...left].every((name) => right.has(name))
}

/** How many of these clients are connected right now. */
export function connectedAmong(clients: readonly Known[] | undefined, usernames: Iterable<string>) {
  const names = new Set(usernames)
  return (clients ?? []).filter((client) => names.has(client.username) && client.connections.length > 0).length
}

/** The clients that have a role, directly or through one of these groups. */
export function holdersOf(rolename: string, clients: readonly Known[] | undefined, groups: readonly { groupname: string; roles: string[] }[] | undefined) {
  const viaGroups = new Set((groups ?? []).filter((group) => group.roles.includes(rolename)).map((group) => group.groupname))
  return (clients ?? []).filter((client) => client.roles.includes(rolename) || client.groups.some((group) => viaGroups.has(group))).map((client) => client.username)
}
