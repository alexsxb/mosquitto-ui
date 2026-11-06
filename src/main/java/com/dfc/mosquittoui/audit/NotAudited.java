package com.dfc.mosquittoui.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A POST that changes nothing, as it only needs a body: checking a configuration, looking into a backup. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NotAudited {
}
