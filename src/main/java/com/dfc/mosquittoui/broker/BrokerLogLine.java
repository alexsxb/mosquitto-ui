package com.dfc.mosquittoui.broker;

import java.time.Instant;

/** A line of the log of the broker, as it publishes it with log_dest topic on $SYS/broker/log/<level>. */
public record BrokerLogLine(Instant time, String level, String text) {
}
