import { useTranslation } from "react-i18next"
import { toast } from "sonner"

import { ApiError } from "@/api/client"

/** Errors whose message from the server tells what exactly is wrong. */
const detailed = ["validation", "broker_rejected", "config_rejected", "invalid_file", "bridge_loop", "listener_failed", "victron_unreachable"]

/** Translated message for an error; validation and backend errors include the server's details. */
export function useErrorMessage() {
  const { t } = useTranslation()
  return (error: unknown) => {
    if (error instanceof ApiError) {
      const title = t(`errors.${error.code}` as "errors.generic", { defaultValue: error.message })
      return detailed.includes(error.code) ? `${title}: ${error.message}` : title
    }
    return t("errors.generic")
  }
}

export function useErrorToast() {
  const message = useErrorMessage()
  return (error: unknown) => toast.error(message(error))
}
