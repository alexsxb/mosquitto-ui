import { BookOpenIcon, ChevronsUpDownIcon, LogOutIcon } from "lucide-react"
import { useTranslation } from "react-i18next"

import { handbookUrl, logout, useCurrentUser } from "@/auth/session"
import { Avatar, AvatarFallback } from "@/components/ui/avatar"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { SidebarMenu, SidebarMenuButton, SidebarMenuItem } from "@/components/ui/sidebar"

export function UserMenu() {
  const { t, i18n } = useTranslation()
  const me = useCurrentUser()
  if (!me.data) {
    return null
  }
  const user = me.data
  const displayName = user.name || user.username

  return (
    <SidebarMenu>
      <SidebarMenuItem>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <SidebarMenuButton size="lg">
              <Avatar className="size-8 rounded-lg">
                <AvatarFallback className="rounded-lg">{displayName.slice(0, 2).toUpperCase()}</AvatarFallback>
              </Avatar>
              <div className="grid flex-1 text-left text-sm leading-tight">
                <span className="truncate font-medium">{displayName}</span>
                <span className="truncate text-xs text-muted-foreground">{user.email}</span>
              </div>
              <ChevronsUpDownIcon className="ml-auto size-4" />
            </SidebarMenuButton>
          </DropdownMenuTrigger>
          <DropdownMenuContent side="top" align="start" className="min-w-56">
            <DropdownMenuLabel className="font-normal">
              <div className="text-sm font-medium">{user.username}</div>
              {user.email && <div className="text-xs text-muted-foreground">{user.email}</div>}
            </DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuItem asChild>
              <a href={handbookUrl(i18n.resolvedLanguage)} target="_blank" rel="noopener">
                <BookOpenIcon />
                {t("auth.handbook")}
              </a>
            </DropdownMenuItem>
            <DropdownMenuItem onSelect={logout}>
              <LogOutIcon />
              {t("auth.logout")}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </SidebarMenuItem>
    </SidebarMenu>
  )
}
