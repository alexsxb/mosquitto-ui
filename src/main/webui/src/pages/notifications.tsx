import { useQueryClient } from "@tanstack/react-query"
import { MailIcon } from "lucide-react"
import { useState, type FormEvent } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"

import { $api, type Schemas } from "@/api/client"
import { ChipsInput } from "@/components/chips-input"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { Spinner } from "@/components/ui/spinner"
import { Switch } from "@/components/ui/switch"
import { useErrorToast } from "@/lib/errors"

type Settings = Schemas["NotificationSettings"]
type Security = Settings["mail"]["security"]
type Notice = Settings["muted"][number]

const notices: Notice[] = ["BROKER", "BRIDGE", "CLIENT", "CERTIFICATE", "BACKUP", "RULE", "LOG_ERROR", "LOGIN_REJECTED"]
const securities: Security[] = ["STARTTLS", "TLS", "NONE"]

/** What the hint of a notice says: count is the number it is about */
function hintValues(settings: Settings, notice: Notice) {
  const every = settings.logMailEveryMinutes
  if (notice === "LOGIN_REJECTED") {
    return { count: settings.rejectedLogins, minutes: settings.rejectedWindowMinutes, every }
  }
  return { count: notice === "CLIENT" ? settings.clientAfterMinutes : settings.outageAfterMinutes, every }
}

function NotificationsForm({ settings }: { settings: Settings }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const save = $api.useMutation("put", "/api/notifications")
  const testMail = $api.useMutation("post", "/api/notifications/test-mail")
  const [recipients, setRecipients] = useState(settings.recipients)
  const [muted, setMuted] = useState(settings.muted)
  const [ignored, setIgnored] = useState(settings.ignoredClients)
  const [host, setHost] = useState(settings.mail.host ?? "")
  const [port, setPort] = useState(String(settings.mail.port))
  const [security, setSecurity] = useState<Security>(settings.mail.security)
  const [username, setUsername] = useState(settings.mail.username ?? "")
  const [password, setPassword] = useState("")
  const [from, setFrom] = useState(settings.mail.from ?? "")

  async function submit(event: FormEvent) {
    event.preventDefault()
    try {
      await save.mutateAsync({
        body: {
          recipients,
          mailHost: host || null,
          mailPort: port ? Number(port) : null,
          mailSecurity: security,
          mailUsername: username || null,
          mailPassword: password || null,
          mailFrom: from || null,
          muted,
          ignoredClients: ignored,
        },
      })
      setPassword("")
      toast.success(t("notifications.saved"))
      await queryClient.invalidateQueries({ queryKey: ["get", "/api/notifications"] })
    } catch (error) {
      errorToast(error)
    }
  }

  async function sendTest() {
    try {
      await testMail.mutateAsync({})
      toast.success(t("notifications.testSent"))
    } catch (error) {
      errorToast(error)
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <Card>
        <CardHeader>
          <CardTitle>{t("notifications.recipientsTitle")}</CardTitle>
          <CardDescription>{t("notifications.recipientsDesc")}</CardDescription>
        </CardHeader>
        <CardContent>
          <Field>
            <FieldLabel htmlFor="notify-recipients">{t("notifications.recipients")}</FieldLabel>
            <ChipsInput id="notify-recipients" placeholder="admin@example.com" value={recipients} onChange={setRecipients} />
            <FieldDescription>{t("notifications.recipientsHint")}</FieldDescription>
          </Field>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>{t("notifications.noticesTitle")}</CardTitle>
          <CardDescription>{t("notifications.noticesDesc")}</CardDescription>
        </CardHeader>
        <CardContent>
          <FieldGroup>
            <div className="divide-border flex flex-col divide-y">
              {notices.map((notice) => (
                <label key={notice} className="flex items-start justify-between gap-4 py-3 first:pt-0 last:pb-0">
                  <span className="min-w-0">
                    <span className="block text-sm font-medium">{t(`notifications.notices.${notice}`)}</span>
                    <span className="text-muted-foreground block text-sm">{t(`notifications.hints.${notice}`, hintValues(settings, notice))}</span>
                  </span>
                  <Switch
                    checked={!muted.includes(notice)}
                    onCheckedChange={(on) => setMuted(on ? muted.filter((m) => m !== notice) : [...muted, notice])}
                    aria-label={t(`notifications.notices.${notice}`)}
                  />
                </label>
              ))}
            </div>
            <Field>
              <FieldLabel htmlFor="notify-ignored">{t("notifications.ignoredClients")}</FieldLabel>
              <ChipsInput id="notify-ignored" value={ignored} onChange={setIgnored} />
              <FieldDescription>{t("notifications.ignoredClientsHint")}</FieldDescription>
            </Field>
          </FieldGroup>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>{t("notifications.mail")}</CardTitle>
          <CardDescription>{t("notifications.mailDesc")}</CardDescription>
        </CardHeader>
        <CardContent>
          <FieldGroup>
            <div className="grid gap-3 sm:grid-cols-[1fr_7rem_10rem]">
              <Field>
                <FieldLabel htmlFor="mail-host">{t("notifications.host")}</FieldLabel>
                <Input id="mail-host" placeholder="smtp.example.com" value={host} onChange={(e) => setHost(e.target.value)} />
              </Field>
              <Field>
                <FieldLabel htmlFor="mail-port">{t("notifications.port")}</FieldLabel>
                <Input id="mail-port" type="number" min={1} max={65535} value={port} onChange={(e) => setPort(e.target.value)} />
              </Field>
              <Field>
                <FieldLabel>{t("notifications.security")}</FieldLabel>
                <Select value={security} onValueChange={(v) => setSecurity(v as Security)}>
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {securities.map((s) => (
                      <SelectItem key={s} value={s}>
                        {t(`notifications.securities.${s}`)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </Field>
            </div>
            <div className="grid gap-3 sm:grid-cols-3">
              <Field>
                <FieldLabel htmlFor="mail-user">{t("notifications.user")}</FieldLabel>
                <Input id="mail-user" autoComplete="off" value={username} onChange={(e) => setUsername(e.target.value)} />
              </Field>
              <Field>
                <FieldLabel htmlFor="mail-password">{t("notifications.password")}</FieldLabel>
                <Input
                  id="mail-password"
                  type="password"
                  autoComplete="new-password"
                  placeholder={settings.mail.passwordSet ? t("notifications.unchanged") : ""}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
              </Field>
              <Field>
                <FieldLabel htmlFor="mail-from">{t("notifications.from")}</FieldLabel>
                <Input id="mail-from" type="email" placeholder="mosquitto-ui@example.com" value={from} onChange={(e) => setFrom(e.target.value)} />
              </Field>
            </div>
          </FieldGroup>
        </CardContent>
      </Card>

      <div className="flex flex-wrap items-center gap-2">
        <Button type="submit" disabled={save.isPending}>
          {save.isPending && <Spinner />}
          {t("common.save")}
        </Button>
        <Button type="button" variant="outline" disabled={testMail.isPending} onClick={() => void sendTest()}>
          {testMail.isPending ? <Spinner /> : <MailIcon />}
          {t("notifications.testMail")}
        </Button>
        <span className="text-muted-foreground text-sm">{t("notifications.testHint")}</span>
      </div>
    </form>
  )
}

/** Mail server, recipients and which problems they hear about. */
export function NotificationsPage() {
  const settings = $api.useQuery("get", "/api/notifications")
  return settings.data ? <NotificationsForm key={JSON.stringify(settings.data)} settings={settings.data} /> : <Skeleton className="h-96 w-full" />
}
