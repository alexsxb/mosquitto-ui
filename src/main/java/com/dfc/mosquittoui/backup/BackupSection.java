package com.dfc.mosquittoui.backup;

/**
 * Parts of a backup, restored in this order: the configuration can name a certificate, the UI has to reach the broker throughout, and
 * retained messages come before the bridges, which would pass them on.
 */
public enum BackupSection {
    /** All certificate sets with their keys, API tokens and account keys. */
    CERTIFICATES(true),
    /** Clients, groups and roles with their password hashes, the identity providers of the token login, and the login of the UI:
     * its identity providers and users (password hashes). */
    ACCESS(true),
    /** Retained messages, read from the running broker. They can hold anything, so they count as secret. */
    RETAINED(true),
    /** The bridges with their passwords, and the certificates of GX devices. */
    BRIDGES(true),
    /** Options and listeners of the UI. */
    CONFIG(false),
    /** Targets with their passwords, jobs and settings of the backups themselves, the mail server and who gets told what. */
    BACKUPS(true),
    /** Who changed what. Names and targets, and error messages as they came. */
    AUDIT(true);

    private final boolean encryptedOnly;

    BackupSection(boolean encryptedOnly) {
        this.encryptedOnly = encryptedOnly;
    }

    /** Whether the part holds secrets, which only go into encrypted backups. */
    public boolean encryptedOnly() {
        return encryptedOnly;
    }
}
