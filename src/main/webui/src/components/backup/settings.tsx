import { useQueryClient } from "@tanstack/react-query"
import { useState, type FormEvent } from "react"
import { useTranslation } from "react-i18next"
import { Link } from "react-router"
import { toast } from "sonner"

import { $api, type Schemas } from "@/api/client"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Spinner } from "@/components/ui/spinner"
import { useErrorToast } from "@/lib/errors"

type Settings = Schemas["BackupSettings"]

/** Time zone of the schedules and run log retention; the mail server has its own page. */
export function BackupSettingsCard({ settings }: { settings: Settings }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const save = $api.useMutation("put", "/api/backup/settings")
  const [timeZone, setTimeZone] = useState(settings.timeZone)
  const [retention, setRetention] = useState(String(settings.runRetentionDays))
  const zones = typeof Intl.supportedValuesOf === "function" ? Intl.supportedValuesOf("timeZone") : [timeZone]

  async function submit(event: FormEvent) {
    event.preventDefault()
    try {
      const result = await save.mutateAsync({
        body: { timeZone, runRetentionDays: Number(retention || 0) },
      })
      toast.success(result.pruned > 0 ? t("backups.settings.savedPruned", { count: result.pruned }) : t("backups.settings.saved"))
      await queryClient.invalidateQueries({ queryKey: ["get", "/api/backup"] })
      await queryClient.invalidateQueries({ queryKey: ["get", "/api/backup/runs"] })
    } catch (error) {
      errorToast(error)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("backups.settings.title")}</CardTitle>
        <CardDescription>{t("backups.settings.desc")}</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit}>
          <FieldGroup>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field>
                <FieldLabel>{t("backups.settings.timeZone")}</FieldLabel>
                <Select value={timeZone} onValueChange={setTimeZone}>
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent className="max-h-72">
                    {zones.map((z) => (
                      <SelectItem key={z} value={z}>
                        {z}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <FieldDescription>{t("backups.settings.timeZoneHint")}</FieldDescription>
              </Field>
              <Field>
                <FieldLabel htmlFor="settings-retention">{t("backups.settings.runRetention")}</FieldLabel>
                <Input id="settings-retention" type="number" min={0} max={3650} value={retention} onChange={(e) => setRetention(e.target.value)} />
                <FieldDescription>{t("backups.settings.runRetentionHint")}</FieldDescription>
              </Field>
            </div>
            <p className="text-muted-foreground text-sm">
              {t("backups.settings.mailMoved")}{" "}
              <Link to="/notifications" className="underline underline-offset-4">
                {t("nav.notifications")}
              </Link>
            </p>
            <div className="flex flex-wrap gap-2">
              <Button type="submit" disabled={save.isPending}>
                {save.isPending && <Spinner />}
                {t("common.save")}
              </Button>
            </div>
          </FieldGroup>
        </form>
      </CardContent>
    </Card>
  )
}
