import createFetchClient, { type Middleware } from "openapi-fetch"
import createClient from "openapi-react-query"

import type { components, paths } from "./schema"

export type Schemas = components["schemas"]

/** Body of error responses. */
interface ErrorBody {
  code?: string
  message?: string
  field?: string
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly field?: string

  constructor(status: number, code: string, message: string, field?: string) {
    super(message)
    this.status = status
    this.code = code
    this.field = field
  }
}

/** Error of a failed response. */
export async function toApiError(response: Response) {
  let body: ErrorBody = {}
  try {
    body = (await response.clone().json()) as ErrorBody
  } catch {
    // not a JSON error body
  }
  return new ApiError(response.status, body.code ?? `http_${response.status}`, body.message ?? response.statusText, body.field)
}

/** Status of the OIDC extension for an expired session, instead of a redirect that fetch cannot follow. */
export const SESSION_EXPIRED = 499

/**
 * Sent with every request to the API. The server refuses changes without it: another site cannot set it without a CORS
 * preflight, which the server never allows (CSRF). The OIDC extension also answers an expired session with 499 instead of a
 * redirect then.
 */
const REQUESTED_WITH = { "X-Requested-With": "JavaScript" } as const

const session: Middleware = {
  onRequest({ request }) {
    request.headers.set("X-Requested-With", REQUESTED_WITH["X-Requested-With"])
    return request
  },
}

/** fetch for what the generated client cannot do (uploads, downloads, plain text): with the session and the header above */
export function apiFetch(url: string, init: Omit<RequestInit, "headers"> & { headers?: Record<string, string> } = {}) {
  return fetch(url, { ...init, credentials: "same-origin", headers: { ...REQUESTED_WITH, ...init.headers } })
}

const errors: Middleware = {
  async onResponse({ request, response }) {
    if (response.ok) {
      return undefined
    }
    // the question who is logged in has an answer: nobody, and the start page shows that
    if (response.status === SESSION_EXPIRED && !new URL(request.url).pathname.endsWith("/api/auth/me")) {
      // through the login of the identity provider and back to this page
      window.location.reload()
    }
    throw await toApiError(response)
  },
}

export const fetchClient = createFetchClient<paths>({ credentials: "same-origin" })
fetchClient.use(session, errors)

export const $api = createClient(fetchClient)
