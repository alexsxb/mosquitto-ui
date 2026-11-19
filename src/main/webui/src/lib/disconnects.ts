import { $api } from "@/api/client"

/**
 * Dynamic security disconnects clients when their rights change, so that the broker checks their subscriptions again. With
 * plugin_opt_kick always (Mosquitto itself) on every change: every client of a role whose rules change, every member of a
 * group on each change of it, a client on each change of it. With minimal (docker/mosquitto/dynsec/kick-minimal.patch, the
 * default of mosquitto-ui) only when a change can take a subscription away, as publishing and receiving are checked on every
 * message. With the anonymous group also everyone without a login. These count who is connected among them, so the dialogs
 * can say so before saving.
 */
type Known = { username: string; groups: string[]; roles: string[]; connections: string[] }
type RoleRules = { rolename: string; allowwildcardsubs: boolean; acls: { acltype: string; allow?: boolean }[] }

/** What the broker does; until the UI knows, as Mosquitto itself. */
export function useKickMinimal() {
  const access = $api.useQuery("get", "/api/access")
  return access.data?.kick === "MINIMAL"
}

export function sameNames(a: readonly string[], b: readonly string[]) {
  const left = new Set(a)
  const right = new Set(b)
  return left.size === right.size && [...left].every((name) => right.has(name))
}

function isSubscribe(acltype: string) {
  return acltype === "subscribeLiteral" || acltype === "subscribePattern"
}

function grantsSubscribe(role: RoleRules) {
  return role.acls.some((acl) => isSubscribe(acl.acltype) && acl.allow === true)
}

function restrictsSubscribe(role: RoleRules) {
  return !role.allowwildcardsubs || role.acls.some((acl) => isSubscribe(acl.acltype) && !acl.allow)
}

/** Whether going from these roles to those can take a subscription away: a role that allowed one goes, one that forbids comes. */
export function rolesChangeCanRevoke(before: readonly string[], after: readonly string[], roles: readonly RoleRules[] | undefined) {
  const find = (name: string) => roles?.find((role) => role.rolename === name)
  const gone = before.filter((name) => !after.includes(name))
  const added = after.filter((name) => !before.includes(name))
  // a role the UI does not know (yet) counts as one that takes away
  return gone.some((name) => !find(name) || grantsSubscribe(find(name)!)) || added.some((name) => !find(name) || restrictsSubscribe(find(name)!))
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

type DiffRule = { acltype: string; allow: boolean }

/**
 * Whether replacing all rules of a role can take a subscription away, as kick-minimal.patch decides for modifyRole: a rule
 * that allowed subscribing goes or changes, or one that forbids it comes or changes.
 */
export function aclChangeCanRevoke(diff: { added: DiffRule[]; removed: DiffRule[]; changed: { before: DiffRule; after: DiffRule }[] }) {
  return (
    diff.removed.some((acl) => isSubscribe(acl.acltype) && acl.allow) ||
    diff.added.some((acl) => isSubscribe(acl.acltype) && !acl.allow) ||
    diff.changed.some(({ before, after }) => isSubscribe(before.acltype) && (before.allow || !after.allow))
  )
}
