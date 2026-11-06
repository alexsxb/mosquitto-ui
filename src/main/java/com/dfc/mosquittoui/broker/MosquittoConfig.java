package com.dfc.mosquittoui.broker;

import io.smallrye.config.ConfigMapping;
import java.time.Duration;

/** Connection to the broker. */
@ConfigMapping(prefix = "mosquitto")
public interface MosquittoConfig {

    String host();

    int port();

    boolean tls();

    String username();

    String password();

    /** How long to wait for an answer of the broker. */
    Duration timeout();
}
