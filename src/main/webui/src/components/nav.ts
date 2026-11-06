import { ActivityIcon, ArchiveIcon, ScrollTextIcon, CircleDotIcon, FilterIcon, BellIcon, BoxesIcon, FileCogIcon, HistoryIcon, ImportIcon, KeyRoundIcon, LayoutDashboardIcon, ListChecksIcon, ListTreeIcon, NetworkIcon, ShieldCheckIcon, ShieldIcon, UserCogIcon, UsersIcon, LogInIcon, type LucideIcon } from "lucide-react"

export interface NavItem {
  titleKey: `nav.${"dashboard" | "topics" | "sessions" | "trace" | "logs" | "clients" | "groups" | "roles" | "rules" | "tokenAuth" | "config" | "messageRules" | "bridges" | "certificate" | "backups" | "notifications" | "import" | "audit" | "localUsers" | "sso"}`
  to: string
  icon: LucideIcon
}

export const navGroups: { titleKey: `nav.${"overview" | "access" | "brokerGroup"}`; items: NavItem[] }[] = [
  {
    titleKey: "nav.overview",
    items: [
      { titleKey: "nav.dashboard", to: "/", icon: LayoutDashboardIcon },
      { titleKey: "nav.topics", to: "/topics", icon: ListTreeIcon },
      { titleKey: "nav.sessions", to: "/sessions", icon: ActivityIcon },
      { titleKey: "nav.trace", to: "/trace", icon: CircleDotIcon },
      { titleKey: "nav.logs", to: "/logs", icon: ScrollTextIcon },
    ],
  },
  {
    titleKey: "nav.access",
    items: [
      { titleKey: "nav.clients", to: "/clients", icon: UsersIcon },
      { titleKey: "nav.groups", to: "/groups", icon: BoxesIcon },
      { titleKey: "nav.roles", to: "/roles", icon: ShieldIcon },
      { titleKey: "nav.rules", to: "/access", icon: ListChecksIcon },
      { titleKey: "nav.tokenAuth", to: "/token-auth", icon: KeyRoundIcon },
    ],
  },
  {
    titleKey: "nav.brokerGroup",
    items: [
      { titleKey: "nav.config", to: "/config", icon: FileCogIcon },
      { titleKey: "nav.messageRules", to: "/message-rules", icon: FilterIcon },
      { titleKey: "nav.bridges", to: "/bridges", icon: NetworkIcon },
      { titleKey: "nav.certificate", to: "/certificate", icon: ShieldCheckIcon },
      { titleKey: "nav.backups", to: "/backups", icon: ArchiveIcon },
      { titleKey: "nav.notifications", to: "/notifications", icon: BellIcon },
      { titleKey: "nav.import", to: "/import", icon: ImportIcon },
      { titleKey: "nav.sso", to: "/sso", icon: LogInIcon },
      { titleKey: "nav.localUsers", to: "/local-users", icon: UserCogIcon },
      { titleKey: "nav.audit", to: "/audit", icon: HistoryIcon },
    ],
  },
]

export const navItems = navGroups.flatMap((group) => group.items)

/** The groups of the navigation */
export function visibleGroups() {
  return navGroups
}

export function isActive(item: NavItem, pathname: string) {
  return item.to === "/" ? pathname === "/" : pathname.startsWith(item.to)
}

/** The page with everything that is not in the bar at the bottom of a phone */
export const MORE_PATH = "/more"

/** What the bar at the bottom of a phone shows next to "More": what one looks at on the way */
export const barItems = ["/", "/topics", "/clients"].map((to) => navItems.find((item) => item.to === to)!)
