import { useTranslation } from "react-i18next"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"

const REASONS = ["broker", "plugin", "rights"] as const

type Reason = (typeof REASONS)[number]

function isReason(reason: string | null | undefined): reason is Reason {
  return (REASONS as readonly (string | null | undefined)[]).includes(reason)
}

/** Why the inspect plugin of the broker cannot be asked (InspectService.status) */
export function InspectUnavailable({ reason }: { reason: string | null | undefined }) {
  const { t } = useTranslation()
  const known = isReason(reason) ? reason : "plugin"
  return (
    <Alert>
      <AlertTitle>{t("inspect.unavailableTitle")}</AlertTitle>
      <AlertDescription>{t(`inspect.unavailable.${known}`)}</AlertDescription>
    </Alert>
  )
}
