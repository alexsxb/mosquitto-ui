import { useTranslation } from "react-i18next"

import type { Schemas } from "@/api/client"

/** Why the rules allow or forbid: the deciding rule or role with the group it came through, as the ACL tester and the trace say it. */
export function useAclReason() {
  const { t } = useTranslation()
  return (decision: Schemas["AclDecision"]) => {
    const via = decision.group ? ` ${t("access.viaGroup", { group: decision.group })}` : ""
    switch (decision.reason) {
      case "RULE":
        return t("access.reasons.RULE", { topic: decision.rule?.topic, priority: decision.rule?.priority, role: decision.role }) + via
      case "WILDCARDS":
        return t("access.reasons.WILDCARDS", { role: decision.role }) + via
      default:
        return t(`access.reasons.${decision.reason}`)
    }
  }
}
