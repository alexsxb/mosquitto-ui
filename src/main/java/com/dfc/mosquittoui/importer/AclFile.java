package com.dfc.mosquittoui.importer;

import com.dfc.mosquittoui.api.Dtos.Acl;
import com.dfc.mosquittoui.api.Dtos.ImportWarning;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The rules of an acl_file, as rules of the dynamic security plugin. */
public final class AclFile {

    /** Above allowing rules, as in an acl_file, where a deny always wins. */
    static final int DENY_PRIORITY = 10;

    /** Rules before the first "user" line. */
    public final List<Acl> anonymous = new ArrayList<>();
    /** "pattern" rules, they apply to every user. */
    public final List<Acl> patterns = new ArrayList<>();
    public final Map<String, List<Acl>> users = new LinkedHashMap<>();

    private AclFile() {
    }

    public static AclFile parse(String text, List<ImportWarning> warnings) {
        AclFile file = new AclFile();
        List<Acl> current = file.anonymous;
        if (text == null) {
            return file;
        }
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+", 2);
            if (parts.length < 2) {
                warnings.add(new ImportWarning("invalid_line", line));
            } else if (parts[0].equals("user")) {
                current = file.users.computeIfAbsent(parts[1].strip(), user -> new ArrayList<>());
            } else if (parts[0].equals("topic")) {
                current.addAll(rules(parts[1]));
            } else if (parts[0].equals("pattern")) {
                file.patterns.addAll(rules(parts[1]));
            } else {
                warnings.add(new ImportWarning("invalid_line", line));
            }
        }
        return file;
    }

    /** "[read|write|readwrite|deny] topic"; topics may contain spaces. */
    private static List<Acl> rules(String rule) {
        String[] parts = rule.strip().split("\\s+", 2);
        String access = parts.length == 2 && List.of("read", "write", "readwrite", "deny").contains(parts[0]) ? parts[0] : "readwrite";
        String topic = access.equals(parts[0]) && parts.length == 2 ? parts[1].strip() : rule.strip();
        List<Acl> acls = new ArrayList<>();
        boolean allow = !access.equals("deny");
        int priority = allow ? 0 : DENY_PRIORITY;
        if (!access.equals("write")) {
            acls.add(new Acl("subscribePattern", topic, priority, allow));
            acls.add(new Acl("publishClientReceive", topic, priority, allow));
        }
        if (!access.equals("read")) {
            acls.add(new Acl("publishClientSend", topic, priority, allow));
        }
        return acls;
    }
}
