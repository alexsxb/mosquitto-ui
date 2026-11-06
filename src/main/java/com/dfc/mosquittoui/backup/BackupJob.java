package com.dfc.mosquittoui.backup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** What is backed up, where to, when and how it is encrypted. */
public class BackupJob {

    public enum Schedule { MANUAL, DAILY, WEEKLY, MONTHLY }

    public enum Encryption { NONE, PASSWORD, GPG }

    public long id;
    public String name;
    public boolean enabled;
    public Set<BackupSection> sections = EnumSet.noneOf(BackupSection.class);
    public long targetId;
    public Schedule schedule;
    /** HH:mm in the time zone of the settings. */
    public String time;
    /** 1 is Monday. */
    public Integer weekday;
    public Integer monthDay;
    public Encryption encryption;
    public String password;
    /** Armored public keys. */
    public List<String> gpgKeys = new ArrayList<>();
    public Instant createdAt;
}
