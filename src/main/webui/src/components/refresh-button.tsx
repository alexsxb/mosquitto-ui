import type { UseQueryResult } from "@tanstack/react-query"
import { RefreshCwIcon } from "lucide-react"
import { useState } from "react"
import { useTranslation } from "react-i18next"

import { Button } from "@/components/ui/button"
import { useErrorToast } from "@/lib/errors"
import { cn } from "@/lib/utils"

/** Long enough to see that something happened, an answer from the broker takes a few milliseconds. */
const MIN_SPIN_MS = 500

type Refreshable = Pick<UseQueryResult<unknown, unknown>, "refetch">

/** Loads the queries again. The table keeps showing the old rows until the new ones are there, and if that fails. */
export function RefreshButton({ queries }: { queries: Refreshable[] }) {
  const { t } = useTranslation()
  const errorToast = useErrorToast()
  const [spinning, setSpinning] = useState(false)

  async function refresh() {
    setSpinning(true)
    const [results] = await Promise.all([
      Promise.all(queries.map((query) => query.refetch())),
      new Promise((resolve) => setTimeout(resolve, MIN_SPIN_MS)),
    ])
    setSpinning(false)
    const failed = results.find((result) => result.isError)
    if (failed) {
      errorToast(failed.error)
    }
  }

  return (
    <Button variant="ghost" size="icon" aria-label={t("common.refresh")} title={t("common.refresh")} disabled={spinning} onClick={() => void refresh()}>
      <RefreshCwIcon className={cn(spinning && "animate-spin")} />
    </Button>
  )
}
