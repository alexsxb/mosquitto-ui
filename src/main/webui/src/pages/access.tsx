import { useQueryClient } from "@tanstack/react-query"
import { BanIcon, CheckIcon, FlaskConicalIcon, InfoIcon, SlidersHorizontalIcon, XIcon } from "lucide-react"
import { useState, type FormEvent } from "react"
import { useTranslation } from "react-i18next"
import { Link } from "react-router"
import { toast } from "sonner"

import { $api, type Schemas } from "@/api/client"
import { ConfirmDialog } from "@/components/confirm-dialog"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { Spinner } from "@/components/ui/spinner"
import { Switch } from "@/components/ui/switch"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { useErrorToast } from "@/lib/errors"

type Decision = Schemas["AclDecision"]
type Defaults = Schemas["AccessDefaults"]

const accessKey = ["get", "/api/access"]
const groupsKey = ["get", "/api/groups"]
/** Not a name a client can have: the plugin does not allow an empty one, and a select needs a value. */
const ANONYMOUS = "*"
const defaultTypes = ["publishClientSend", "publishClientReceive", "subscribe", "unsubscribe"] as const

/** Clients without a login only exist if the broker lets them in; null as long as the configuration is not known. */
function useAnonymousAllowed() {
  const config = $api.useQuery("get", "/api/config")
  return config.data?.available ? config.data.form.options.allow_anonymous === "true" : null
}

function AnonymousOffHint() {
  const { t } = useTranslation()
  return (
    <Alert>
      <InfoIcon />
      <AlertDescription>
        <span>
          {t("access.anonymousOff")}{" "}
          <Link to="/config" className="underline underline-offset-4">
            {t("nav.config")}
          </Link>
        </span>
      </AlertDescription>
    </Alert>
  )
}

function Verdict({ allowed }: { allowed: boolean | null }) {
  const { t } = useTranslation()
  if (allowed === null) {
    return <Badge variant="outline">{t("access.impossible")}</Badge>
  }
  return allowed ? (
    <Badge variant="secondary">
      <CheckIcon />
      {t("access.allowed")}
    </Badge>
  ) : (
    <Badge variant="destructive">
      <XIcon />
      {t("access.denied")}
    </Badge>
  )
}

function useReason() {
  const { t } = useTranslation()
  return (decision: Decision) => {
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

function TesterCard() {
  const { t } = useTranslation()
  const errorToast = useErrorToast()
  const clients = $api.useQuery("get", "/api/clients")
  const test = $api.useMutation("post", "/api/access/test")
  const anonymousAllowed = useAnonymousAllowed()
  const reason = useReason()

  const [username, setUsername] = useState(ANONYMOUS)
  const [topic, setTopic] = useState("")
  const [clientId, setClientId] = useState("")
  /** The answer stays with the question it belongs to, while the form changes. */
  const [tested, setTested] = useState<string | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    try {
      await test.mutateAsync({ body: { username: username === ANONYMOUS ? null : username, clientId, topic } })
      setTested(username)
    } catch (error) {
      errorToast(error)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("access.testTitle")}</CardTitle>
        <CardDescription>{t("access.testDesc")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <form onSubmit={submit}>
          <FieldGroup>
            <div className="grid gap-4 md:grid-cols-3">
              <Field>
                <FieldLabel htmlFor="access-client">{t("access.client")}</FieldLabel>
                <Select value={username} onValueChange={setUsername}>
                  <SelectTrigger id="access-client">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={ANONYMOUS}>{t("access.anonymous")}</SelectItem>
                    {(clients.data ?? []).map((client) => (
                      <SelectItem key={client.username} value={client.username}>
                        {client.username}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </Field>
              <Field>
                <FieldLabel htmlFor="access-topic">{t("access.topic")}</FieldLabel>
                <Input id="access-topic" required className="font-mono" placeholder="sensors/+/temperature" value={topic} onChange={(e) => setTopic(e.target.value)} />
              </Field>
              <Field>
                <FieldLabel htmlFor="access-client-id">{t("access.clientId")}</FieldLabel>
                <Input id="access-client-id" className="font-mono" value={clientId} onChange={(e) => setClientId(e.target.value)} />
                <FieldDescription>{t("access.clientIdHint")}</FieldDescription>
              </Field>
            </div>
            <div>
              <Button type="submit" disabled={test.isPending}>
                {test.isPending && <Spinner />}
                {t("access.test")}
              </Button>
            </div>
          </FieldGroup>
        </form>

        {test.data && tested !== null && (
          <>
            {test.data.disabled && (
              <Alert>
                <BanIcon />
                <AlertDescription>{t("access.disabled")}</AlertDescription>
              </Alert>
            )}
            {tested === ANONYMOUS && anonymousAllowed === false && <AnonymousOffHint />}
            <div className="rounded-lg border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("access.action")}</TableHead>
                    <TableHead>{t("access.result")}</TableHead>
                    <TableHead className="hidden md:table-cell">{t("access.reason")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {test.data.decisions.map((decision) => (
                    <TableRow key={decision.access}>
                      <TableCell className="whitespace-normal">
                        <div className="font-medium">{t(`access.types.${decision.access as (typeof defaultTypes)[number]}`)}</div>
                        <p className="text-sm break-words text-muted-foreground md:hidden">{reason(decision)}</p>
                      </TableCell>
                      <TableCell>
                        <Verdict allowed={decision.allowed} />
                      </TableCell>
                      <TableCell className="hidden whitespace-normal text-muted-foreground md:table-cell">{reason(decision)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  )
}

function DefaultsCard({ current }: { current: Defaults }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const save = $api.useMutation("put", "/api/access/defaults")
  const [defaults, setDefaults] = useState(current)
  const [confirming, setConfirming] = useState(false)
  const changed = defaultTypes.some((type) => defaults[type] !== current[type])

  async function apply() {
    setConfirming(false)
    try {
      await save.mutateAsync({ body: defaults })
      toast.success(t("access.saved"))
      await queryClient.invalidateQueries({ queryKey: accessKey })
    } catch (error) {
      errorToast(error)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("access.defaultsTitle")}</CardTitle>
        <CardDescription>{t("access.defaultsDesc")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="divide-y rounded-lg border">
          {defaultTypes.map((type) => (
            <div key={type} className="flex items-center justify-between gap-4 p-3">
              <div className="min-w-0">
                <div className="text-sm font-medium">{t(`access.types.${type}`)}</div>
                <p className="text-sm text-muted-foreground">{t(`access.typeHints.${type}`)}</p>
              </div>
              <Switch aria-label={t(`access.types.${type}`)} checked={defaults[type]} onCheckedChange={(allowed) => setDefaults({ ...defaults, [type]: allowed })} />
            </div>
          ))}
        </div>
        <Button disabled={!changed || save.isPending} onClick={() => setConfirming(true)}>
          {save.isPending && <Spinner />}
          {t("common.save")}
        </Button>
      </CardContent>
      <ConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title={t("access.confirmDefaultsTitle")}
        description={t("access.confirmDefaultsDesc")}
        confirmLabel={t("common.save")}
        destructive={false}
        onConfirm={() => void apply()}
      />
    </Card>
  )
}

function AnonymousGroupCard({ current }: { current: string }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const errorToast = useErrorToast()
  const groups = $api.useQuery("get", "/api/groups")
  const save = $api.useMutation("put", "/api/access/anonymous-group")
  const anonymousAllowed = useAnonymousAllowed()
  const [groupname, setGroupname] = useState(current)
  const [confirming, setConfirming] = useState(false)
  // the rights of this application would go to everyone
  const choices = (groups.data ?? []).filter((group) => !group.locked)

  async function apply() {
    setConfirming(false)
    try {
      await save.mutateAsync({ body: { groupname } })
      toast.success(t("access.saved"))
      await Promise.all([queryClient.invalidateQueries({ queryKey: accessKey }), queryClient.invalidateQueries({ queryKey: groupsKey })])
    } catch (error) {
      errorToast(error)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("access.anonymousTitle")}</CardTitle>
        <CardDescription>{t("access.anonymousDesc")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {anonymousAllowed === false && <AnonymousOffHint />}
        <Field className="max-w-sm">
          <FieldLabel htmlFor="access-anonymous-group">{t("access.anonymousGroup")}</FieldLabel>
          <Select value={groupname} onValueChange={setGroupname}>
            <SelectTrigger id="access-anonymous-group">
              <SelectValue placeholder={t("access.anonymousNone")} />
            </SelectTrigger>
            <SelectContent>
              {choices.map((group) => (
                <SelectItem key={group.groupname} value={group.groupname}>
                  {group.groupname}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <FieldDescription>{t("access.anonymousHint")}</FieldDescription>
        </Field>
        <Button disabled={groupname === current || !groupname || save.isPending} onClick={() => setConfirming(true)}>
          {save.isPending && <Spinner />}
          {t("common.save")}
        </Button>
      </CardContent>
      <ConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title={t("access.confirmAnonymousTitle", { group: groupname })}
        description={t("access.confirmAnonymousDesc")}
        confirmLabel={t("common.save")}
        destructive={false}
        onConfirm={() => void apply()}
      />
    </Card>
  )
}

/** What a client may do, and what holds for everyone. */
export function AccessPage() {
  const { t } = useTranslation()
  const access = $api.useQuery("get", "/api/access")

  return (
    <Tabs defaultValue="test" className="gap-4">
      <TabsList className="w-full justify-start overflow-x-auto group-data-horizontal/tabs:h-10">
        <TabsTrigger value="test">
          <FlaskConicalIcon />
          {t("access.tabs.test")}
        </TabsTrigger>
        <TabsTrigger value="defaults">
          <SlidersHorizontalIcon />
          {t("access.tabs.defaults")}
        </TabsTrigger>
      </TabsList>
      <TabsContent value="test">
        <TesterCard />
      </TabsContent>
      <TabsContent value="defaults" className="space-y-4">
        {access.data ? (
          <>
            <DefaultsCard key={JSON.stringify(access.data.defaults)} current={access.data.defaults} />
            <AnonymousGroupCard key={access.data.anonymousGroup} current={access.data.anonymousGroup} />
          </>
        ) : (
          <Skeleton className="h-64 w-full" />
        )}
      </TabsContent>
    </Tabs>
  )
}
