/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Alexander Schlenker
 *
 * Message rules of the inspect plugin: what a message has to look like, and how many a client may send. See rules.c.
 */
#ifndef INSPECT_RULES_H
#define INSPECT_RULES_H

#include <stdbool.h>
#include <stdint.h>

#include <cjson/cJSON.h>

/* What a client used of its rate limit; kept with the client by the plugin */
struct rules_client {
	double tokens;
	double refilled;
	const void *rate_rule;
	uint64_t dropped;
};

/* The verdict on a message: refused when reason is set */
struct rules_verdict {
	const char *reason;
	const char *rule_id;
	bool rate;
	bool disconnect;
};

void rules_init(const char *path);
void rules_cleanup(void);
/* Reads the file again when it changed; called about every second */
void rules_reload(void);
/* Whether a message may pass; a refused one is counted with its rule */
struct rules_verdict rules_check(const char *clientid, const char *username, const char *topic, const void *payload, uint32_t len,
		struct rules_client *state);
/* { "loaded", "error", "content": [ { id, dropped, lastAt, lastClient, lastReason } ], "rate": [ ... ] } */
cJSON *rules_stats(void);

#endif
