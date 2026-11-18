import { UnplugIcon } from "lucide-react"
import { useTranslation } from "react-i18next"

/** Before saving: how many connected clients the broker will disconnect for this change. */
export function DisconnectHint({ count, anonymous = false, rules = false }: { count: number; anonymous?: boolean; rules?: boolean }) {
  const { t } = useTranslation()
  if (count === 0 && !anonymous) {
    return null
  }
  return (
    <p className="flex items-start gap-2 text-sm text-muted-foreground">
      <UnplugIcon className="mt-0.5 size-4 shrink-0" />
      <span>
        {rules
          ? t(anonymous ? "disconnects.rulesWithAnonymous" : "disconnects.rules", { count })
          : t(anonymous ? "disconnects.withAnonymous" : "disconnects.clients", { count })}
      </span>
    </p>
  )
}
