import { useQueryClient } from "@tanstack/react-query"
import { useState, type FormEvent } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"

import { $api, type Schemas } from "@/api/client"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Spinner } from "@/components/ui/spinner"
import { useErrorToast } from "@/lib/errors"

export type BlockField = Schemas["BlockField"]

/** A connected client to block, with what the token told about it */
export interface BlockTarget {
  username: string
  sub?: string | null
  jti?: string | null
  issuer?: string | null
  /** End of the token, as an ISO time */
  expires?: string | null
}

const ALL_ISSUERS = "*"

/** A time for datetime-local, in the local zone, with seconds: the block of a token must not end before the token */
function localInput(iso: string | null | undefined) {
  if (!iso) {
    return ""
  }
  const date = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, "0")
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/**
 * Blocks a user name, a subject or a single token. With a target, from the list of clients: the choice is what of it to block,
 * a single token by default until it would have expired anyway.
 */
export function TokenBlockDialog({ target, issuers, onClose }: { target?: BlockTarget; issuers: string[]; onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const block = $api.useMutation("post", "/api/token-auth/blocks")
  const initialField: BlockField = target?.jti ? "jti" : "username"
  const [field, setField] = useState<BlockField>(initialField)
  const [value, setValue] = useState(target ? (initialField === "jti" ? target.jti! : target.username) : "")
  const [issuer, setIssuer] = useState(target?.issuer ?? ALL_ISSUERS)
  const [until, setUntil] = useState(initialField === "jti" ? localInput(target?.expires) : "")
  const [reason, setReason] = useState("")

  function choose(next: BlockField) {
    setField(next)
    if (target) {
      setValue((next === "jti" ? target.jti : next === "sub" ? target.sub : target.username) ?? "")
      // a single token needs no block beyond its own end
      setUntil(next === "jti" ? localInput(target.expires) : "")
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    // not on to a form around the dialog, see the page Token login
    event.stopPropagation()
    try {
      await block.mutateAsync({
        body: {
          field,
          value,
          issuer: issuer === ALL_ISSUERS ? null : issuer,
          until: until ? new Date(until).toISOString() : null,
          reason: reason || null,
        },
      })
      toast.success(t("tokenBlocks.blocked", { value }))
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["get", "/api/token-auth"] }),
        queryClient.invalidateQueries({ queryKey: ["get", "/api/token-auth/clients"] }),
        queryClient.invalidateQueries({ queryKey: ["get", "/api/token-auth/seen"] }),
      ])
      onClose()
    } catch (error) {
      errorToast(error)
    }
  }

  const fields: BlockField[] = target ? (["jti", "username", "sub"] as BlockField[]).filter((f) => f !== "jti" || target.jti).filter((f) => f !== "sub" || target.sub) : ["username", "sub", "jti"]
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <form onSubmit={submit} className="space-y-6">
          <DialogHeader>
            <DialogTitle>{target ? t("tokenBlocks.titleFor", { name: target.username }) : t("tokenBlocks.title")}</DialogTitle>
            <DialogDescription>{t("tokenBlocks.description")}</DialogDescription>
          </DialogHeader>
          <FieldGroup>
            <Field>
              <FieldLabel>{t("tokenBlocks.field")}</FieldLabel>
              <Select value={field} onValueChange={(v) => choose(v as BlockField)}>
                <SelectTrigger className="w-full" aria-label={t("tokenBlocks.field")}>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {fields.map((f) => (
                    <SelectItem key={f} value={f}>
                      {t(`tokenBlocks.fields.${f}`)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <FieldDescription>{t(`tokenBlocks.fieldHints.${field}`)}</FieldDescription>
            </Field>
            <Field>
              <FieldLabel htmlFor="block-value">{t("tokenBlocks.value")}</FieldLabel>
              <Input id="block-value" required className="font-mono text-xs" value={value} onChange={(e) => setValue(e.target.value)} readOnly={Boolean(target)} />
            </Field>
            <Field>
              <FieldLabel>{t("tokenBlocks.issuer")}</FieldLabel>
              <Select value={issuer} onValueChange={setIssuer}>
                <SelectTrigger className="w-full" aria-label={t("tokenBlocks.issuer")}>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={ALL_ISSUERS}>{t("tokenBlocks.allIssuers")}</SelectItem>
                  {issuers.map((i) => (
                    <SelectItem key={i} value={i}>
                      {i}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <Field>
              <FieldLabel htmlFor="block-until">{t("tokenBlocks.until")}</FieldLabel>
              <Input id="block-until" type="datetime-local" step={1} value={until} onChange={(e) => setUntil(e.target.value)} />
              <FieldDescription>{t("tokenBlocks.untilHint")}</FieldDescription>
            </Field>
            <Field>
              <FieldLabel htmlFor="block-reason">{t("tokenBlocks.reason")}</FieldLabel>
              <Input id="block-reason" value={reason} onChange={(e) => setReason(e.target.value)} />
            </Field>
          </FieldGroup>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" variant="destructive" disabled={block.isPending || !value}>
              {block.isPending && <Spinner />}
              {t("tokenBlocks.block")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
