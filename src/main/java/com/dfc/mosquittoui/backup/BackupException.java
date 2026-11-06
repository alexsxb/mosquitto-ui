package com.dfc.mosquittoui.backup;

/** A backup request that cannot be carried out. */
public class BackupException extends RuntimeException {

    private final String code;
    private final String field;

    private BackupException(String code, String field, String message) {
        super(message);
        this.code = code;
        this.field = field;
    }

    /** Wrong input, field says which. */
    public static BackupException validation(String field, String message) {
        return new BackupException("validation", field, message);
    }

    /** code: job_running, job_not_running, target_in_use. */
    public static BackupException conflict(String code, String message) {
        return new BackupException(code, null, message);
    }

    public static BackupException notFound(String what) {
        return new BackupException("not_found", null, what + " not found");
    }

    public String code() {
        return code;
    }

    public String field() {
        return field;
    }
}
