package com.dfc.mosquittoui.audit;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PathParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Writes down who changed what. Only path parameters and the fields named here are taken from a request: a new field stays out unless
 * someone decides otherwise, so passwords, keys, configurations and the values of messages never get into the log.
 */
@Audited
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_AFTER)
public class AuditInterceptor {

    private static final Logger LOG = Logger.getLogger(AuditInterceptor.class);
    private static final Set<String> FIELDS = Set.of("username", "groupname", "rolename", "name", "topic", "acltype", "allow", "enabled", "address", "host", "sections",
            "config", "bridges", "certificate", "access", "backups", "auth", "retained", "conf", "retain", "publishClientSend", "publishClientReceive", "subscribe", "unsubscribe");
    private static final String ERROR = "error";

    @Inject
    AuditLog log;

    @Inject
    SecurityIdentity identity;

    @AroundInvoke
    Object record(InvocationContext context) throws Exception {
        if (context.getMethod().isAnnotationPresent(GET.class) || context.getMethod().isAnnotationPresent(NotAudited.class)) {
            return context.proceed();
        }
        String action = context.getMethod().getName();
        Map<String, String> details = details(context);
        try {
            Object result = context.proceed();
            log.record(identity.getPrincipal().getName(), action, details, reportedError(result));
            return result;
        } catch (Exception e) {
            log.record(identity.getPrincipal().getName(), action, details, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            throw e;
        }
    }

    private static Map<String, String> details(InvocationContext context) {
        Map<String, String> details = new LinkedHashMap<>();
        Annotation[][] annotations = context.getMethod().getParameterAnnotations();
        Object[] parameters = context.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            for (Annotation annotation : annotations[i]) {
                if (annotation instanceof PathParam path) {
                    details.put(path.value(), String.valueOf(parameters[i]));
                }
            }
            if (parameters[i] != null && parameters[i].getClass().isRecord()) {
                addFields(parameters[i], details);
            }
        }
        return details;
    }

    private static Object read(RecordComponent component, Object record) {
        try {
            return component.getAccessor().invoke(record);
        } catch (IllegalAccessException | InvocationTargetException e) {
            LOG.debugf("No access to %s: %s", component.getName(), e.getMessage());
            return null;
        }
    }

    private static void addFields(Object record, Map<String, String> details) {
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            Object value = read(component, record);
            boolean plain = value instanceof String || value instanceof Boolean || value instanceof Collection<?> list && list.stream().allMatch(Enum.class::isInstance);
            if (FIELDS.contains(component.getName()) && plain) {
                details.putIfAbsent(component.getName(), String.valueOf(value));
            }
        }
    }

    /** Some operations tell in their answer that they stopped halfway, with a status of 200. */
    private static String reportedError(Object result) {
        if (result != null && result.getClass().isRecord()) {
            for (RecordComponent component : result.getClass().getRecordComponents()) {
                if (component.getName().equals(ERROR) && component.getType() == String.class) {
                    return (String) read(component, result);
                }
            }
        }
        return null;
    }
}
