package com.dfc.mosquittoui.audit;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** On a resource: what its methods change is logged, see {@link AuditInterceptor}. On the class, so that a new method cannot be forgotten. */
@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {
}
