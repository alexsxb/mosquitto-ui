import { useTranslation } from "react-i18next"

export function PlaceholderPage() {
  const { t } = useTranslation()
  return <p className="text-muted-foreground text-sm">{t("notFound")}</p>
}
