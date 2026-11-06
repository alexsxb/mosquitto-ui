import { TriangleAlertIcon } from "lucide-react"
import { useTranslation } from "react-i18next"

import { useBrokerStatus } from "@/lib/broker"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"

/** Without the broker, no page can show or change anything. */
export function BrokerAlert() {
  const { t } = useTranslation()
  const status = useBrokerStatus()

  if (!status.data || status.data.connected) {
    return null
  }
  return (
    <Alert variant="destructive" className="mb-4">
      <TriangleAlertIcon />
      <AlertTitle>{t("broker.offlineTitle", { host: status.data.host, port: status.data.port })}</AlertTitle>
      {status.data.error && <AlertDescription>{status.data.error}</AlertDescription>}
    </Alert>
  )
}
