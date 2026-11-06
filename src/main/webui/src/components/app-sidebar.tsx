import { useTranslation } from "react-i18next"
import { NavLink, useLocation } from "react-router"

import { Logo } from "@/components/logo"
import { isActive, visibleGroups } from "@/components/nav"
import { UserMenu } from "@/components/user-menu"
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
} from "@/components/ui/sidebar"

export function AppSidebar() {
  const { t } = useTranslation()
  const { pathname } = useLocation()

  return (
    <Sidebar collapsible="icon">
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton size="lg" asChild>
              <NavLink to="/">
                {/* the menu button sizes every svg to size-4 */}
                <Logo className="size-8!" />
                <span className="truncate font-semibold">mosquitto-ui</span>
              </NavLink>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>
      <SidebarContent>
        {visibleGroups().map((group) => (
          <SidebarGroup key={group.titleKey}>
            <SidebarGroupLabel>{t(group.titleKey)}</SidebarGroupLabel>
            <SidebarMenu>
              {group.items.map((item) => (
                <SidebarMenuItem key={item.to}>
                  <SidebarMenuButton asChild tooltip={t(item.titleKey)} isActive={isActive(item, pathname)}>
                    <NavLink to={item.to}>
                      <item.icon />
                      <span>{t(item.titleKey)}</span>
                    </NavLink>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              ))}
            </SidebarMenu>
          </SidebarGroup>
        ))}
      </SidebarContent>
      <SidebarFooter>
        <UserMenu />
      </SidebarFooter>
    </Sidebar>
  )
}
