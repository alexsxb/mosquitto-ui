import { useQueryClient } from "@tanstack/react-query"
import { RotateCcwIcon } from "lucide-react"
import { useMemo, useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"

import { $api, type Schemas } from "@/api/client"
import { DisconnectHint } from "@/components/disconnect-hint"
import { Button } from "@/components/ui/button"
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Spinner } from "@/components/ui/spinner"
import { Textarea } from "@/components/ui/textarea"
import { defaultActions, diffAcls, normalizeAcls, parseAclText, serializeAcls, type AclDiff, type AclRule, type AclTextError } from "@/lib/acl-text"
import { aclChangeCanRevoke, useKickMinimal } from "@/lib/disconnects"
import { useErrorToast } from "@/lib/errors"
import { cn } from "@/lib/utils"

type Role = Schemas["RoleView"]

/** Errors shown at once, the rest only counted. */
const MAX_ERRORS = 5

/** The errors of a text with their lines, translated. */
export function AclTextErrors({ errors }: { errors: AclTextError[] }) {
  const { t } = useTranslation()
  if (errors.length === 0) {
    return null
  }
  return (
    <ul className="space-y-1 text-sm text-destructive">
      {errors.slice(0, MAX_ERRORS).map((error, index) => (
        <li key={index}>
          <span className="font-medium">{t("roles.text.line", { line: error.line })}:</span>{" "}
          {error.code === "invalidTopic"
            ? t("roles.text.errors.invalidTopic", { topic: error.topic, reason: t(`roles.text.reasons.${error.reason}`) })
            : t(`roles.text.errors.${error.code}`, { ...error })}
        </li>
      ))}
      {errors.length > MAX_ERRORS && <li>{t("roles.text.moreErrors", { count: errors.length - MAX_ERRORS })}</li>}
    </ul>
  )
}

/** One rule as a line of the text. */
function line(rule: AclRule) {
  return serializeAcls([rule])
}

/** What saving changes, line by line; against no rules, the rules a new role starts with. */
export function DiffList({ diff }: { diff: AclDiff }) {
  const rows = [
    ...diff.added.map((rule) => ({ sign: "+", text: line(rule), was: null, tone: "text-emerald-600 dark:text-emerald-400" })),
    ...diff.changed.map(({ before, after }) => ({ sign: "~", text: line(after), was: line(before), tone: "text-amber-700 dark:text-amber-400" })),
    ...diff.removed.map((rule) => ({ sign: "−", text: line(rule), was: null, tone: "text-destructive" })),
  ]
  if (rows.length === 0) {
    return null
  }
  return (
    <ul className="max-h-40 overflow-y-auto rounded-lg border px-3 py-2 font-mono text-xs">
      {rows.map((row, index) => (
        <li key={index} className="whitespace-pre-wrap break-all">
          <span className={cn("mr-2 select-none", row.tone)}>{row.sign}</span>
          <span className={cn(row.tone, row.sign === "−" && "line-through")}>{row.text}</span>
          {row.was && <span className="text-muted-foreground"> ← {row.was}</span>}
        </li>
      ))}
    </ul>
  )
}

/** The choice of what lines without an action get. */
export function DefaultActionSelect({ id, value, onChange }: { id: string; value: string; onChange: (value: string) => void }) {
  const { t } = useTranslation()
  return (
    <Field orientation="horizontal" className="w-auto">
      <FieldLabel htmlFor={id} className="font-normal whitespace-nowrap">
        {t("roles.text.defaults")}
      </FieldLabel>
      <Select value={value} onValueChange={onChange}>
        <SelectTrigger id={id} size="sm" className="w-auto">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {defaultActions.map((action) => (
            <SelectItem key={action} value={action}>
              {t(`roles.text.defaultActions.${action}`)}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </Field>
  )
}

/**
 * All rules of a role as text. text is null while it is the rules of the role as they are; the dialog keeps it, so that it
 * survives switching to the table and back.
 */
export function AclTextEditor({
  role,
  text,
  onText,
  affected,
}: {
  role: Role
  text: string | null
  onText: (text: string | null) => void
  affected: { connected: number; anonymous: boolean }
}) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const kickMinimal = useKickMinimal()
  const save = $api.useMutation("put", "/api/roles/{rolename}/acls")
  const [defaultAction, setDefaultAction] = useState<string>(defaultActions[0])

  const current = useMemo(() => normalizeAcls(role.acls), [role.acls])
  const value = text ?? serializeAcls(current)
  const parsed = useMemo(() => parseAclText(value, [defaultAction]), [value, defaultAction])
  const diff = useMemo(() => diffAcls(current, parsed.rules), [current, parsed.rules])
  const changes = diff.added.length + diff.removed.length + diff.changed.length
  const disconnects = changes > 0 && (!kickMinimal || aclChangeCanRevoke(diff))

  async function apply() {
    try {
      const acls = parsed.rules.map(({ acltype, topic, priority, allow }) => ({ acltype, topic, priority, allow }))
      await save.mutateAsync({ params: { path: { rolename: role.rolename } }, body: { acls } })
      toast.success(t("roles.text.saved"))
      await queryClient.invalidateQueries({ queryKey: ["get", "/api/roles"] })
      onText(null)
    } catch (error) {
      errorToast(error)
    }
  }

  return (
    <div className="space-y-3">
      <Field>
        <Textarea
          aria-label={t("roles.text.text")}
          className="max-h-[50vh] min-h-48 font-mono text-xs"
          spellCheck={false}
          autoCapitalize="off"
          autoCorrect="off"
          readOnly={role.locked}
          placeholder={"sensors/+/state\npub devices/door/cmd\ndeny sub home/secret prio 10"}
          value={value}
          onChange={(e) => onText(e.target.value)}
        />
        <FieldDescription>{t("roles.text.hint")}</FieldDescription>
      </Field>
      {!role.locked && (
        <>
          <AclTextErrors errors={parsed.errors} />
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
            <DefaultActionSelect id="acl-text-defaults" value={defaultAction} onChange={setDefaultAction} />
            <p className="text-sm text-muted-foreground">
              {changes === 0
                ? t("roles.text.noChanges")
                : [
                    diff.added.length > 0 && t("roles.text.added", { count: diff.added.length }),
                    diff.changed.length > 0 && t("roles.text.changed", { count: diff.changed.length }),
                    diff.removed.length > 0 && t("roles.text.removed", { count: diff.removed.length }),
                    diff.unchanged > 0 && t("roles.text.unchanged", { count: diff.unchanged }),
                  ]
                    .filter(Boolean)
                    .join(" · ")}
            </p>
          </div>
          <DiffList diff={diff} />
          {disconnects && <DisconnectHint count={affected.connected} anonymous={affected.anonymous} />}
          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" disabled={text === null} onClick={() => onText(null)}>
              <RotateCcwIcon />
              {t("roles.text.reset")}
            </Button>
            <Button type="button" disabled={save.isPending || changes === 0 || parsed.errors.length > 0} onClick={() => void apply()}>
              {save.isPending && <Spinner />}
              {t("roles.text.apply")}
            </Button>
          </div>
        </>
      )}
    </div>
  )
}
