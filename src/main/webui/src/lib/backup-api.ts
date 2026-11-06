import { apiFetch, toApiError, type Schemas } from "@/api/client"

export type BackupSection = Schemas["BackupSection"]
export type ArchiveInfo = Schemas["ArchiveInfo"]
export type ArchiveRestoreReport = Schemas["ArchiveRestoreReport"]

/** Saves a response body as file, using the name from Content-Disposition. */
async function save(response: Response, fallbackName: string) {
  if (!response.ok) {
    throw await toApiError(response)
  }
  const disposition = response.headers.get("Content-Disposition") ?? ""
  const name = /filename="([^"]+)"/.exec(disposition)?.[1] ?? fallbackName
  const url = URL.createObjectURL(await response.blob())
  const link = document.createElement("a")
  link.href = url
  link.download = name
  link.click()
  URL.revokeObjectURL(url)
}

/** Archive of the chosen parts; with a password encrypted. */
export async function downloadArchive(sections: BackupSection[], password: string | null) {
  await save(
    await apiFetch("/api/backup/archive", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sections, password }),
    }),
    "mosquitto-ui.zip",
  )
}

async function postForm<T>(url: string, form: FormData): Promise<T> {
  const response = await apiFetch(url, { method: "POST", body: form })
  if (!response.ok) {
    throw await toApiError(response)
  }
  return (await response.json()) as T
}

/** Parts of an uploaded backup archive. */
export function inspectArchive(file: File, password: string) {
  const form = new FormData()
  form.append("file", file)
  form.append("password", password)
  return postForm<ArchiveInfo>("/api/backup/restore/inspect", form)
}

/** Restores the chosen parts of an uploaded backup archive. */
export function restoreArchive(file: File, password: string, sections: BackupSection[], trustUnsigned: boolean) {
  const form = new FormData()
  form.append("file", file)
  form.append("password", password)
  form.append("request", JSON.stringify({ sections, trustUnsigned }))
  return postForm<ArchiveRestoreReport>("/api/backup/restore", form)
}
