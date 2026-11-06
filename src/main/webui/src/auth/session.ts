import { $api, fetchClient } from "@/api/client"

/** The handbook of this version in the given language, open to everyone (docs/handbuch, German at the top, en/ and fr/ below) */
export function handbookUrl(language: string | undefined) {
  return language === "en" || language === "fr" ? `/handbuch/${language}/index.html` : "/handbuch/index.html"
}

export function useCurrentUser() {
  return $api.useQuery("get", "/api/auth/me", {}, { staleTime: Infinity })
}

/** Whether the local login is on and which identity providers there are; open to everyone, the start page needs it */
export function useAuthMode() {
  return $api.useQuery("get", "/api/auth/mode", {}, { staleTime: Infinity })
}

/** Where the code flow of a provider starts (and ends) */
export function providerLoginUrl(id: string) {
  return `/auth/sso/${encodeURIComponent(id)}`
}

/** Ends the session; one from a provider that can log out ends there as well, which comes back to the start page */
export async function logout() {
  let redirect: string | null | undefined
  try {
    redirect = (await fetchClient.POST("/api/auth/logout")).data?.redirect
  } catch {
    // the cookie may be gone already
  }
  window.location.href = redirect ?? "/"
}
