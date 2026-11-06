/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Alexander Schlenker
 *
 * Message rules: the broker refuses a message that does not hold, before any other plugin or subscriber sees it.
 *
 * The UI writes them to the file of plugin_opt_rules_file; it is read again within a second when it changes, and a file that
 * cannot be read leaves the rules before in force. No file: no rules.
 *
 *   { "exemptUsernames": [ "admin", "ui-import-*" ], never checked: the users of the UI, which restores retained messages;
 *                                                 with a trailing * the user names that begin like this
 *     "content": [ { "id": "r1", "topic": "shelly/#", "clients": "sensor-*" (optional),
 *                    "json": true, "maxBytes": 4096 (optional, 0 none),
 *                    "fields": [ { "path": "aenergy.total", "type": "number" } ] (optional),
 *                    "action": "drop" | "disconnect" } ],
 *     "rate": [ { "id": "l1", "clients": "*", "perSecond": 20, "burst": 50 (optional, else perSecond),
 *                 "action": "drop" | "disconnect" } ] }
 *
 * clients: empty or * every client, with a trailing * the client ids that begin like this, else exactly this one. A message
 * has to hold for every content rule whose topic filter and clients match it. Of the rate rules the first that matches the
 * client counts, as a bucket of burst messages that fills up with perSecond: an empty bucket refuses. The types of a field:
 * number, string, boolean, object, array, null, or any (only that it is there); the path goes through objects, by dots.
 *
 * A refused message counts with its rule (dropped, the last time, client and reason); with disconnect, the plugin also
 * disconnects the client.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>

#include "mosquitto.h"
#include "rules.h"

#define MAX_RULES 200
#define MAX_FIELDS 32
#define MAX_FILE (1024 * 1024)

enum field_type { T_ANY, T_NUMBER, T_STRING, T_BOOLEAN, T_OBJECT, T_ARRAY, T_NULL };

static const char *TYPE_NAMES[] = { "any", "number", "string", "boolean", "object", "array", "null" };

struct field {
	char *path;
	enum field_type type;
};

struct clients {
	char *pattern;
	bool prefix;
};

struct counter {
	uint64_t dropped;
	time_t last_at;
	char *last_client;
	char last_reason[160];
};

struct content_rule {
	char *id;
	char *topic;
	struct clients clients;
	bool json;
	uint32_t max_bytes;
	struct field fields[MAX_FIELDS];
	int field_count;
	bool disconnect;
	struct counter counter;
};

struct rate_rule {
	char *id;
	struct clients clients;
	double per_second;
	double burst;
	bool disconnect;
	struct counter counter;
};

struct ruleset {
	char **exempt;
	int exempt_count;
	struct content_rule *content;
	int content_count;
	struct rate_rule *rate;
	int rate_count;
};

static struct {
	char *path;
	struct ruleset *rules;
	time_t mtime;
	off_t size;
	bool loaded;
	char error[200];
	char reason[160];
} state;

static double monotonic(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}

static void free_ruleset(struct ruleset *r)
{
	if(!r){
		return;
	}
	for(int i = 0; i < r->exempt_count; i++){
		free(r->exempt[i]);
	}
	free(r->exempt);
	for(int i = 0; i < r->content_count; i++){
		struct content_rule *c = &r->content[i];
		free(c->id);
		free(c->topic);
		free(c->clients.pattern);
		free(c->counter.last_client);
		for(int f = 0; f < c->field_count; f++){
			free(c->fields[f].path);
		}
	}
	free(r->content);
	for(int i = 0; i < r->rate_count; i++){
		free(r->rate[i].id);
		free(r->rate[i].clients.pattern);
		free(r->rate[i].counter.last_client);
	}
	free(r->rate);
	free(r);
}

static void read_clients(cJSON *j, struct clients *c)
{
	const char *pattern = cJSON_GetStringValue(cJSON_GetObjectItem(j, "clients"));
	if(!pattern || !*pattern || !strcmp(pattern, "*")){
		c->pattern = NULL;
		return;
	}
	size_t len = strlen(pattern);
	c->prefix = pattern[len - 1] == '*';
	c->pattern = strndup(pattern, c->prefix ? len - 1 : len);
}

static bool clients_match(const struct clients *c, const char *clientid);

/* An exempt user name: exactly, or with a trailing * by its beginning */
static bool exempt(const struct ruleset *r, const char *username)
{
	for(int i = 0; username && i < r->exempt_count; i++){
		size_t len = strlen(r->exempt[i]);
		if(len > 0 && r->exempt[i][len - 1] == '*' ? !strncmp(username, r->exempt[i], len - 1) : !strcmp(username, r->exempt[i])){
			return true;
		}
	}
	return false;
}

static bool clients_match(const struct clients *c, const char *clientid)
{
	if(!c->pattern){
		return true;
	}
	if(!clientid){
		return false;
	}
	return c->prefix ? !strncmp(clientid, c->pattern, strlen(c->pattern)) : !strcmp(clientid, c->pattern);
}

static bool disconnects(cJSON *j)
{
	const char *action = cJSON_GetStringValue(cJSON_GetObjectItem(j, "action"));
	return action && !strcmp(action, "disconnect");
}

static char *id_of(cJSON *j, const char *kind, int index)
{
	const char *id = cJSON_GetStringValue(cJSON_GetObjectItem(j, "id"));
	if(id && *id){
		return strdup(id);
	}
	char fallback[32];
	snprintf(fallback, sizeof(fallback), "%s-%d", kind, index + 1);
	return strdup(fallback);
}

/* The rules of a file, or NULL with the reason in state.error */
static struct ruleset *parse(cJSON *tree)
{
	struct ruleset *r = calloc(1, sizeof(*r));
	if(!r){
		snprintf(state.error, sizeof(state.error), "out of memory");
		return NULL;
	}
	cJSON *exempt = cJSON_GetObjectItem(tree, "exemptUsernames");
	int n = cJSON_IsArray(exempt) ? cJSON_GetArraySize(exempt) : 0;
	if(n > 0){
		r->exempt = calloc((size_t)n, sizeof(char *));
		for(int i = 0; r->exempt && i < n; i++){
			const char *name = cJSON_GetStringValue(cJSON_GetArrayItem(exempt, i));
			if(name){
				r->exempt[r->exempt_count++] = strdup(name);
			}
		}
	}

	cJSON *content = cJSON_GetObjectItem(tree, "content");
	n = cJSON_IsArray(content) ? cJSON_GetArraySize(content) : 0;
	if(n > MAX_RULES){
		snprintf(state.error, sizeof(state.error), "more than %d content rules", MAX_RULES);
		free_ruleset(r);
		return NULL;
	}
	r->content = calloc((size_t)(n > 0 ? n : 1), sizeof(struct content_rule));
	for(int i = 0; r->content && i < n; i++){
		cJSON *j = cJSON_GetArrayItem(content, i);
		const char *topic = cJSON_GetStringValue(cJSON_GetObjectItem(j, "topic"));
		if(!topic || mosquitto_sub_topic_check(topic) != MOSQ_ERR_SUCCESS){
			snprintf(state.error, sizeof(state.error), "content rule %d: no valid topic filter", i + 1);
			free_ruleset(r);
			return NULL;
		}
		struct content_rule *c = &r->content[r->content_count++];
		c->id = id_of(j, "content", i);
		c->topic = strdup(topic);
		read_clients(j, &c->clients);
		c->json = cJSON_IsTrue(cJSON_GetObjectItem(j, "json"));
		cJSON *max = cJSON_GetObjectItem(j, "maxBytes");
		c->max_bytes = cJSON_IsNumber(max) && max->valuedouble > 0 ? (uint32_t)max->valuedouble : 0;
		c->disconnect = disconnects(j);
		cJSON *fields = cJSON_GetObjectItem(j, "fields");
		int fn = cJSON_IsArray(fields) ? cJSON_GetArraySize(fields) : 0;
		for(int f = 0; f < fn && c->field_count < MAX_FIELDS; f++){
			cJSON *jf = cJSON_GetArrayItem(fields, f);
			const char *path = cJSON_GetStringValue(cJSON_GetObjectItem(jf, "path"));
			const char *type = cJSON_GetStringValue(cJSON_GetObjectItem(jf, "type"));
			if(!path || !*path){
				continue;
			}
			enum field_type t = T_ANY;
			for(int k = 0; type && k <= T_NULL; k++){
				if(!strcmp(type, TYPE_NAMES[k])){
					t = (enum field_type)k;
				}
			}
			c->fields[c->field_count].path = strdup(path);
			c->fields[c->field_count].type = t;
			c->field_count++;
		}
	}

	cJSON *rate = cJSON_GetObjectItem(tree, "rate");
	n = cJSON_IsArray(rate) ? cJSON_GetArraySize(rate) : 0;
	if(n > MAX_RULES){
		snprintf(state.error, sizeof(state.error), "more than %d rate rules", MAX_RULES);
		free_ruleset(r);
		return NULL;
	}
	r->rate = calloc((size_t)(n > 0 ? n : 1), sizeof(struct rate_rule));
	for(int i = 0; r->rate && i < n; i++){
		cJSON *j = cJSON_GetArrayItem(rate, i);
		cJSON *per = cJSON_GetObjectItem(j, "perSecond");
		if(!cJSON_IsNumber(per) || per->valuedouble <= 0){
			snprintf(state.error, sizeof(state.error), "rate rule %d: perSecond missing", i + 1);
			free_ruleset(r);
			return NULL;
		}
		struct rate_rule *l = &r->rate[r->rate_count++];
		l->id = id_of(j, "rate", i);
		read_clients(j, &l->clients);
		l->per_second = per->valuedouble;
		cJSON *burst = cJSON_GetObjectItem(j, "burst");
		l->burst = cJSON_IsNumber(burst) && burst->valuedouble >= 1 ? burst->valuedouble : (l->per_second >= 1 ? l->per_second : 1);
		l->disconnect = disconnects(j);
	}
	if(!r->content || !r->rate){
		snprintf(state.error, sizeof(state.error), "out of memory");
		free_ruleset(r);
		return NULL;
	}
	return r;
}

/* What a rule counted so far goes on with the rule of the same id */
static void keep_counter(struct counter *to, const char *id, bool content)
{
	struct ruleset *old = state.rules;
	if(!old){
		return;
	}
	int count = content ? old->content_count : old->rate_count;
	for(int i = 0; i < count; i++){
		struct counter *from = content ? &old->content[i].counter : &old->rate[i].counter;
		const char *old_id = content ? old->content[i].id : old->rate[i].id;
		if(!strcmp(old_id, id)){
			*to = *from;
			from->last_client = NULL;
			return;
		}
	}
}

void rules_reload(void)
{
	struct stat st;
	if(!state.path){
		return;
	}
	if(stat(state.path, &st) != 0){
		if(state.rules || state.loaded){
			mosquitto_log_printf(MOSQ_LOG_NOTICE, "inspect: %s is gone, no message rules.", state.path);
			free_ruleset(state.rules);
			state.rules = NULL;
			state.loaded = false;
		}
		state.mtime = 0;
		state.size = 0;
		return;
	}
	if(st.st_mtime == state.mtime && st.st_size == state.size){
		return;
	}
	state.mtime = st.st_mtime;
	state.size = st.st_size;
	if(st.st_size > MAX_FILE){
		snprintf(state.error, sizeof(state.error), "file larger than %d bytes", MAX_FILE);
		mosquitto_log_printf(MOSQ_LOG_WARNING, "inspect: %s: %s, keeping the rules before.", state.path, state.error);
		return;
	}
	FILE *f = fopen(state.path, "rb");
	if(!f){
		snprintf(state.error, sizeof(state.error), "cannot be read");
		mosquitto_log_printf(MOSQ_LOG_WARNING, "inspect: cannot read %s, keeping the rules before.", state.path);
		return;
	}
	char *text = malloc((size_t)st.st_size + 1);
	size_t got = text ? fread(text, 1, (size_t)st.st_size, f) : 0;
	fclose(f);
	if(!text){
		return;
	}
	text[got] = '\0';
	cJSON *tree = cJSON_Parse(text);
	free(text);
	if(!tree){
		snprintf(state.error, sizeof(state.error), "not valid JSON");
		mosquitto_log_printf(MOSQ_LOG_WARNING, "inspect: %s is not valid JSON, keeping the rules before.", state.path);
		return;
	}
	struct ruleset *r = parse(tree);
	cJSON_Delete(tree);
	if(!r){
		mosquitto_log_printf(MOSQ_LOG_WARNING, "inspect: %s: %s, keeping the rules before.", state.path, state.error);
		return;
	}
	for(int i = 0; i < r->content_count; i++){
		keep_counter(&r->content[i].counter, r->content[i].id, true);
	}
	for(int i = 0; i < r->rate_count; i++){
		keep_counter(&r->rate[i].counter, r->rate[i].id, false);
	}
	free_ruleset(state.rules);
	state.rules = r;
	state.loaded = true;
	state.error[0] = '\0';
	mosquitto_log_printf(MOSQ_LOG_NOTICE, "inspect: %s with %d content and %d rate rule(s).", state.path, r->content_count, r->rate_count);
}

void rules_init(const char *path)
{
	memset(&state, 0, sizeof(state));
	state.path = path ? strdup(path) : NULL;
	rules_reload();
}

void rules_cleanup(void)
{
	free_ruleset(state.rules);
	free(state.path);
	memset(&state, 0, sizeof(state));
}

static cJSON *field_at(cJSON *tree, const char *path)
{
	char *copy = strdup(path);
	if(!copy){
		return NULL;
	}
	cJSON *node = tree;
	char *save = NULL;
	for(char *part = strtok_r(copy, ".", &save); part && node; part = strtok_r(NULL, ".", &save)){
		node = cJSON_IsObject(node) ? cJSON_GetObjectItemCaseSensitive(node, part) : NULL;
	}
	free(copy);
	return node;
}

static bool has_type(cJSON *node, enum field_type type)
{
	switch(type){
		case T_NUMBER: return cJSON_IsNumber(node);
		case T_STRING: return cJSON_IsString(node);
		case T_BOOLEAN: return cJSON_IsBool(node);
		case T_OBJECT: return cJSON_IsObject(node);
		case T_ARRAY: return cJSON_IsArray(node);
		case T_NULL: return cJSON_IsNull(node);
		default: return true;
	}
}

static void count(struct counter *c, const char *clientid, struct rules_client *client)
{
	c->dropped++;
	c->last_at = time(NULL);
	free(c->last_client);
	c->last_client = clientid ? strdup(clientid) : NULL;
	snprintf(c->last_reason, sizeof(c->last_reason), "%s", state.reason);
	client->dropped++;
}

struct rules_verdict rules_check(const char *clientid, const char *username, const char *topic, const void *payload, uint32_t len,
		struct rules_client *client)
{
	struct rules_verdict v = { NULL, NULL, false, false };
	struct ruleset *r = state.rules;
	if(!r || !topic){
		return v;
	}
	if(exempt(r, username)){
		return v;
	}

	for(int i = 0; i < r->rate_count; i++){
		struct rate_rule *l = &r->rate[i];
		if(!clients_match(&l->clients, clientid)){
			continue;
		}
		double now = monotonic();
		if(client->rate_rule != l){
			/* a new rule, or the rules changed: a full bucket */
			client->rate_rule = l;
			client->tokens = l->burst;
		}else{
			client->tokens += (now - client->refilled) * l->per_second;
			if(client->tokens > l->burst){
				client->tokens = l->burst;
			}
		}
		client->refilled = now;
		if(client->tokens < 1){
			snprintf(state.reason, sizeof(state.reason), "more than %g messages per second", l->per_second);
			count(&l->counter, clientid, client);
			v.reason = state.reason;
			v.rule_id = l->id;
			v.rate = true;
			v.disconnect = l->disconnect;
			return v;
		}
		client->tokens -= 1;
		break;
	}

	cJSON *parsed = NULL;
	bool parse_tried = false;
	for(int i = 0; i < r->content_count; i++){
		struct content_rule *c = &r->content[i];
		bool matches = false;
		if(!clients_match(&c->clients, clientid) || mosquitto_topic_matches_sub(c->topic, topic, &matches) != MOSQ_ERR_SUCCESS || !matches){
			continue;
		}
		state.reason[0] = '\0';
		if(c->max_bytes && len > c->max_bytes){
			snprintf(state.reason, sizeof(state.reason), "%u bytes, more than %u", len, c->max_bytes);
		}else if(c->json || c->field_count > 0){
			if(!parse_tried){
				parse_tried = true;
				parsed = payload ? cJSON_ParseWithLength(payload, len) : NULL;
			}
			if(!parsed){
				snprintf(state.reason, sizeof(state.reason), "not valid JSON");
			}
			for(int f = 0; parsed && f < c->field_count && !state.reason[0]; f++){
				cJSON *node = field_at(parsed, c->fields[f].path);
				if(!node){
					snprintf(state.reason, sizeof(state.reason), "%s is missing", c->fields[f].path);
				}else if(!has_type(node, c->fields[f].type)){
					snprintf(state.reason, sizeof(state.reason), "%s is not of type %s", c->fields[f].path, TYPE_NAMES[c->fields[f].type]);
				}
			}
		}
		if(state.reason[0]){
			count(&c->counter, clientid, client);
			v.reason = state.reason;
			v.rule_id = c->id;
			v.disconnect = c->disconnect;
			break;
		}
	}
	cJSON_Delete(parsed);
	return v;
}

static cJSON *counter_json(const char *id, const struct counter *c)
{
	cJSON *j = cJSON_CreateObject();
	if(!j){
		return NULL;
	}
	cJSON_AddStringToObject(j, "id", id);
	cJSON_AddNumberToObject(j, "dropped", (double)c->dropped);
	if(c->last_at){
		cJSON_AddNumberToObject(j, "lastAt", (double)c->last_at);
		if(c->last_client) cJSON_AddStringToObject(j, "lastClient", c->last_client);
		cJSON_AddStringToObject(j, "lastReason", c->last_reason);
	}
	return j;
}

cJSON *rules_stats(void)
{
	cJSON *j = cJSON_CreateObject();
	if(!j){
		return NULL;
	}
	cJSON_AddBoolToObject(j, "loaded", state.rules != NULL);
	if(state.error[0]) cJSON_AddStringToObject(j, "error", state.error);
	cJSON *content = cJSON_AddArrayToObject(j, "content");
	cJSON *rate = cJSON_AddArrayToObject(j, "rate");
	struct ruleset *r = state.rules;
	for(int i = 0; r && i < r->content_count; i++){
		cJSON_AddItemToArray(content, counter_json(r->content[i].id, &r->content[i].counter));
	}
	for(int i = 0; r && i < r->rate_count; i++){
		cJSON_AddItemToArray(rate, counter_json(r->rate[i].id, &r->rate[i].counter));
	}
	return j;
}
