/** The priority the plugin gives a role that was given none (and leaves out of its answers). */
export const NO_PRIORITY = -1

/** The priority of a role, with the default of the plugin. */
export function priorityOf(priorities: Record<string, number> | undefined, role: string) {
  return priorities?.[role] ?? NO_PRIORITY
}

/** " (5)" after a role in the lists, nothing for the default. */
export function prioritySuffix(priorities: Record<string, number> | undefined, role: string) {
  const priority = priorityOf(priorities, role)
  return priority === NO_PRIORITY ? undefined : ` (${priority})`
}

/** Whether the roles or their priorities differ. */
export function rolesDiffer(
  before: readonly string[],
  beforePriorities: Record<string, number> | undefined,
  after: readonly string[],
  afterPriorities: Record<string, number> | undefined,
) {
  return (
    before.length !== after.length ||
    after.some((role) => !before.includes(role)) ||
    after.some((role) => priorityOf(beforePriorities, role) !== priorityOf(afterPriorities, role))
  )
}

/** The priorities of the chosen roles, for the request: each one explicitly, so that the server sends what the dialog shows. */
export function chosenPriorities(roles: readonly string[], priorities: Record<string, number>) {
  return Object.fromEntries(roles.map((role) => [role, priorityOf(priorities, role)]))
}
