import { Navigate, Route, Routes } from "react-router"

import { RequireAccess } from "@/auth/guards"
import { Layout } from "@/components/layout"
import { BridgesPage } from "@/pages/bridges"
import { BackupsPage } from "@/pages/backups"
import { CertificatePage } from "@/pages/certificate"
import { ClientsPage } from "@/pages/clients"
import { ConfigPage } from "@/pages/config"
import { LocalUsersPage } from "@/pages/local-users"
import { SsoPage } from "@/pages/sso"
import { AccessPage } from "@/pages/access"
import { AuditPage } from "@/pages/audit"
import { DashboardPage } from "@/pages/dashboard"
import { GroupsPage } from "@/pages/groups"
import { ImportPage } from "@/pages/import"
import { MessageRulesPage } from "@/pages/message-rules"
import { MorePage } from "@/pages/more"
import { NotificationsPage } from "@/pages/notifications"
import { TokenAuthPage } from "@/pages/token-auth"
import { PlaceholderPage } from "@/pages/placeholder"
import { RolesPage } from "@/pages/roles"
import { SessionsPage } from "@/pages/sessions"
import { TracePage } from "@/pages/trace"
import { LogsPage } from "@/pages/logs"
import { TopicsPage } from "@/pages/topics"

export default function App() {
  return (
    <Routes>
      <Route
        element={
          <RequireAccess>
            <Layout />
          </RequireAccess>
        }
      >
        <Route index element={<DashboardPage />} />
        <Route path="login" element={<Navigate to="/" replace />} />
        <Route path="topics" element={<TopicsPage />} />
        <Route path="sessions" element={<SessionsPage />} />
        <Route path="trace" element={<TracePage />} />
        <Route path="logs" element={<LogsPage />} />
        <Route path="clients" element={<ClientsPage />} />
        <Route path="groups" element={<GroupsPage />} />
        <Route path="roles" element={<RolesPage />} />
        <Route path="access" element={<AccessPage />} />
        <Route path="token-auth" element={<TokenAuthPage />} />
        <Route path="notifications" element={<NotificationsPage />} />
        <Route path="config" element={<ConfigPage />} />
        <Route path="message-rules" element={<MessageRulesPage />} />
        <Route path="bridges" element={<BridgesPage />} />
        <Route path="certificate" element={<CertificatePage />} />
        <Route path="backups" element={<BackupsPage />} />
        <Route path="import" element={<ImportPage />} />
        <Route path="audit" element={<AuditPage />} />
        <Route path="local-users" element={<LocalUsersPage />} />
        <Route path="sso" element={<SsoPage />} />
        <Route path="more" element={<MorePage />} />
        <Route path="*" element={<PlaceholderPage />} />
      </Route>
    </Routes>
  )
}
