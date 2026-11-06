import { useQueryClient } from "@tanstack/react-query"
import { ArchiveRestoreIcon, CalendarClockIcon, HardDriveIcon, SettingsIcon } from "lucide-react"
import { useState } from "react"
import { useTranslation } from "react-i18next"

import { $api } from "@/api/client"
import { JobsCard, RunLogDialog, RunsCard } from "@/components/backup/jobs"
import { DownloadCard, RestoreCard } from "@/components/backup/restore"
import { BackupSettingsCard } from "@/components/backup/settings"
import { TargetsCard } from "@/components/backup/targets"
import { Skeleton } from "@/components/ui/skeleton"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { useErrorToast } from "@/lib/errors"

/** Backups: jobs with schedule and runs, targets, restore and settings. */
export function BackupsPage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const overview = $api.useQuery("get", "/api/backup", {}, {
    refetchInterval: (q) => (q.state.data?.jobs.some((j) => j.running) ? 3000 : false),
  })
  const cancel = $api.useMutation("post", "/api/backup/jobs/{id}/cancel")
  const [shownRun, setShownRun] = useState<number | null>(null)

  async function refresh() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["get", "/api/backup"] }),
      queryClient.invalidateQueries({ queryKey: ["get", "/api/backup/runs"] }),
    ])
  }

  if (!overview.data) {
    return <Skeleton className="h-64 w-full" />
  }
  const data = overview.data

  return (
    <>
      <Tabs defaultValue="jobs" className="gap-4">
        <TabsList className="w-full justify-start overflow-x-auto group-data-horizontal/tabs:h-10">
          <TabsTrigger value="jobs">
            <CalendarClockIcon />
            {t("backups.tabs.jobs")}
          </TabsTrigger>
          <TabsTrigger value="targets">
            <HardDriveIcon />
            {t("backups.tabs.targets")}
          </TabsTrigger>
          <TabsTrigger value="restore">
            <ArchiveRestoreIcon />
            {t("backups.tabs.restore")}
          </TabsTrigger>
          <TabsTrigger value="settings">
            <SettingsIcon />
            {t("backups.tabs.settings")}
          </TabsTrigger>
        </TabsList>
        <TabsContent value="jobs" className="space-y-4">
          <JobsCard
            jobs={data.jobs}
            targets={data.targets}
            serverTime={data.serverTime}
            timeZone={data.settings.timeZone}
            onChanged={refresh}
            onShowRun={setShownRun}
          />
          <RunsCard onShowRun={setShownRun} />
        </TabsContent>
        <TabsContent value="targets">
          <TargetsCard targets={data.targets} onChanged={refresh} />
        </TabsContent>
        <TabsContent value="restore" className="space-y-4">
          <RestoreCard targets={data.targets} />
          <DownloadCard />
        </TabsContent>
        <TabsContent value="settings">
          <BackupSettingsCard key={JSON.stringify(data.settings)} settings={data.settings} />
        </TabsContent>
      </Tabs>
      {shownRun !== null && (
        <RunLogDialog
          runId={shownRun}
          onClose={() => setShownRun(null)}
          onCancel={(jobId) =>
            cancel
              .mutateAsync({ params: { path: { id: jobId } } })
              .then(refresh)
              .catch((error: unknown) => errorToast(error))
          }
        />
      )}
    </>
  )
}
