import type { Schemas } from "@/api/client"

type Section = Schemas["BackupSection"]

export const allSections: Section[] = ["ACCESS", "RETAINED", "CONFIG", "BRIDGES", "CERTIFICATES", "BACKUPS", "AUDIT"]
export const defaultSections = allSections

/** Parts with secrets: only backed up encrypted. */
export const encryptedOnlySections: Section[] = ["ACCESS", "RETAINED", "BRIDGES", "CERTIFICATES", "BACKUPS", "AUDIT"]
