package com.dfc.mosquittoui.backup;

import java.time.Instant;

/** One run of a job. success is null as long as it runs. */
public class BackupRun {

    public long id;
    public long jobId;
    public String jobName;
    public Instant startedAt;
    public Instant finishedAt;
    public Boolean success;
    public String message;
    public Long bytes;
    public String fileName;
    public String triggeredBy;
    public String log = "";
}
