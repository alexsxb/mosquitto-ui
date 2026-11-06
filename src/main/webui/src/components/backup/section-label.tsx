import type { ReactNode } from "react"
import { useTranslation } from "react-i18next"
import type { Schemas } from "@/api/client"

/** Name of a part of a backup for a checkbox, with what it holds below it where the name alone does not say. */
export function SectionLabel({ section, children }: { section: Schemas["BackupSection"]; children?: ReactNode }) {
  const { t } = useTranslation()
  const details = section === "ACCESS" ? t("backups.sectionDetails.ACCESS") : null
  return (
    <span className="flex flex-col gap-0.5">
      <span>
        {t(`backups.sections.${section}`)} {children}
      </span>
      {details && <span className="text-xs text-muted-foreground">{details}</span>}
    </span>
  )
}
