/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Alexander Schlenker
 *
 * A look into the broker for mosquitto-ui: what each client does, and a recording of what it does for a while.
 *
 * Mosquitto tells on $SYS only how many clients there are. This plugin follows the events of the broker and answers on
 * $CONTROL/inspect/v1, as dynamic security does on its own topic, with { "commands": [ { "command": ..., "correlationData":
 * ... } ] } and the answers on $CONTROL/inspect/v1/response. Only who may use $CONTROL/inspect/# sees any of it (the role
 * super-admin of dynamic security has $CONTROL/#): the answers hold addresses, and a recording the messages themselves.
 *
 * listClients: every session the broker has, connected or kept for later: client id, user name, address, MQTT version,
 * keep alive, clean start, the subscriptions it made, and how many messages and bytes it sent and got. The broker gives
 * plugins no list of the subscriptions of a client, only their number: the plugin sees each subscribe and unsubscribe, so
 * those made before the broker started (a session kept in the persistence) are missing until made again; subscriptionCount
 * is what the broker counts. Connected since and messages count from the start of the broker.
 *
 * startTrace: records, for a while, what clients do: connect, disconnect, subscribe, unsubscribe, every message they send
 * and get, and what the broker refused them (a message or subscription the ACLs did not allow). For one client id, all
 * client ids with a prefix ("sensor-*"), a topic filter, or both; connecting and going only with a client id. Each event
 * has at (milliseconds), type and clientid; the payload is base64, cut at payloadBytes (truncated). The events go out about every second on
 * $CONTROL/inspect/v1/trace, as { "id", "events": [ ... ], "end": reason } where end comes with the last batch: stopped,
 * time, limit (maxEvents) or replaced (another trace started). One trace at a time. stopTrace ends it.
 *
 * Message rules (rules.c, plugin_opt_rules_file): a message that does not hold is refused before anyone gets it, counted, and
 * recorded as dropped; ruleStats tells how many each rule refused.
 *
 * Its own topics are never recorded, nor counted. $SYS/broker/inspect/version is published retained at start, so that the UI
 * knows the plugin is there without asking.
 *
 * To see what the ACLs refused it has to be loaded before the other plugins: it sees each ACL check first, always lets the
 * others decide, and counts an attempt as refused when no message or subscription follows it.
 */
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <cjson/cJSON.h>

#include "mosquitto.h"
#include "mosquitto/broker_control.h"
#include "rules.h"

#define PLUGIN_NAME "inspect"
#define PLUGIN_VERSION "1"
#define CONTROL_TOPIC "$CONTROL/inspect/v1"
#define RESPONSE_TOPIC CONTROL_TOPIC "/response"
#define TRACE_TOPIC CONTROL_TOPIC "/trace"
#define VERSION_TOPIC "$SYS/broker/inspect/version"
#define BUCKETS 1024
#define MAX_TRACE_SECONDS 3600
#define DEFAULT_TRACE_SECONDS 300
#define MAX_TRACE_EVENTS 50000
#define DEFAULT_TRACE_EVENTS 5000
#define MAX_PAYLOAD_BYTES 4096
#define DEFAULT_PAYLOAD_BYTES 256
#define BATCH_EVENTS 500
#define MAX_ID 64

MOSQUITTO_PLUGIN_DECLARE_VERSION(5);

struct subscription {
	char *filter;
	uint32_t identifier;
	uint8_t options;
	struct subscription *next;
};

struct client {
	char *id;
	time_t connected_at;
	time_t offline_at;
	uint64_t messages_in, messages_out, bytes_in, bytes_out;
	time_t last_in, last_out;
	struct subscription *subscriptions;
	int subscription_count;
	/* an ACL check that let nothing through yet, while a trace looks at this client */
	char *pending_topic;
	int pending_access;
	uint8_t pending_qos;
	bool pending_retain;
	uint32_t pending_size;
	bool alive;
	/* its rate limit, and messages the rules refused */
	struct rules_client rules;
	/* to be disconnected at the next tick, as a rule says */
	bool kick;
	struct client *next;
};

struct trace {
	bool active;
	char id[MAX_ID];
	char *clientid;
	bool prefix;
	char *topic;
	time_t started;
	time_t until;
	int max_events;
	int payload_bytes;
	int count;
	cJSON *batch;
	const char *end;
	char *started_by;
};

static struct {
	mosquitto_plugin_id_t *pid;
	struct client *buckets[BUCKETS];
	struct trace trace;
	time_t last_prune;
} plugin;

static uint32_t hash(const char *s)
{
	uint32_t h = 2166136261u;
	for(; *s; s++){
		h = (h ^ (uint8_t)*s) * 16777619u;
	}
	return h % BUCKETS;
}

static struct client *find_client(const char *id, bool create)
{
	if(!id){
		return NULL;
	}
	uint32_t b = hash(id);
	for(struct client *c = plugin.buckets[b]; c; c = c->next){
		if(!strcmp(c->id, id)){
			return c;
		}
	}
	if(!create){
		return NULL;
	}
	struct client *c = calloc(1, sizeof(*c));
	if(!c){
		return NULL;
	}
	c->id = strdup(id);
	if(!c->id){
		free(c);
		return NULL;
	}
	c->next = plugin.buckets[b];
	plugin.buckets[b] = c;
	return c;
}

static void free_subscriptions(struct client *c)
{
	while(c->subscriptions){
		struct subscription *s = c->subscriptions;
		c->subscriptions = s->next;
		free(s->filter);
		free(s);
	}
	c->subscription_count = 0;
}

static void clear_pending(struct client *c)
{
	free(c->pending_topic);
	c->pending_topic = NULL;
}

static void free_client(struct client *c)
{
	free_subscriptions(c);
	clear_pending(c);
	free(c->id);
	free(c);
}

static void remove_client(const char *id)
{
	if(!id){
		return;
	}
	for(struct client **p = &plugin.buckets[hash(id)]; *p; p = &(*p)->next){
		if(!strcmp((*p)->id, id)){
			struct client *c = *p;
			*p = c->next;
			free_client(c);
			return;
		}
	}
}

/* Its own topics: neither counted nor recorded, or a recording would record itself */
static bool own_topic(const char *topic)
{
	return topic && (!strncmp(topic, CONTROL_TOPIC, strlen(CONTROL_TOPIC)) || !strncmp(topic, VERSION_TOPIC, strlen(VERSION_TOPIC)));
}

static double now_ms(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_REALTIME, &ts);
	return (double)ts.tv_sec * 1000.0 + (double)(ts.tv_nsec / 1000000);
}

static const char *protocol_name(int version)
{
	switch(version){
		case 3: return "3.1";
		case 4: return "3.1.1";
		case 5: return "5";
		default: return "?";
	}
}

/* The broker does not export mosquitto_strerror: the reasons a connection ends with */
static const char *reason_text(int reason)
{
	switch(reason){
		case MOSQ_ERR_SUCCESS: return "disconnected";
		case MOSQ_ERR_NOMEM: return "out of memory";
		case MOSQ_ERR_PROTOCOL: return "protocol error";
		case MOSQ_ERR_CONN_REFUSED: return "connection refused";
		case MOSQ_ERR_CONN_LOST: return "connection lost";
		case MOSQ_ERR_TLS: return "TLS error";
		case MOSQ_ERR_PAYLOAD_SIZE: return "payload too large";
		case MOSQ_ERR_NOT_SUPPORTED: return "not supported";
		case MOSQ_ERR_AUTH: return "not authorized";
		case MOSQ_ERR_ACL_DENIED: return "access denied";
		case MOSQ_ERR_MALFORMED_UTF8: return "malformed UTF-8";
		case MOSQ_ERR_KEEPALIVE: return "keep alive timeout";
		case MOSQ_ERR_MALFORMED_PACKET: return "malformed packet";
		case MOSQ_ERR_OVERSIZE_PACKET: return "packet too large";
		case MOSQ_ERR_TIMEOUT: return "timeout";
		case MOSQ_ERR_ADMINISTRATIVE_ACTION: return "disconnected by the broker";
		case MOSQ_ERR_SESSION_TAKEN_OVER: return "session taken over";
		case MOSQ_ERR_SERVER_SHUTTING_DOWN: return "broker shutting down";
		case MOSQ_ERR_BAD_USERNAME_OR_PASSWORD: return "bad user name or password";
		case MOSQ_ERR_CLIENT_IDENTIFIER_NOT_VALID: return "client id not valid";
		default: return "other";
	}
}

static char *base64(const uint8_t *data, size_t len)
{
	static const char table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
	char *out = malloc(4 * ((len + 2) / 3) + 1);
	if(!out){
		return NULL;
	}
	size_t o = 0;
	for(size_t i = 0; i < len; i += 3){
		uint32_t v = (uint32_t)data[i] << 16;
		if(i + 1 < len) v |= (uint32_t)data[i + 1] << 8;
		if(i + 2 < len) v |= data[i + 2];
		out[o++] = table[(v >> 18) & 63];
		out[o++] = table[(v >> 12) & 63];
		out[o++] = i + 1 < len ? table[(v >> 6) & 63] : '=';
		out[o++] = i + 2 < len ? table[v & 63] : '=';
	}
	out[o] = '\0';
	return out;
}

/* --- the recording --- */

static bool trace_wants_client(const char *clientid)
{
	struct trace *t = &plugin.trace;
	if(!t->active){
		return false;
	}
	if(!t->clientid){
		return true;
	}
	if(!clientid){
		return false;
	}
	return t->prefix ? !strncmp(clientid, t->clientid, strlen(t->clientid)) : !strcmp(clientid, t->clientid);
}

/* Connecting and going are recorded only for a trace of client ids: one of a topic alone would get those of all clients */
static bool trace_wants_session(const char *clientid)
{
	return plugin.trace.active && plugin.trace.clientid && trace_wants_client(clientid);
}

static bool trace_wants_topic(const char *topic)
{
	struct trace *t = &plugin.trace;
	if(!t->topic){
		return true;
	}
	bool result = false;
	return topic && mosquitto_topic_matches_sub(t->topic, topic, &result) == MOSQ_ERR_SUCCESS && result;
}

/* A new event of the trace for this client, or NULL when it is not recorded */
static cJSON *trace_event(const char *type, const char *clientid)
{
	struct trace *t = &plugin.trace;
	if(!t->active || t->count >= t->max_events){
		return NULL;
	}
	if(!t->batch){
		t->batch = cJSON_CreateArray();
		if(!t->batch){
			return NULL;
		}
	}
	cJSON *e = cJSON_CreateObject();
	if(!e){
		return NULL;
	}
	cJSON_AddNumberToObject(e, "at", now_ms());
	cJSON_AddStringToObject(e, "type", type);
	cJSON_AddStringToObject(e, "clientid", clientid ? clientid : "");
	cJSON_AddItemToArray(t->batch, e);
	t->count++;
	if(t->count >= t->max_events){
		t->end = "limit";
	}
	return e;
}

static void add_payload(cJSON *e, const void *payload, uint32_t len)
{
	uint32_t shown = len < (uint32_t)plugin.trace.payload_bytes ? len : (uint32_t)plugin.trace.payload_bytes;
	cJSON_AddNumberToObject(e, "size", len);
	if(shown > 0 && payload){
		char *b = base64(payload, shown);
		if(b){
			cJSON_AddStringToObject(e, "payload", b);
			free(b);
		}
	}
	if(shown < len){
		cJSON_AddBoolToObject(e, "truncated", true);
	}
}

static void free_trace(void)
{
	struct trace *t = &plugin.trace;
	free(t->clientid);
	free(t->topic);
	free(t->started_by);
	cJSON_Delete(t->batch);
	memset(t, 0, sizeof(*t));
}

/* Sends what was recorded since the last time; with end, the last batch and the end of the trace */
static void trace_flush(void)
{
	struct trace *t = &plugin.trace;
	if(!t->active){
		return;
	}
	while(true){
		int size = t->batch ? cJSON_GetArraySize(t->batch) : 0;
		if(size == 0 && !t->end){
			return;
		}
		cJSON *message = cJSON_CreateObject();
		if(!message){
			return;
		}
		cJSON_AddStringToObject(message, "id", t->id);
		cJSON *events = cJSON_AddArrayToObject(message, "events");
		for(int i = 0; i < BATCH_EVENTS && t->batch && cJSON_GetArraySize(t->batch) > 0; i++){
			cJSON_AddItemToArray(events, cJSON_DetachItemFromArray(t->batch, 0));
		}
		bool more = t->batch && cJSON_GetArraySize(t->batch) > 0;
		if(t->end && !more){
			cJSON_AddStringToObject(message, "end", t->end);
		}
		char *text = cJSON_PrintUnformatted(message);
		cJSON_Delete(message);
		if(text){
			mosquitto_broker_publish_copy(NULL, TRACE_TOPIC, (int)strlen(text), text, 0, false, NULL);
			free(text);
		}
		if(!more){
			break;
		}
	}
	if(t->end){
		mosquitto_log_printf(MOSQ_LOG_NOTICE, "inspect: trace %s ended (%s) after %d event(s).", t->id, t->end, t->count);
		free_trace();
	}
}

/* An ACL check that nothing followed: the broker refused it */
static void flush_pending(struct client *c)
{
	if(!c || !c->pending_topic){
		return;
	}
	if(trace_wants_client(c->id) && trace_wants_topic(c->pending_topic)){
		cJSON *e = trace_event(c->pending_access == MOSQ_ACL_SUBSCRIBE ? "subscribeDenied" : "publishDenied", c->id);
		if(e){
			cJSON_AddStringToObject(e, "topic", c->pending_topic);
			cJSON_AddNumberToObject(e, "qos", c->pending_qos);
			if(c->pending_access == MOSQ_ACL_WRITE){
				cJSON_AddBoolToObject(e, "retain", c->pending_retain);
				cJSON_AddNumberToObject(e, "size", c->pending_size);
			}
		}
	}
	clear_pending(c);
}

/* --- events of the broker --- */

static int on_connect(int event, void *event_data, void *userdata)
{
	struct mosquitto_evt_connect *ed = event_data;
	const char *id = mosquitto_client_id(ed->client);
	(void)event;
	(void)userdata;

	struct client *c = find_client(id, true);
	if(c){
		if(mosquitto_client_clean_session(ed->client)){
			free_subscriptions(c);
		}
		c->connected_at = time(NULL);
		c->offline_at = 0;
		clear_pending(c);
	}
	if(trace_wants_session(id)){
		cJSON *e = trace_event("connect", id);
		if(e){
			const char *username = mosquitto_client_username(ed->client);
			const char *address = mosquitto_client_address(ed->client);
			if(username) cJSON_AddStringToObject(e, "username", username);
			if(address) cJSON_AddStringToObject(e, "address", address);
			cJSON_AddStringToObject(e, "protocol", protocol_name(mosquitto_client_protocol_version(ed->client)));
			cJSON_AddNumberToObject(e, "keepalive", mosquitto_client_keepalive(ed->client));
			cJSON_AddBoolToObject(e, "cleanStart", mosquitto_client_clean_session(ed->client));
			cJSON_AddStringToObject(e, "transport", mosquitto_client_protocol(ed->client) == mp_websockets ? "websockets" : "mqtt");
		}
	}
	return MOSQ_ERR_SUCCESS;
}

static void went(struct mosquitto *client, int reason, bool session_ended)
{
	const char *id = mosquitto_client_id(client);
	/* the old connection of a client id another one took over: the session is the new one's */
	bool replaced = id && mosquitto_client(id) != NULL && mosquitto_client(id) != client;
	struct client *c = replaced ? NULL : find_client(id, false);
	flush_pending(c);
	if(trace_wants_session(id)){
		cJSON *e = trace_event(session_ended ? "disconnect" : "offline", id);
		if(e){
			cJSON_AddNumberToObject(e, "reason", reason);
			cJSON_AddStringToObject(e, "reasonText", reason_text(reason));
		}
	}
	if(replaced){
		return;
	}
	if(session_ended){
		remove_client(id);
	}else if(c){
		c->offline_at = time(NULL);
	}
}

static int on_disconnect(int event, void *event_data, void *userdata)
{
	struct mosquitto_evt_disconnect *ed = event_data;
	(void)event;
	(void)userdata;
	went(ed->client, ed->reason, true);
	return MOSQ_ERR_SUCCESS;
}

static int on_client_offline(int event, void *event_data, void *userdata)
{
	struct mosquitto_evt_client_offline *ed = event_data;
	(void)event;
	(void)userdata;
	went(ed->client, ed->reason, false);
	return MOSQ_ERR_SUCCESS;
}

static int on_subscribe(int event, void *event_data, void *userdata)
{
	struct mosquitto_evt_subscribe *ed = event_data;
	const char *id = mosquitto_client_id(ed->client);
	const char *filter = ed->data.topic_filter;
	(void)event;
	(void)userdata;

	struct client *c = find_client(id, true);
	if(c && filter){
		clear_pending(c);
		struct subscription *s;
		for(s = c->subscriptions; s; s = s->next){
			if(!strcmp(s->filter, filter)){
				break;
			}
		}
		if(!s){
			s = calloc(1, sizeof(*s));
			if(s){
				s->filter = strdup(filter);
				if(!s->filter){
					free(s);
					s = NULL;
				}else{
					s->next = c->subscriptions;
					c->subscriptions = s;
					c->subscription_count++;
				}
			}
		}
		if(s){
			s->options = ed->data.options;
			s->identifier = ed->data.identifier;
		}
	}
	if(trace_wants_client(id) && trace_wants_topic(filter)){
		cJSON *e = trace_event("subscribe", id);
		if(e){
			cJSON_AddStringToObject(e, "topic", filter ? filter : "");
			cJSON_AddNumberToObject(e, "qos", ed->data.options & 0x03);
		}
	}
	return MOSQ_ERR_SUCCESS;
}

static int on_unsubscribe(int event, void *event_data, void *userdata)
{
	struct mosquitto_evt_unsubscribe *ed = event_data;
	const char *id = mosquitto_client_id(ed->client);
	const char *filter = ed->data.topic_filter;
	(void)event;
	(void)userdata;

	struct client *c = find_client(id, false);
	if(c && filter){
		clear_pending(c);
		for(struct subscription **p = &c->subscriptions; *p; p = &(*p)->next){
			if(!strcmp((*p)->filter, filter)){
				struct subscription *s = *p;
				*p = s->next;
				free(s->filter);
				free(s);
				c->subscription_count--;
				break;
			}
		}
	}
	if(trace_wants_client(id) && trace_wants_topic(filter)){
		cJSON *e = trace_event("unsubscribe", id);
		if(e){
			cJSON_AddStringToObject(e, "topic", filter ? filter : "");
		}
	}
	return MOSQ_ERR_SUCCESS;
}

static int on_message_in(int event, void *event_data, void *userdata)
{
	struct mosquitto_evt_message *ed = event_data;
	(void)event;
	(void)userdata;

	if(!ed->client || own_topic(ed->topic)){
		return MOSQ_ERR_SUCCESS;
	}
	const char *id = mosquitto_client_id(ed->client);
	struct client *c = find_client(id, true);
	struct rules_client scratch = { 0 };
	struct rules_verdict v = rules_check(id, mosquitto_client_username(ed->client), ed->topic, ed->payload, ed->payloadlen,
			c ? &c->rules : &scratch);
	if(v.reason){
		if(c){
			clear_pending(c);
			c->kick = c->kick || v.disconnect;
		}
		if(trace_wants_client(id) && trace_wants_topic(ed->topic)){
			cJSON *e = trace_event("dropped", id);
			if(e){
				cJSON_AddStringToObject(e, "topic", ed->topic);
				cJSON_AddNumberToObject(e, "qos", ed->qos);
				cJSON_AddBoolToObject(e, "retain", ed->retain);
				cJSON_AddStringToObject(e, "rule", v.rule_id);
				cJSON_AddStringToObject(e, "reasonText", v.reason);
				if(v.disconnect) cJSON_AddBoolToObject(e, "disconnect", true);
				add_payload(e, ed->payload, ed->payloadlen);
			}
		}
		/* MQTT 5 clients learn why from the reason code of their PUBACK */
		return v.rate ? MOSQ_ERR_QUOTA_EXCEEDED : MOSQ_ERR_ACL_DENIED;
	}
	if(c){
		clear_pending(c);
		c->messages_in++;
		c->bytes_in += ed->payloadlen;
		c->last_in = time(NULL);
	}
	if(trace_wants_client(id) && trace_wants_topic(ed->topic)){
		cJSON *e = trace_event("publish", id);
		if(e){
			cJSON_AddStringToObject(e, "topic", ed->topic);
			cJSON_AddNumberToObject(e, "qos", ed->qos);
			cJSON_AddBoolToObject(e, "retain", ed->retain);
			add_payload(e, ed->payload, ed->payloadlen);
		}
	}
	return MOSQ_ERR_SUCCESS;
}

static int on_message_out(int event, void *event_data, void *userdata)
{
	struct mosquitto_evt_message *ed = event_data;
	(void)event;
	(void)userdata;

	if(!ed->client || own_topic(ed->topic)){
		return MOSQ_ERR_SUCCESS;
	}
	const char *id = mosquitto_client_id(ed->client);
	struct client *c = find_client(id, false);
	if(c){
		c->messages_out++;
		c->bytes_out += ed->payloadlen;
		c->last_out = time(NULL);
	}
	if(trace_wants_client(id) && trace_wants_topic(ed->topic)){
		cJSON *e = trace_event("deliver", id);
		if(e){
			cJSON_AddStringToObject(e, "topic", ed->topic);
			cJSON_AddNumberToObject(e, "qos", ed->qos);
			cJSON_AddBoolToObject(e, "retain", ed->retain);
			add_payload(e, ed->payload, ed->payloadlen);
		}
	}
	return MOSQ_ERR_SUCCESS;
}

/* Never decides: notes a message or subscription that a client tries, to tell later whether the others let it through */
static int on_acl_check(int event, void *event_data, void *userdata)
{
	struct mosquitto_evt_acl_check *ed = event_data;
	(void)event;
	(void)userdata;

	if(!plugin.trace.active || (ed->access != MOSQ_ACL_WRITE && ed->access != MOSQ_ACL_SUBSCRIBE) || own_topic(ed->topic)){
		return MOSQ_ERR_PLUGIN_DEFER;
	}
	const char *id = mosquitto_client_id(ed->client);
	if(!trace_wants_client(id) || !trace_wants_topic(ed->topic)){
		return MOSQ_ERR_PLUGIN_DEFER;
	}
	/* $CONTROL is answered by a plugin, no message follows */
	if(ed->access == MOSQ_ACL_WRITE && ed->topic && !strncmp(ed->topic, "$CONTROL/", 9)){
		return MOSQ_ERR_PLUGIN_DEFER;
	}
	struct client *c = find_client(id, true);
	if(c){
		flush_pending(c);
		c->pending_topic = ed->topic ? strdup(ed->topic) : NULL;
		c->pending_access = ed->access;
		c->pending_qos = ed->qos;
		c->pending_retain = ed->retain;
		c->pending_size = ed->payloadlen;
	}
	return MOSQ_ERR_PLUGIN_DEFER;
}

/* --- answers --- */

struct listing {
	cJSON *clients;
};

static int list_one(const struct mosquitto *client, void *userdata)
{
	struct listing *l = userdata;
	const char *id = mosquitto_client_id(client);
	if(!id){
		return MOSQ_ERR_SUCCESS;
	}
	struct client *c = find_client(id, false);
	cJSON *j = cJSON_CreateObject();
	if(!j){
		return MOSQ_ERR_NOMEM;
	}
	const char *username = mosquitto_client_username(client);
	const char *address = mosquitto_client_address(client);
	bool connected = c && c->connected_at && !c->offline_at;
	cJSON_AddStringToObject(j, "clientid", id);
	if(username) cJSON_AddStringToObject(j, "username", username);
	if(address) cJSON_AddStringToObject(j, "address", address);
	cJSON_AddBoolToObject(j, "connected", connected);
	cJSON_AddStringToObject(j, "protocol", protocol_name(mosquitto_client_protocol_version(client)));
	cJSON_AddStringToObject(j, "transport", mosquitto_client_protocol(client) == mp_websockets ? "websockets" : "mqtt");
	cJSON_AddNumberToObject(j, "keepalive", mosquitto_client_keepalive(client));
	cJSON_AddBoolToObject(j, "cleanStart", mosquitto_client_clean_session(client));
	cJSON_AddNumberToObject(j, "subscriptionCount", mosquitto_client_sub_count(client));
	cJSON *subs = cJSON_AddArrayToObject(j, "subscriptions");
	if(c){
		if(c->connected_at) cJSON_AddNumberToObject(j, "connectedAt", (double)c->connected_at);
		if(c->offline_at) cJSON_AddNumberToObject(j, "offlineAt", (double)c->offline_at);
		cJSON_AddNumberToObject(j, "messagesIn", (double)c->messages_in);
		cJSON_AddNumberToObject(j, "dropped", (double)c->rules.dropped);
		cJSON_AddNumberToObject(j, "messagesOut", (double)c->messages_out);
		cJSON_AddNumberToObject(j, "bytesIn", (double)c->bytes_in);
		cJSON_AddNumberToObject(j, "bytesOut", (double)c->bytes_out);
		if(c->last_in) cJSON_AddNumberToObject(j, "lastIn", (double)c->last_in);
		if(c->last_out) cJSON_AddNumberToObject(j, "lastOut", (double)c->last_out);
		for(struct subscription *s = c->subscriptions; s; s = s->next){
			cJSON *js = cJSON_CreateObject();
			if(js){
				cJSON_AddStringToObject(js, "topic", s->filter);
				cJSON_AddNumberToObject(js, "qos", s->options & 0x03);
				cJSON_AddBoolToObject(js, "noLocal", (s->options & 0x04) != 0);
				cJSON_AddBoolToObject(js, "retainAsPublished", (s->options & 0x08) != 0);
				if(s->identifier) cJSON_AddNumberToObject(js, "identifier", s->identifier);
				cJSON_AddItemToArray(subs, js);
			}
		}
	}
	cJSON_AddItemToArray(l->clients, j);
	return MOSQ_ERR_SUCCESS;
}

/* Forgets the clients whose session the broker no longer has: one that expired gives no event */
static int mark_alive(const struct mosquitto *client, void *userdata)
{
	(void)userdata;
	struct client *c = find_client(mosquitto_client_id(client), false);
	if(c){
		c->alive = true;
	}
	return MOSQ_ERR_SUCCESS;
}

static void prune(void)
{
	for(int b = 0; b < BUCKETS; b++){
		for(struct client *c = plugin.buckets[b]; c; c = c->next){
			c->alive = false;
		}
	}
	if(mosquitto_apply_on_all_clients(mark_alive, NULL) != MOSQ_ERR_SUCCESS){
		return;
	}
	for(int b = 0; b < BUCKETS; b++){
		for(struct client **q = &plugin.buckets[b]; *q; ){
			if((*q)->alive){
				q = &(*q)->next;
			}else{
				struct client *c = *q;
				*q = c->next;
				free_client(c);
			}
		}
	}
}

static void reply_data(struct mosquitto_control_cmd *cmd, cJSON *data)
{
	cJSON *tree = cJSON_CreateObject();
	if(!tree){
		cJSON_Delete(data);
		mosquitto_control_command_reply(cmd, "Internal error");
		return;
	}
	cJSON_AddStringToObject(tree, "command", cmd->command_name);
	cJSON_AddItemToObject(tree, "data", data);
	if(cmd->correlation_data){
		cJSON_AddStringToObject(tree, "correlationData", cmd->correlation_data);
	}
	cJSON_AddItemToArray(cmd->j_responses, tree);
}

static cJSON *trace_state(void)
{
	struct trace *t = &plugin.trace;
	cJSON *j = cJSON_CreateObject();
	if(!j || !t->active){
		return j;
	}
	cJSON *a = cJSON_AddObjectToObject(j, "trace");
	if(!a){
		return j;
	}
	cJSON_AddStringToObject(a, "id", t->id);
	if(t->clientid){
		char *pattern = malloc(strlen(t->clientid) + 2);
		if(pattern){
			sprintf(pattern, "%s%s", t->clientid, t->prefix ? "*" : "");
			cJSON_AddStringToObject(a, "clientid", pattern);
			free(pattern);
		}
	}
	if(t->topic) cJSON_AddStringToObject(a, "topic", t->topic);
	cJSON_AddNumberToObject(a, "started", (double)t->started);
	cJSON_AddNumberToObject(a, "until", (double)t->until);
	cJSON_AddNumberToObject(a, "maxEvents", t->max_events);
	cJSON_AddNumberToObject(a, "payloadBytes", t->payload_bytes);
	cJSON_AddNumberToObject(a, "count", t->count);
	if(t->started_by) cJSON_AddStringToObject(a, "startedBy", t->started_by);
	return j;
}

static int number(cJSON *command, const char *name, int fallback, int max)
{
	cJSON *j = cJSON_GetObjectItem(command, name);
	if(!cJSON_IsNumber(j)){
		return fallback;
	}
	int v = (int)j->valuedouble;
	return v < 0 ? 0 : (v > max ? max : v);
}

static void start_trace(struct mosquitto_control_cmd *cmd)
{
	const char *id = cJSON_GetStringValue(cJSON_GetObjectItem(cmd->j_command, "id"));
	const char *clientid = cJSON_GetStringValue(cJSON_GetObjectItem(cmd->j_command, "clientid"));
	const char *topic = cJSON_GetStringValue(cJSON_GetObjectItem(cmd->j_command, "topic"));
	const char *by = cJSON_GetStringValue(cJSON_GetObjectItem(cmd->j_command, "startedBy"));
	if(clientid && !*clientid) clientid = NULL;
	if(topic && !*topic) topic = NULL;

	if(!id || !*id || strlen(id) >= MAX_ID){
		mosquitto_control_command_reply(cmd, "Missing or too long id");
		return;
	}
	if(!clientid && !topic){
		mosquitto_control_command_reply(cmd, "A client id or a topic filter is needed");
		return;
	}
	if(topic && mosquitto_sub_topic_check(topic) != MOSQ_ERR_SUCCESS){
		mosquitto_control_command_reply(cmd, "Invalid topic filter");
		return;
	}
	if(plugin.trace.active){
		plugin.trace.end = "replaced";
		trace_flush();
	}
	struct trace *t = &plugin.trace;
	t->active = true;
	snprintf(t->id, sizeof(t->id), "%s", id);
	if(clientid){
		size_t len = strlen(clientid);
		t->prefix = len > 0 && clientid[len - 1] == '*';
		t->clientid = strndup(clientid, t->prefix ? len - 1 : len);
	}
	t->topic = topic ? strdup(topic) : NULL;
	t->started_by = by ? strdup(by) : NULL;
	t->started = time(NULL);
	int seconds = number(cmd->j_command, "seconds", DEFAULT_TRACE_SECONDS, MAX_TRACE_SECONDS);
	t->until = t->started + (seconds > 0 ? seconds : 1);
	t->max_events = number(cmd->j_command, "maxEvents", DEFAULT_TRACE_EVENTS, MAX_TRACE_EVENTS);
	if(t->max_events < 1) t->max_events = 1;
	t->payload_bytes = number(cmd->j_command, "payloadBytes", DEFAULT_PAYLOAD_BYTES, MAX_PAYLOAD_BYTES);
	mosquitto_log_printf(MOSQ_LOG_NOTICE, "inspect: trace %s started by %s for client %s, topic %s, %d s.", t->id,
			by ? by : "?", clientid ? clientid : "*", topic ? topic : "#", seconds);
	reply_data(cmd, trace_state());
}

static int handle_command(struct mosquitto_control_cmd *cmd, void *userdata)
{
	(void)userdata;
	if(!strcmp(cmd->command_name, "listClients")){
		prune();
		cJSON *data = cJSON_CreateObject();
		struct listing l = { data ? cJSON_AddArrayToObject(data, "clients") : NULL };
		if(!l.clients || mosquitto_apply_on_all_clients(list_one, &l) != MOSQ_ERR_SUCCESS){
			cJSON_Delete(data);
			mosquitto_control_command_reply(cmd, "Internal error");
			return MOSQ_ERR_NOMEM;
		}
		reply_data(cmd, data);
	}else if(!strcmp(cmd->command_name, "startTrace")){
		start_trace(cmd);
	}else if(!strcmp(cmd->command_name, "stopTrace")){
		const char *id = cJSON_GetStringValue(cJSON_GetObjectItem(cmd->j_command, "id"));
		if(plugin.trace.active && id && !strcmp(id, plugin.trace.id)){
			plugin.trace.end = "stopped";
			trace_flush();
		}
		reply_data(cmd, trace_state());
	}else if(!strcmp(cmd->command_name, "ruleStats")){
		reply_data(cmd, rules_stats());
	}else if(!strcmp(cmd->command_name, "getTrace")){
		reply_data(cmd, trace_state());
	}else{
		mosquitto_control_command_reply(cmd, "Unknown command");
		return MOSQ_ERR_INVAL;
	}
	return MOSQ_ERR_SUCCESS;
}

static int on_control(int event, void *event_data, void *userdata)
{
	(void)event;
	return mosquitto_control_generic_callback(event_data, RESPONSE_TOPIC, userdata, handle_command);
}

/* Disconnects the clients a rule said so for; collected first, as a kick ends in went() */
static void kick_flagged(void)
{
	char **ids = NULL;
	int count = 0;
	for(int b = 0; b < BUCKETS; b++){
		for(struct client *c = plugin.buckets[b]; c; c = c->next){
			if(!c->kick){
				continue;
			}
			c->kick = false;
			char **more = realloc(ids, (size_t)(count + 1) * sizeof(char *));
			if(!more){
				continue;
			}
			ids = more;
			ids[count++] = strdup(c->id);
		}
	}
	for(int i = 0; i < count; i++){
		if(ids[i]){
			mosquitto_log_printf(MOSQ_LOG_NOTICE, "inspect: client %s disconnected, a message rule says so.", ids[i]);
			mosquitto_kick_client_by_clientid(ids[i], false);
			free(ids[i]);
		}
	}
	free(ids);
}

static int on_tick(int event, void *event_data, void *userdata)
{
	struct mosquitto_evt_tick *ed = event_data;
	time_t now = time(NULL);
	(void)event;
	(void)userdata;

	rules_reload();
	kick_flagged();
	if(plugin.trace.active){
		/* a check without anything after it within its own round was refused */
		for(int b = 0; b < BUCKETS; b++){
			for(struct client *c = plugin.buckets[b]; c; c = c->next){
				flush_pending(c);
			}
		}
		if(!plugin.trace.end && now >= plugin.trace.until){
			plugin.trace.end = "time";
		}
		trace_flush();
	}
	if(now - plugin.last_prune >= 60){
		plugin.last_prune = now;
		prune();
	}
	ed->next_s = 1;
	ed->next_ms = 0;
	return MOSQ_ERR_SUCCESS;
}

int mosquitto_plugin_init(mosquitto_plugin_id_t *identifier, void **userdata, struct mosquitto_opt *opts, int opt_count)
{
	(void)userdata;
	memset(&plugin, 0, sizeof(plugin));
	plugin.pid = identifier;
	const char *rules_file = NULL;
	for(int i = 0; i < opt_count; i++){
		if(!strcmp(opts[i].key, "rules_file")){
			rules_file = opts[i].value;
		}
	}
	rules_init(rules_file);
	plugin.last_prune = time(NULL);
	mosquitto_plugin_set_info(identifier, PLUGIN_NAME, PLUGIN_VERSION);
	if(mosquitto_callback_register(identifier, MOSQ_EVT_CONTROL, on_control, CONTROL_TOPIC, NULL)
			|| mosquitto_callback_register(identifier, MOSQ_EVT_CONNECT, on_connect, NULL, NULL)
			|| mosquitto_callback_register(identifier, MOSQ_EVT_DISCONNECT, on_disconnect, NULL, NULL)
			|| mosquitto_callback_register(identifier, MOSQ_EVT_CLIENT_OFFLINE, on_client_offline, NULL, NULL)
			|| mosquitto_callback_register(identifier, MOSQ_EVT_SUBSCRIBE, on_subscribe, NULL, NULL)
			|| mosquitto_callback_register(identifier, MOSQ_EVT_UNSUBSCRIBE, on_unsubscribe, NULL, NULL)
			|| mosquitto_callback_register(identifier, MOSQ_EVT_MESSAGE_IN, on_message_in, NULL, NULL)
			|| mosquitto_callback_register(identifier, MOSQ_EVT_MESSAGE_OUT, on_message_out, NULL, NULL)
			|| mosquitto_callback_register(identifier, MOSQ_EVT_ACL_CHECK, on_acl_check, NULL, NULL)
			|| mosquitto_callback_register(identifier, MOSQ_EVT_TICK, on_tick, NULL, NULL)){
		mosquitto_log_printf(MOSQ_LOG_ERR, "inspect: cannot register its callbacks.");
		return MOSQ_ERR_UNKNOWN;
	}
	mosquitto_broker_publish_copy(NULL, VERSION_TOPIC, (int)strlen(PLUGIN_VERSION), PLUGIN_VERSION, 0, true, NULL);
	return MOSQ_ERR_SUCCESS;
}

int mosquitto_plugin_cleanup(void *userdata, struct mosquitto_opt *opts, int opt_count)
{
	(void)userdata;
	(void)opts;
	(void)opt_count;
	for(int b = 0; b < BUCKETS; b++){
		while(plugin.buckets[b]){
			struct client *c = plugin.buckets[b];
			plugin.buckets[b] = c->next;
			free_client(c);
		}
	}
	free_trace();
	rules_cleanup();
	return MOSQ_ERR_SUCCESS;
}
