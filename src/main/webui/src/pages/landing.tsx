import { useQueryClient } from "@tanstack/react-query"
import { ArrowRightIcon, BookOpenIcon, LogInIcon } from "lucide-react"
import { useState, type FormEvent } from "react"
import { useTranslation } from "react-i18next"

import { ApiError, fetchClient } from "@/api/client"
import { handbookUrl, providerLoginUrl, useAuthMode } from "@/auth/session"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Spinner } from "@/components/ui/spinner"

import { LanguageSwitcher } from "@/components/language-switcher"
import { Logo } from "@/components/logo"
import { ThemeToggle } from "@/components/theme-toggle"

const HUB = { x: 280, y: 230 }

/** Drawing only: topics around a broker, not data of this broker. */
const NODES = [
  { x: 92, y: 70, topic: "haus/keller/temp", bend: [150, 190] },
  { x: 438, y: 58, topic: "victron/battery/soc", bend: [400, 170] },
  { x: 500, y: 236, topic: "garten/pumpe/set", bend: [390, 280] },
  { x: 426, y: 404, topic: "$SYS/broker/uptime", bend: [320, 340] },
  { x: 120, y: 396, topic: "tor/status", bend: [210, 330] },
  { x: 64, y: 232, topic: "licht/flur/state", bend: [170, 260] },
]

function BrokerDrawing() {
  return (
    <svg viewBox="0 0 560 460" aria-hidden="true" className="w-full max-w-xl overflow-visible">
      {[70, 118].map((r, i) => (
        <circle key={r} cx={HUB.x} cy={HUB.y} r={r} className="landing-pulse" style={{ animationDelay: `${i * 1.5}s` }} />
      ))}
      {NODES.map((node, i) => {
        const path = `M${HUB.x} ${HUB.y} Q${node.bend[0]} ${node.bend[1]} ${node.x} ${node.y}`
        return (
          <g key={node.topic}>
            <path d={path} className="landing-wire" />
            <path d={path} pathLength={100} className="landing-signal" style={{ animationDelay: `${i * 0.7}s` }} />
            <path d={path} pathLength={100} className="landing-signal landing-signal-back" style={{ animationDelay: `${i * 0.7 + 1.9}s` }} />
            <circle cx={node.x} cy={node.y} r={7} className="landing-node" />
            <text x={node.x} y={node.y + 26} textAnchor="middle" className="landing-topic">
              {node.topic}
            </text>
          </g>
        )
      })}
      <circle cx={HUB.x} cy={HUB.y} r={46} className="landing-hub" />
      <foreignObject x={HUB.x - 26} y={HUB.y - 26} width={52} height={52}>
        <Logo className="size-13" />
      </foreignObject>
    </svg>
  )
}

/** The local login: user name and password, the session cookie comes with the answer */
function LoginForm() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [username, setUsername] = useState("")
  const [password, setPassword] = useState("")
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await fetchClient.POST("/api/auth/login", { body: { username, password } })
      // who is logged in now: the app instead of this page
      await queryClient.invalidateQueries()
    } catch (e) {
      setPassword("")
      setError(e instanceof ApiError && e.code === "too_many_attempts" ? t("landing.tooMany") : e instanceof ApiError && e.code === "login_failed" ? t("landing.failed") : t("errors.generic"))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={(e) => void submit(e)} className="max-w-sm">
      <FieldGroup>
        <Field>
          <FieldLabel htmlFor="login-user">{t("landing.username")}</FieldLabel>
          <Input id="login-user" autoComplete="username" autoFocus required value={username} onChange={(e) => setUsername(e.target.value)} />
        </Field>
        <Field>
          <FieldLabel htmlFor="login-password">{t("landing.password")}</FieldLabel>
          <Input id="login-password" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        </Field>
        {error && (
          <Alert variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}
        <Button type="submit" size="lg" disabled={busy}>
          {busy ? <Spinner /> : <ArrowRightIcon />}
          {t("landing.login")}
        </Button>
      </FieldGroup>
    </form>
  )
}

/** What came back from a provider: turned away (sso-denied, who), or an error of the provider (error, from the OIDC extension) */
function ProviderMessage() {
  const { t } = useTranslation()
  const query = new URLSearchParams(window.location.search)
  const denied = query.get("sso-denied")
  const error = query.get("error") ?? query.get("sso-error")
  if (!denied && !error) {
    return null
  }
  return (
    <Alert variant="destructive" className="max-w-sm">
      <AlertDescription>
        {denied
          ? t("landing.denied", { provider: denied, who: query.get("who") ?? "?" })
          : error === "unreachable"
            ? t("landing.unreachable")
            : t("landing.providerError", { error })}
      </AlertDescription>
    </Alert>
  )
}

/** A button per identity provider, each starts the code flow of its provider */
function ProviderButtons({ providers, primary }: { providers: { id: string; name: string }[]; primary: boolean }) {
  const { t } = useTranslation()
  return (
    <div className="grid max-w-sm gap-2">
      {providers.map((provider, i) =>
        primary && i === 0 ? (
          <a key={provider.id} href={providerLoginUrl(provider.id)} className="landing-button group">
            {t("landing.loginWith", { provider: provider.name })}
            <ArrowRightIcon className="size-5 transition-transform group-hover:translate-x-1" />
          </a>
        ) : (
          <Button key={provider.id} variant="outline" size="lg" asChild>
            <a href={providerLoginUrl(provider.id)}>
              <LogInIcon />
              {t("landing.loginWith", { provider: provider.name })}
            </a>
          </Button>
        ),
      )}
    </div>
  )
}

/** For visitors without a session: the form of the local login when it is on, and a button per identity provider. */
export function LandingPage() {
  const { t, i18n } = useTranslation()
  const mode = useAuthMode()

  return (
    <div className="landing relative flex min-h-svh flex-col overflow-hidden">
      <header className="relative flex items-center justify-between px-4 py-4 sm:px-8">
        <div className="flex items-center gap-2 font-medium">
          <Logo className="size-7" />
          mosquitto-ui
        </div>
        <div className="flex items-center gap-1">
          <Button variant="ghost" size="sm" asChild>
            <a href={handbookUrl(i18n.resolvedLanguage)} target="_blank" rel="noopener">
              <BookOpenIcon />
              <span className="hidden sm:inline">{t("auth.handbook")}</span>
            </a>
          </Button>
          <LanguageSwitcher />
          <ThemeToggle />
        </div>
      </header>

      <main className="relative mx-auto grid w-full max-w-6xl flex-1 items-center gap-8 px-4 py-8 sm:px-8 lg:grid-cols-[1fr_1.1fr] lg:gap-12">
        <div className="animate-in fade-in slide-in-from-bottom-4 fill-mode-both duration-700">
          <h1 className="text-4xl font-semibold tracking-tight text-balance sm:text-6xl">
            {t("landing.title")} <span className="landing-accent-text">{t("landing.titleAccent")}</span>
          </h1>
          <p className="mt-5 max-w-lg text-lg text-pretty text-muted-foreground">{t("landing.subtitle")}</p>
          {mode.data && (
            <div className="mt-8 grid gap-6">
              <ProviderMessage />
              {mode.data.local && <LoginForm />}
              {mode.data.providers.length > 0 && (
                <>
                  {mode.data.local && <p className="text-sm text-muted-foreground">{t("landing.or")}</p>}
                  <ProviderButtons providers={mode.data.providers} primary={!mode.data.local} />
                </>
              )}
            </div>
          )}
        </div>
        <div className="flex animate-in justify-center fade-in fill-mode-both delay-200 duration-1000">
          <BrokerDrawing />
        </div>
      </main>
    </div>
  )
}
