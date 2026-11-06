package com.dfc.mosquittoui.broker;

import com.fasterxml.jackson.databind.JsonNode;

/** Events of a recording of the inspect plugin, as it sends them about every second: { id, events, end }. */
public record TraceBatch(JsonNode batch) {
}
