package com.dfc.mosquittoui.audit;

import java.util.Map;

/**
 * The result of a change that tells the audit log what it did beyond what its request says, such as the rules that replacing all
 * rules of a role added and removed. Whoever implements it decides what goes into the log: never passwords, keys or contents.
 */
public interface AuditDetails {

    Map<String, String> auditDetails();
}
