package com.dfc.mosquittoui.backup;

import java.time.Instant;

/** Where backups go. */
public class BackupTarget {

    public enum Type { SFTP, FTP, LOCAL, S3, EMAIL }

    public long id;
    public String name;
    public Type type;
    public String host;
    public int port;
    public String username;
    public String password;
    public String path;
    public boolean tls;
    /** SFTP: the key of the server, learned on the first contact. FTPS: a certificate trusted by its fingerprint. */
    public String hostKey;
    public String region;
    public String bucket;
    /** E-mail: comma-separated. */
    public String recipients;
    public Integer maxMb;
    /** 0: backups stay. */
    public int retentionDays;
    public Instant createdAt;
}
