/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Alexander Schlenker
 *
 * Login of MQTT clients with an access token (JWT) of an identity provider, checked offline.
 *
 * The client sends the token as its password. The plugin checks it against the public keys in a JSON file that mosquitto-ui
 * writes (plugin_opt_config_file), takes the user name from a claim and the roles from a claim with groups, and from then on
 * decides every ACL check of that client itself, with the same rules as dynamic security. It never asks anyone over the
 * network: without the UI the last file stays in force.
 *
 * It has to be loaded before dynamic security. A password that is no JWT, a token that does not hold, or no file, and it
 * defers: dynamic security decides as before, and knows no such user or checks its own password. For a client it let in it
 * never defers, so a token can never borrow the rights of a dynamic security client of the same name. A token that has
 * expired, or is blocked, disconnects its client. The file is read again when it changes.
 *
 * With bindClientId, the client id has to be the user name or start with "<user name>/": a token cannot take over the session
 * of another client. Blocked are a user name, a subject (sub) or a single token (jti), of one issuer or of all, until a time
 * or for good.
 *
 * The clients logged in with a token are published, retained, on $SYS/broker/token-auth/clients whenever that changes, as a
 * JSON array of { clientid, username, issuer, sub, jti, address, expires, roles }: dynamic security does not know them.
 *
 * Tokens it did not accept are published, retained, on $SYS/broker/token-auth/rejected, the latest first, at most
 * MAX_REJECTED since the start of the broker: { at, count, clientid, address, issuer, kid, username, sub, jti, verified,
 * reason }. The same client with the same issuer, name and reason again is one entry, with count and at of the last time.
 * issuer, kid and username are what the token says; verified tells whether its signature held, and only then are sub and jti
 * there. The reason unknown_key means a kid that none of the keys of the issuer has: the UI fetches the keys again.
 *
 * The file (all keys required unless noted):
 *   { "enabled": true, "leeway": 30,
 *     "defaults": { "publishClientSend": false, "publishClientReceive": true, "subscribe": false, "unsubscribe": true },
 *     "reservedUsernames": [ "admin" ],                 user names a token never gets, those of dynamic security (optional)
 *     "roles": [ { "rolename": "sensors", "allowWildcardSubs": true,
 *                  "acls": [ { "acltype": "publishClientSend", "topic": "sensors/%u/#", "allow": true } ] } ],
 *                                                     roles in the order they are checked, ACLs highest priority first
 *     "issuers": [ { "issuer": "https://idp/realms/x", "audience": "mqtt" (optional),
 *                    "usernameClaim": "preferred_username", "groupsClaim": "groups", "usernamePrefix": "" (optional),
 *                    "bindClientId": true (optional),
 *                    "keys": [ { "kid": "abc" (optional), "pem": "-----BEGIN PUBLIC KEY-----..." } ],
 *                    "groups": { "sensor-devices": [ "sensors" ] } } ],
 *     "blocked": [ { "field": "username" | "sub" | "jti", "value": "...", "issuer": "..." (optional, else every issuer),
 *                    "until": 1793700000 (optional, seconds, else for good) } ] (optional) }
 */
#include <fcntl.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <cjson/cJSON.h>
#include <openssl/bio.h>
#include <openssl/bn.h>
#include <openssl/ecdsa.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/rsa.h>

#include "mosquitto.h"

#define PLUGIN_NAME "token-auth"
#define PLUGIN_VERSION "1"
#define MAX_TOKEN 16384
#define CLIENTS_TOPIC "$SYS/broker/token-auth/clients"
#define REJECTED_TOPIC "$SYS/broker/token-auth/rejected"
#define MAX_REJECTED 50

MOSQUITTO_PLUGIN_DECLARE_VERSION(5);

enum acl_type { PUB_SEND, PUB_RECV, SUB_LITERAL, SUB_PATTERN, UNSUB_LITERAL, UNSUB_PATTERN, ACL_TYPES };

static const char *ACL_NAMES[ACL_TYPES] = {
	"publishClientSend", "publishClientReceive", "subscribeLiteral", "subscribePattern", "unsubscribeLiteral", "unsubscribePattern"
};

struct acl {
	enum acl_type type;
	char *topic;
	bool allow;
};

struct role {
	char *name;
	bool allow_wildcard_subs;
	struct acl *acls;
	int acl_count;
};

struct key {
	char *kid;
	EVP_PKEY *pkey;
};

struct mapping {
	char *group;
	char **roles;
	int role_count;
};

enum block_field { BLOCK_USERNAME, BLOCK_SUB, BLOCK_JTI };

struct block {
	enum block_field field;
	char *value;
	char *issuer;   /* NULL: every issuer */
	time_t until;   /* 0: for good */
};

struct issuer {
	char *issuer;
	bool bind_clientid;
	char *audience;
	char *username_claim;
	char *groups_claim;
	char *username_prefix;
	struct key *keys;
	int key_count;
	struct mapping *mappings;
	int mapping_count;
};

struct config {
	bool enabled;
	long leeway;
	bool default_pub_send, default_pub_recv, default_sub, default_unsub;
	char **reserved;
	int reserved_count;
	struct role *roles;
	int role_count;
	struct issuer *issuers;
	int issuer_count;
	struct block *blocks;
	int block_count;
};

/* A client that came in with a token. Found by its pointer, checked against its client id. */
struct session {
	struct session *next;
	const struct mosquitto *client;
	char *clientid;
	char *username;
	char *address;
	char *issuer;
	char *sub;
	char *jti;
	char **groups;
	int group_count;
	time_t exp;
	bool offline;
	bool kicked;
	/* roles of the current file, in their order; recomputed when the file changes */
	const struct role **roles;
	int role_count;
};

/* A token not accepted, for the UI; what the token says may be anything, so it is cut short */
struct rejection {
	time_t at;
	int count;
	char *clientid;
	char *address;
	char *issuer;
	char *kid;
	char *username;
	char *sub;
	char *jti;
	bool verified;
	const char *reason;
};

struct plugin {
	mosquitto_plugin_id_t *pid;
	char *path;
	struct timespec mtime;
	off_t size;
	time_t last_stat;
	struct config *config;
	struct session *sessions;
	/* the list on CLIENTS_TOPIC has to be published again */
	bool clients_changed;
	/* oldest first */
	struct rejection rejections[MAX_REJECTED];
	int rejection_count;
	bool rejections_changed;
};

static struct plugin plugin;

/* ---------------------------------------------------------------- helpers */

static char *copy(const char *s)
{
	return s ? strdup(s) : NULL;
}

/* At most max bytes of a string that came with a token */
static char *copy_short(const char *s, size_t max)
{
	return s ? strndup(s, max) : NULL;
}

static bool same(const char *a, const char *b)
{
	return a == b || (a && b && !strcmp(a, b));
}

static const char *string_item(const cJSON *object, const char *name)
{
	const cJSON *item = cJSON_GetObjectItemCaseSensitive(object, name);
	return cJSON_IsString(item) ? item->valuestring : NULL;
}

static bool bool_item(const cJSON *object, const char *name, bool fallback)
{
	const cJSON *item = cJSON_GetObjectItemCaseSensitive(object, name);
	return cJSON_IsBool(item) ? cJSON_IsTrue(item) : fallback;
}

/* base64url without padding, as in a JWT; NULL on anything else */
static unsigned char *base64url_decode(const char *in, size_t len, size_t *out_len)
{
	static const char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
	unsigned char *out;
	size_t n = 0;
	unsigned int buffer = 0;
	int bits = 0;

	if(len % 4 == 1){
		return NULL;
	}
	out = malloc(len * 3 / 4 + 1);
	if(!out){
		return NULL;
	}
	for(size_t i = 0; i < len; i++){
		const char *p = memchr(alphabet, in[i], 64);
		if(!p || in[i] == '\0'){
			free(out);
			return NULL;
		}
		buffer = (buffer << 6) | (unsigned int)(p - alphabet);
		bits += 6;
		if(bits >= 8){
			bits -= 8;
			out[n++] = (unsigned char)((buffer >> bits) & 0xFF);
		}
	}
	out[n] = '\0';
	*out_len = n;
	return out;
}

static cJSON *decode_json(const char *in, size_t len)
{
	size_t n;
	unsigned char *raw = base64url_decode(in, len, &n);
	cJSON *json;

	if(!raw){
		return NULL;
	}
	json = cJSON_ParseWithLength((const char *)raw, n);
	free(raw);
	return json;
}

/* A claim by a path with dots, such as realm_access.roles */
static const cJSON *claim(const cJSON *payload, const char *path)
{
	char *p = strdup(path), *save = NULL;
	const cJSON *item = payload;

	if(!p){
		return NULL;
	}
	for(char *part = strtok_r(p, ".", &save); part && item; part = strtok_r(NULL, ".", &save)){
		item = cJSON_IsObject(item) ? cJSON_GetObjectItemCaseSensitive(item, part) : NULL;
	}
	free(p);
	return item;
}

/* ---------------------------------------------------------------- the file */

static void free_config(struct config *c)
{
	if(!c){
		return;
	}
	for(int i = 0; i < c->reserved_count; i++){
		free(c->reserved[i]);
	}
	free(c->reserved);
	for(int i = 0; i < c->role_count; i++){
		for(int j = 0; j < c->roles[i].acl_count; j++){
			free(c->roles[i].acls[j].topic);
		}
		free(c->roles[i].acls);
		free(c->roles[i].name);
	}
	free(c->roles);
	for(int i = 0; i < c->issuer_count; i++){
		struct issuer *is = &c->issuers[i];
		for(int j = 0; j < is->key_count; j++){
			free(is->keys[j].kid);
			EVP_PKEY_free(is->keys[j].pkey);
		}
		free(is->keys);
		for(int j = 0; j < is->mapping_count; j++){
			for(int k = 0; k < is->mappings[j].role_count; k++){
				free(is->mappings[j].roles[k]);
			}
			free(is->mappings[j].roles);
			free(is->mappings[j].group);
		}
		free(is->mappings);
		free(is->issuer);
		free(is->audience);
		free(is->username_claim);
		free(is->groups_claim);
		free(is->username_prefix);
	}
	free(c->issuers);
	for(int i = 0; i < c->block_count; i++){
		free(c->blocks[i].value);
		free(c->blocks[i].issuer);
	}
	free(c->blocks);
	free(c);
}

static char **string_array(const cJSON *array, int *count)
{
	char **out;
	const cJSON *item;
	int n = 0;

	*count = 0;
	if(!cJSON_IsArray(array)){
		return NULL;
	}
	out = calloc((size_t)cJSON_GetArraySize(array) + 1, sizeof(char *));
	if(!out){
		return NULL;
	}
	cJSON_ArrayForEach(item, array){
		if(cJSON_IsString(item)){
			out[n++] = strdup(item->valuestring);
		}
	}
	*count = n;
	return out;
}

static bool parse_roles(struct config *c, const cJSON *roles)
{
	const cJSON *role;
	int i = 0;

	if(!cJSON_IsArray(roles)){
		return false;
	}
	c->roles = calloc((size_t)cJSON_GetArraySize(roles) + 1, sizeof(struct role));
	if(!c->roles){
		return false;
	}
	cJSON_ArrayForEach(role, roles){
		struct role *r = &c->roles[i++];
		const cJSON *acls = cJSON_GetObjectItemCaseSensitive(role, "acls"), *acl;
		int j = 0;

		c->role_count = i;
		if(!string_item(role, "rolename") || !cJSON_IsArray(acls)){
			return false;
		}
		r->name = strdup(string_item(role, "rolename"));
		r->allow_wildcard_subs = bool_item(role, "allowWildcardSubs", true);
		r->acls = calloc((size_t)cJSON_GetArraySize(acls) + 1, sizeof(struct acl));
		if(!r->acls){
			return false;
		}
		cJSON_ArrayForEach(acl, acls){
			const char *type = string_item(acl, "acltype"), *topic = string_item(acl, "topic");
			int t;

			if(!type || !topic){
				return false;
			}
			for(t = 0; t < ACL_TYPES && strcmp(ACL_NAMES[t], type); t++){
			}
			if(t == ACL_TYPES){
				return false;
			}
			r->acls[j].type = (enum acl_type)t;
			r->acls[j].topic = strdup(topic);
			r->acls[j].allow = bool_item(acl, "allow", false);
			r->acl_count = ++j;
		}
	}
	return true;
}

static EVP_PKEY *read_key(const char *pem)
{
	BIO *bio = BIO_new_mem_buf(pem, -1);
	EVP_PKEY *pkey;

	if(!bio){
		return NULL;
	}
	pkey = PEM_read_bio_PUBKEY(bio, NULL, NULL, NULL);
	BIO_free(bio);
	return pkey;
}

static bool parse_issuers(struct config *c, const cJSON *issuers)
{
	const cJSON *item;
	int i = 0;

	if(!cJSON_IsArray(issuers)){
		return false;
	}
	c->issuers = calloc((size_t)cJSON_GetArraySize(issuers) + 1, sizeof(struct issuer));
	if(!c->issuers){
		return false;
	}
	cJSON_ArrayForEach(item, issuers){
		struct issuer *is = &c->issuers[i++];
		const cJSON *keys = cJSON_GetObjectItemCaseSensitive(item, "keys"), *key;
		const cJSON *groups = cJSON_GetObjectItemCaseSensitive(item, "groups"), *group;
		int j = 0;

		c->issuer_count = i;
		if(!string_item(item, "issuer") || !string_item(item, "usernameClaim") || !string_item(item, "groupsClaim")
				|| !cJSON_IsArray(keys) || !cJSON_IsObject(groups)){
			return false;
		}
		is->issuer = strdup(string_item(item, "issuer"));
		is->audience = copy(string_item(item, "audience"));
		is->username_claim = strdup(string_item(item, "usernameClaim"));
		is->groups_claim = strdup(string_item(item, "groupsClaim"));
		is->username_prefix = strdup(string_item(item, "usernamePrefix") ? string_item(item, "usernamePrefix") : "");
		is->bind_clientid = bool_item(item, "bindClientId", false);
		is->keys = calloc((size_t)cJSON_GetArraySize(keys) + 1, sizeof(struct key));
		is->mappings = calloc((size_t)cJSON_GetArraySize(groups) + 1, sizeof(struct mapping));
		if(!is->keys || !is->mappings){
			return false;
		}
		cJSON_ArrayForEach(key, keys){
			const char *pem = string_item(key, "pem");
			EVP_PKEY *pkey = pem ? read_key(pem) : NULL;

			if(!pkey){
				mosquitto_log_printf(MOSQ_LOG_WARNING, "token-auth: a key of %s cannot be read, left out.", is->issuer);
				continue;
			}
			is->keys[j].kid = copy(string_item(key, "kid"));
			is->keys[j].pkey = pkey;
			is->key_count = ++j;
		}
		j = 0;
		cJSON_ArrayForEach(group, groups){
			is->mappings[j].group = strdup(group->string);
			is->mappings[j].roles = string_array(group, &is->mappings[j].role_count);
			is->mapping_count = ++j;
		}
	}
	return true;
}

static bool parse_blocks(struct config *c, const cJSON *blocks)
{
	const cJSON *item;
	int i = 0;

	if(!blocks){
		return true;
	}
	if(!cJSON_IsArray(blocks)){
		return false;
	}
	c->blocks = calloc((size_t)cJSON_GetArraySize(blocks) + 1, sizeof(struct block));
	if(!c->blocks){
		return false;
	}
	cJSON_ArrayForEach(item, blocks){
		const char *field = string_item(item, "field"), *value = string_item(item, "value");
		const cJSON *until = cJSON_GetObjectItemCaseSensitive(item, "until");
		struct block *b = &c->blocks[i];

		if(!field || !value || !value[0]){
			return false;
		}
		if(!strcmp(field, "username")){
			b->field = BLOCK_USERNAME;
		}else if(!strcmp(field, "sub")){
			b->field = BLOCK_SUB;
		}else if(!strcmp(field, "jti")){
			b->field = BLOCK_JTI;
		}else{
			return false;
		}
		b->value = strdup(value);
		b->issuer = copy(string_item(item, "issuer"));
		b->until = cJSON_IsNumber(until) ? (time_t)until->valuedouble : 0;
		c->block_count = ++i;
	}
	return true;
}

static struct config *parse_config(const char *text, size_t len)
{
	cJSON *json = cJSON_ParseWithLength(text, len);
	struct config *c = calloc(1, sizeof(struct config));
	const cJSON *defaults, *leeway;
	bool ok;

	if(!json || !c){
		cJSON_Delete(json);
		free(c);
		return NULL;
	}
	defaults = cJSON_GetObjectItemCaseSensitive(json, "defaults");
	leeway = cJSON_GetObjectItemCaseSensitive(json, "leeway");
	c->enabled = bool_item(json, "enabled", false);
	c->leeway = cJSON_IsNumber(leeway) && leeway->valuedouble >= 0 && leeway->valuedouble <= 600 ? (long)leeway->valuedouble : 30;
	c->default_pub_send = bool_item(defaults, "publishClientSend", false);
	c->default_pub_recv = bool_item(defaults, "publishClientReceive", false);
	c->default_sub = bool_item(defaults, "subscribe", false);
	c->default_unsub = bool_item(defaults, "unsubscribe", false);
	c->reserved = string_array(cJSON_GetObjectItemCaseSensitive(json, "reservedUsernames"), &c->reserved_count);
	ok = cJSON_IsObject(defaults)
			&& parse_roles(c, cJSON_GetObjectItemCaseSensitive(json, "roles"))
			&& parse_issuers(c, cJSON_GetObjectItemCaseSensitive(json, "issuers"))
			&& parse_blocks(c, cJSON_GetObjectItemCaseSensitive(json, "blocked"));
	cJSON_Delete(json);
	if(!ok){
		free_config(c);
		return NULL;
	}
	return c;
}

static const struct issuer *find_issuer(const struct config *c, const char *iss)
{
	for(int i = 0; c && i < c->issuer_count; i++){
		if(!strcmp(c->issuers[i].issuer, iss)){
			return &c->issuers[i];
		}
	}
	return NULL;
}

/* Whether a block of the file holds for a login with these values; a block whose time is over no longer counts */
static bool blocked(const struct config *c, const char *issuer, const char *username, const char *sub, const char *jti)
{
	time_t now = time(NULL);

	for(int i = 0; c && i < c->block_count; i++){
		const struct block *b = &c->blocks[i];
		const char *value = b->field == BLOCK_USERNAME ? username : b->field == BLOCK_SUB ? sub : jti;

		if(b->until && b->until <= now){
			continue;
		}
		if(b->issuer && strcmp(b->issuer, issuer)){
			continue;
		}
		if(value && !strcmp(b->value, value)){
			return true;
		}
	}
	return false;
}

/*
 * The roles of the groups of a session under the current file, in the order of the file. False when the issuer is gone.
 */
static bool resolve_roles(struct session *s, const struct config *c)
{
	const struct issuer *is = c && c->enabled ? find_issuer(c, s->issuer) : NULL;

	free(s->roles);
	s->roles = NULL;
	s->role_count = 0;
	/* a block added to the file takes the rights away, and the next tick disconnects */
	if(!is || (s->username && blocked(c, s->issuer, s->username, s->sub, s->jti))){
		return false;
	}
	s->roles = calloc((size_t)c->role_count + 1, sizeof(struct role *));
	if(!s->roles){
		return false;
	}
	for(int r = 0; r < c->role_count; r++){
		bool granted = false;
		for(int m = 0; m < is->mapping_count && !granted; m++){
			for(int g = 0; g < s->group_count && !granted; g++){
				if(strcmp(is->mappings[m].group, s->groups[g])){
					continue;
				}
				for(int k = 0; k < is->mappings[m].role_count && !granted; k++){
					granted = !strcmp(is->mappings[m].roles[k], c->roles[r].name);
				}
			}
		}
		if(granted){
			s->roles[s->role_count++] = &c->roles[r];
		}
	}
	return true;
}

static void free_session(struct session *s)
{
	for(int i = 0; i < s->group_count; i++){
		free(s->groups[i]);
	}
	free(s->groups);
	free(s->roles);
	free(s->clientid);
	free(s->username);
	free(s->address);
	free(s->issuer);
	free(s->sub);
	free(s->jti);
	free(s);
}

/* Reads the file again when it changed; a file that cannot be read leaves the one before in force */
static void load_config(bool force)
{
	struct stat st;
	FILE *f;
	char *text;
	struct config *c;
	size_t n;
	int fd;

	/* the UI writes the file, so a link there is never followed */
	if(lstat(plugin.path, &st) || !S_ISREG(st.st_mode)){
		if(plugin.config){
			mosquitto_log_printf(MOSQ_LOG_NOTICE, "token-auth: %s is gone or no file, token login off.", plugin.path);
			free_config(plugin.config);
			plugin.config = NULL;
			for(struct session *s = plugin.sessions; s; s = s->next){
				if(!s->kicked){
					resolve_roles(s, NULL);
				}
			}
		}
		plugin.mtime.tv_sec = 0;
		return;
	}
	if(!force && st.st_mtim.tv_sec == plugin.mtime.tv_sec && st.st_mtim.tv_nsec == plugin.mtime.tv_nsec && st.st_size == plugin.size){
		return;
	}
	plugin.mtime = st.st_mtim;
	plugin.size = st.st_size;
	fd = st.st_size > 0 && st.st_size <= 4 * 1024 * 1024 ? open(plugin.path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC) : -1;
	f = fd >= 0 ? fdopen(fd, "r") : NULL;
	if(!f){
		if(fd >= 0){
			close(fd);
		}
		mosquitto_log_printf(MOSQ_LOG_WARNING, "token-auth: cannot read %s, keeping what was in force.", plugin.path);
		return;
	}
	text = malloc((size_t)st.st_size);
	n = text ? fread(text, 1, (size_t)st.st_size, f) : 0;
	fclose(f);
	c = n == (size_t)st.st_size ? parse_config(text, n) : NULL;
	free(text);
	if(!c){
		mosquitto_log_printf(MOSQ_LOG_WARNING, "token-auth: %s is not valid, keeping what was in force.", plugin.path);
		return;
	}
	free_config(plugin.config);
	plugin.config = c;
	for(struct session *s = plugin.sessions; s; s = s->next){
		if(!s->kicked){
			resolve_roles(s, c);
		}
	}
	plugin.clients_changed = true;
	mosquitto_log_printf(MOSQ_LOG_NOTICE, "token-auth: %s with %d issuer(s) and %d role(s), %s.", plugin.path,
			c->issuer_count, c->role_count, c->enabled ? "on" : "off");
}

/* ---------------------------------------------------------------- the token */

static const EVP_MD *digest_of(const char *alg, int *family, int *ec_bits)
{
	static const struct { const char *alg; int family; int bits; } algs[] = {
		{"RS256", EVP_PKEY_RSA, 256}, {"RS384", EVP_PKEY_RSA, 384}, {"RS512", EVP_PKEY_RSA, 512},
		{"PS256", EVP_PKEY_RSA_PSS, 256}, {"PS384", EVP_PKEY_RSA_PSS, 384}, {"PS512", EVP_PKEY_RSA_PSS, 512},
		{"ES256", EVP_PKEY_EC, 256}, {"ES384", EVP_PKEY_EC, 384}, {"ES512", EVP_PKEY_EC, 512},
	};
	for(size_t i = 0; i < sizeof(algs) / sizeof(algs[0]); i++){
		if(!strcmp(algs[i].alg, alg)){
			*family = algs[i].family;
			*ec_bits = algs[i].family == EVP_PKEY_EC ? (algs[i].bits == 512 ? 521 : algs[i].bits) : 0;
			return algs[i].bits == 256 ? EVP_sha256() : algs[i].bits == 384 ? EVP_sha384() : EVP_sha512();
		}
	}
	return NULL;
}

/* ES*: the signature in a JWT is r||s, OpenSSL wants DER */
static unsigned char *ecdsa_der(const unsigned char *raw, size_t len, int bits, size_t *der_len)
{
	size_t half = ((size_t)bits + 7) / 8;
	ECDSA_SIG *sig;
	BIGNUM *r, *s;
	unsigned char *der = NULL;
	int n;

	if(len != 2 * half){
		return NULL;
	}
	sig = ECDSA_SIG_new();
	r = BN_bin2bn(raw, (int)half, NULL);
	s = BN_bin2bn(raw + half, (int)half, NULL);
	if(!sig || !r || !s || !ECDSA_SIG_set0(sig, r, s)){
		ECDSA_SIG_free(sig);
		BN_free(r);
		BN_free(s);
		return NULL;
	}
	n = i2d_ECDSA_SIG(sig, &der);
	ECDSA_SIG_free(sig);
	if(n <= 0){
		return NULL;
	}
	*der_len = (size_t)n;
	return der;
}

static bool verify(EVP_PKEY *pkey, const char *alg, const char *signed_part, size_t signed_len,
		const unsigned char *sig, size_t sig_len)
{
	int family, ec_bits, key_family = EVP_PKEY_get_base_id(pkey);
	const EVP_MD *md = digest_of(alg, &family, &ec_bits);
	EVP_MD_CTX *ctx;
	EVP_PKEY_CTX *pctx = NULL;
	unsigned char *der = NULL;
	bool ok = false;

	if(!md){
		return false;
	}
	/* the key decides what it may verify: an RSA key never verifies ES*, an EC key only its own curve */
	if(family == EVP_PKEY_RSA_PSS ? key_family != EVP_PKEY_RSA && key_family != EVP_PKEY_RSA_PSS : key_family != family){
		return false;
	}
	if(family == EVP_PKEY_EC){
		if(EVP_PKEY_get_bits(pkey) != ec_bits || !(der = ecdsa_der(sig, sig_len, ec_bits, &sig_len))){
			return false;
		}
		sig = der;
	}else if(EVP_PKEY_get_bits(pkey) < 2048){
		return false;
	}
	ctx = EVP_MD_CTX_new();
	if(ctx && EVP_DigestVerifyInit(ctx, &pctx, md, NULL, pkey) == 1){
		if(family == EVP_PKEY_RSA_PSS){
			ok = EVP_PKEY_CTX_set_rsa_padding(pctx, RSA_PKCS1_PSS_PADDING) == 1
					&& EVP_PKEY_CTX_set_rsa_pss_saltlen(pctx, RSA_PSS_SALTLEN_DIGEST) == 1;
		}else{
			ok = true;
		}
		ok = ok && EVP_DigestVerify(ctx, sig, sig_len, (const unsigned char *)signed_part, signed_len) == 1;
	}
	EVP_MD_CTX_free(ctx);
	OPENSSL_free(der);
	return ok;
}

static bool audience_matches(const cJSON *aud, const char *audience)
{
	const cJSON *item;

	if(cJSON_IsString(aud)){
		return !strcmp(aud->valuestring, audience);
	}
	cJSON_ArrayForEach(item, aud){
		if(cJSON_IsString(item) && !strcmp(item->valuestring, audience)){
			return true;
		}
	}
	return false;
}

/* Whether a password looks like a JWT: three parts in base64url, the first one a JSON object */
static bool looks_like_jwt(const char *password, const char **dot1, const char **dot2)
{
	size_t len = strlen(password);

	if(len < 10 || len > MAX_TOKEN || password[0] != 'e' || password[1] != 'y'){
		return false;
	}
	*dot1 = strchr(password, '.');
	*dot2 = *dot1 ? strchr(*dot1 + 1, '.') : NULL;
	return *dot2 && !strchr(*dot2 + 1, '.') && strspn(password,
			"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.") == len;
}

/* Why a token was not accepted: a code for the UI, a text for the log */
static const struct { const char *code; const char *text; } REASONS[] = {
	{"not_jwt", "not a JWT"},
	{"algorithm", "algorithm not allowed"},
	{"critical_header", "critical header"},
	{"unknown_issuer", "unknown issuer"},
	{"no_signature", "no signature"},
	{"unknown_key", "no key with the kid of the token"},
	{"signature", "signature not valid"},
	{"no_expiry", "no expiry"},
	{"expired", "expired"},
	{"not_yet_valid", "not valid yet"},
	{"audience", "wrong audience"},
	{"no_username", "no user name in the token"},
	{"username_reserved", "user name taken by dynamic security"},
	{"clientid", "client id does not belong to the user name"},
	{"blocked", "blocked"},
	{"no_role", "no role"},
	{"error", "internal error"},
};

static const char *reason_text(const char *code)
{
	for(size_t i = 0; i < sizeof(REASONS) / sizeof(REASONS[0]); i++){
		if(!strcmp(REASONS[i].code, code)){
			return REASONS[i].text;
		}
	}
	return code;
}

struct verdict {
	const char *reason;          /* why not: a code of REASONS */
	char *iss;                   /* what the token says, for the list of rejected tokens */
	char *kid;
	char *claimed;               /* the user name the token claims, with the prefix of its issuer */
	bool verified;               /* the signature held */
	char *username;
	char *sub;
	char *jti;
	const struct issuer *issuer;
	char **groups;
	int group_count;
	time_t exp;
};

static void check_token(const struct config *c, const char *clientid, const char *token, const char *dot1, const char *dot2,
		struct verdict *v)
{
	cJSON *header = decode_json(token, (size_t)(dot1 - token));
	cJSON *payload = decode_json(dot1 + 1, (size_t)(dot2 - dot1 - 1));
	const char *alg, *kid, *iss, *name;
	const cJSON *exp, *nbf, *groups, *aud;
	unsigned char *sig = NULL;
	size_t sig_len = 0;
	time_t now = time(NULL);
	bool verified = false;

	v->reason = NULL;
	if(!cJSON_IsObject(header) || !cJSON_IsObject(payload)){
		v->reason = "not_jwt";
		goto done;
	}
	alg = string_item(header, "alg");
	kid = string_item(header, "kid");
	iss = string_item(payload, "iss");
	v->iss = copy_short(iss, 200);
	v->kid = copy_short(kid, 100);
	if(!alg || !digest_of(alg, &(int){0}, &(int){0})){
		v->reason = "algorithm";
		goto done;
	}
	if(cJSON_GetObjectItemCaseSensitive(header, "crit")){
		v->reason = "critical_header";
		goto done;
	}
	if(!iss || !(v->issuer = find_issuer(c, iss))){
		v->reason = "unknown_issuer";
		goto done;
	}
	{
		/* what the token claims, before anything is checked: only for the list of rejected tokens */
		const cJSON *user = claim(payload, v->issuer->username_claim);
		if(cJSON_IsString(user) && user->valuestring[0]){
			size_t n = strlen(v->issuer->username_prefix) + strnlen(user->valuestring, 100);
			v->claimed = malloc(n + 1);
			if(v->claimed){
				snprintf(v->claimed, n + 1, "%s%.100s", v->issuer->username_prefix, user->valuestring);
			}
		}
	}
	sig = base64url_decode(dot2 + 1, strlen(dot2 + 1), &sig_len);
	if(!sig || sig_len == 0){
		v->reason = "no_signature";
		goto done;
	}
	{
		int tried = 0;
		for(int i = 0; i < v->issuer->key_count && !verified; i++){
			const struct key *k = &v->issuer->keys[i];
			if(kid && k->kid && strcmp(kid, k->kid)){
				continue;
			}
			tried++;
			verified = verify(k->pkey, alg, token, (size_t)(dot2 - token), sig, sig_len);
		}
		if(!verified){
			/* a key the provider has made since the keys were last fetched, most likely */
			v->reason = kid && tried == 0 ? "unknown_key" : "signature";
			goto done;
		}
	}
	v->verified = true;
	v->sub = copy(string_item(payload, "sub"));
	v->jti = copy(string_item(payload, "jti"));
	exp = cJSON_GetObjectItemCaseSensitive(payload, "exp");
	nbf = cJSON_GetObjectItemCaseSensitive(payload, "nbf");
	if(!cJSON_IsNumber(exp)){
		v->reason = "no_expiry";
		goto done;
	}
	v->exp = (time_t)exp->valuedouble;
	if(v->exp + c->leeway < now){
		v->reason = "expired";
		goto done;
	}
	if(cJSON_IsNumber(nbf) && (time_t)nbf->valuedouble - c->leeway > now){
		v->reason = "not_yet_valid";
		goto done;
	}
	aud = cJSON_GetObjectItemCaseSensitive(payload, "aud");
	if(v->issuer->audience && v->issuer->audience[0] && !audience_matches(aud, v->issuer->audience)){
		v->reason = "audience";
		goto done;
	}
	{
		const cJSON *user = claim(payload, v->issuer->username_claim);
		name = cJSON_IsString(user) ? user->valuestring : NULL;
	}
	if(!name || !name[0]){
		v->reason = "no_username";
		goto done;
	}
	v->username = malloc(strlen(v->issuer->username_prefix) + strlen(name) + 1);
	if(!v->username){
		v->reason = "error";
		goto done;
	}
	strcpy(v->username, v->issuer->username_prefix);
	strcat(v->username, name);
	for(int i = 0; i < c->reserved_count; i++){
		if(!strcmp(c->reserved[i], v->username)){
			v->reason = "username_reserved";
			goto done;
		}
	}
	if(v->issuer->bind_clientid){
		size_t n = strlen(v->username);
		if(!clientid || strncmp(clientid, v->username, n) || (clientid[n] != '\0' && clientid[n] != '/')){
			v->reason = "clientid";
			goto done;
		}
	}
	if(blocked(c, v->issuer->issuer, v->username, v->sub, v->jti)){
		v->reason = "blocked";
		goto done;
	}
	groups = claim(payload, v->issuer->groups_claim);
	if(cJSON_IsString(groups)){
		v->groups = calloc(2, sizeof(char *));
		if(v->groups){
			v->groups[0] = strdup(groups->valuestring);
			v->group_count = 1;
		}
	}else{
		v->groups = string_array(groups, &v->group_count);
	}
done:
	free(sig);
	cJSON_Delete(header);
	cJSON_Delete(payload);
}

/* ---------------------------------------------------------------- events */

static void remove_session(const struct mosquitto *client)
{
	for(struct session **p = &plugin.sessions; *p; p = &(*p)->next){
		if((*p)->client == client){
			struct session *s = *p;
			*p = s->next;
			free_session(s);
			plugin.clients_changed = true;
			return;
		}
	}
}

static struct session *find_session(const struct mosquitto *client)
{
	const char *clientid = mosquitto_client_id(client);

	for(struct session *s = plugin.sessions; s; s = s->next){
		if(s->client == client){
			/* a pointer used again by another client would not carry the same client id */
			return clientid && !strcmp(clientid, s->clientid) ? s : NULL;
		}
	}
	return NULL;
}

static void free_rejection(struct rejection *r)
{
	free(r->clientid);
	free(r->address);
	free(r->issuer);
	free(r->kid);
	free(r->username);
	free(r->sub);
	free(r->jti);
	memset(r, 0, sizeof(*r));
}

/*
 * Keeps a token that was not accepted for the list of rejected tokens; the same client with the same issuer, name and reason
 * again counts up, so that a client that keeps trying does not push out all the others.
 */
static void record_rejection(const char *clientid, const char *address, const struct verdict *v, const char *sub, const char *jti)
{
	const char *username = v->verified && v->username ? v->username : v->claimed;
	struct rejection r = {0};
	int count = 1;

	for(int i = 0; i < plugin.rejection_count; i++){
		struct rejection *old = &plugin.rejections[i];
		if(same(old->reason, v->reason) && same(old->clientid, clientid) && same(old->issuer, v->iss) && same(old->username, username)
				&& same(old->kid, v->kid)){
			count = old->count + 1;
			free_rejection(old);
			memmove(old, old + 1, (size_t)(plugin.rejection_count - i - 1) * sizeof(struct rejection));
			plugin.rejection_count--;
			memset(&plugin.rejections[plugin.rejection_count], 0, sizeof(struct rejection));
			break;
		}
	}
	if(plugin.rejection_count == MAX_REJECTED){
		free_rejection(&plugin.rejections[0]);
		memmove(&plugin.rejections[0], &plugin.rejections[1], (MAX_REJECTED - 1) * sizeof(struct rejection));
		plugin.rejection_count--;
		memset(&plugin.rejections[plugin.rejection_count], 0, sizeof(struct rejection));
	}
	r.at = time(NULL);
	r.count = count;
	r.clientid = copy_short(clientid, 200);
	r.address = copy(address);
	r.issuer = copy(v->iss);
	r.kid = copy(v->kid);
	r.username = copy_short(username, 200);
	r.verified = v->verified;
	r.sub = v->verified ? copy_short(sub, 200) : NULL;
	r.jti = v->verified ? copy_short(jti, 200) : NULL;
	r.reason = v->reason;
	plugin.rejections[plugin.rejection_count++] = r;
	plugin.rejections_changed = true;
}

static void free_verdict(struct verdict *v)
{
	free(v->iss);
	free(v->kid);
	free(v->claimed);
	free(v->username);
	free(v->sub);
	free(v->jti);
	for(int i = 0; i < v->group_count; i++){
		free(v->groups[i]);
	}
	free(v->groups);
	memset(v, 0, sizeof(*v));
}

static int on_basic_auth(int event, void *event_data, void *userdata)
{
	struct mosquitto_evt_basic_auth *ed = event_data;
	const char *dot1, *dot2, *clientid;
	struct verdict v = {0};
	struct session *s;
	const struct config *c;

	(void)event;
	(void)userdata;

	/* whatever this pointer was before, it is a new connection now */
	remove_session(ed->client);
	load_config(false);
	c = plugin.config;
	if(!c || !c->enabled || !ed->password || !looks_like_jwt(ed->password, &dot1, &dot2)){
		return MOSQ_ERR_PLUGIN_DEFER;
	}
	clientid = mosquitto_client_id(ed->client);
	check_token(c, clientid, ed->password, dot1, dot2, &v);
	if(v.reason){
		mosquitto_log_printf(MOSQ_LOG_NOTICE, "token-auth: token of client %s from %s not accepted: %s.", clientid ? clientid : "?",
				mosquitto_client_address(ed->client), reason_text(v.reason));
		record_rejection(clientid, mosquitto_client_address(ed->client), &v, v.sub, v.jti);
		goto refused;
	}
	s = calloc(1, sizeof(struct session));
	if(!s || !(s->clientid = copy(clientid)) || !(s->issuer = strdup(v.issuer->issuer))){
		if(s){
			free(s->clientid);
			free(s);
		}
		v.reason = "error";
		record_rejection(clientid, mosquitto_client_address(ed->client), &v, v.sub, v.jti);
		goto refused;
	}
	s->client = ed->client;
	s->groups = v.groups;
	s->group_count = v.group_count;
	s->exp = v.exp;
	/* the session owns them now */
	s->sub = v.sub;
	s->jti = v.jti;
	v.sub = NULL;
	v.jti = NULL;
	v.groups = NULL;
	v.group_count = 0;
	resolve_roles(s, c);
	if(s->role_count == 0){
		mosquitto_log_printf(MOSQ_LOG_NOTICE, "token-auth: token of client %s from %s not accepted: %s has no role.", clientid,
				mosquitto_client_address(ed->client), v.username);
		v.reason = "no_role";
		record_rejection(clientid, mosquitto_client_address(ed->client), &v, s->sub, s->jti);
		free_session(s);
		goto refused;
	}
	if(mosquitto_set_username(ed->client, v.username) != MOSQ_ERR_SUCCESS){
		v.reason = "error";
		record_rejection(clientid, mosquitto_client_address(ed->client), &v, s->sub, s->jti);
		free_session(s);
		goto refused;
	}
	s->username = v.username;
	v.username = NULL;
	s->address = copy(mosquitto_client_address(ed->client));
	s->next = plugin.sessions;
	plugin.sessions = s;
	plugin.clients_changed = true;
	mosquitto_log_printf(MOSQ_LOG_INFO, "token-auth: client %s logged in as %s (%s), %d role(s).", clientid, s->username,
			v.issuer->issuer, s->role_count);
	free_verdict(&v);
	return MOSQ_ERR_SUCCESS;

refused:
	free_verdict(&v);
	/* dynamic security has the last word: it knows no such user, or checks the password of its own */
	return MOSQ_ERR_PLUGIN_DEFER;
}

/* The same rules as dynamic security: roles in order, the first ACL of the type that matches decides */
static int check_roles(const struct session *s, struct mosquitto_evt_acl_check *ed, enum acl_type literal, enum acl_type pattern)
{
	const char *clientid = mosquitto_client_id(ed->client), *username = mosquitto_client_username(ed->client);
	bool subscribe = literal == SUB_LITERAL, has_wildcard = strpbrk(ed->topic, "+#") != NULL;

	for(int r = 0; r < s->role_count; r++){
		const struct role *role = s->roles[r];

		if(subscribe && has_wildcard && !role->allow_wildcard_subs){
			return MOSQ_ERR_ACL_DENIED;
		}
		/* literal ones first, as dynamic security looks them up before it goes through the patterns */
		for(int a = 0; literal != pattern && a < role->acl_count; a++){
			if(role->acls[a].type == literal && !strcmp(role->acls[a].topic, ed->topic)){
				return role->acls[a].allow ? MOSQ_ERR_SUCCESS : MOSQ_ERR_ACL_DENIED;
			}
		}
		for(int a = 0; a < role->acl_count; a++){
			bool result = false;
			int rc;

			if(role->acls[a].type != pattern){
				continue;
			}
			if(literal == pattern){
				rc = mosquitto_topic_matches_sub_with_pattern(role->acls[a].topic, ed->topic, clientid, username, &result);
			}else{
				rc = mosquitto_sub_matches_acl_with_pattern(role->acls[a].topic, ed->topic, clientid, username, &result);
			}
			if(rc){
				return MOSQ_ERR_ACL_DENIED;
			}
			if(result){
				return role->acls[a].allow ? MOSQ_ERR_SUCCESS : MOSQ_ERR_ACL_DENIED;
			}
		}
	}
	return MOSQ_ERR_NOT_FOUND;
}

static int on_acl_check(int event, void *event_data, void *userdata)
{
	struct mosquitto_evt_acl_check *ed = event_data;
	const struct session *s = find_session(ed->client);
	const struct config *c = plugin.config;
	bool fallback;
	int rc;

	(void)event;
	(void)userdata;

	if(!s){
		return MOSQ_ERR_PLUGIN_DEFER;
	}
	/* never defer for a client that came in with a token, not even when the file is gone */
	if(!c || !c->enabled || s->role_count == 0){
		return MOSQ_ERR_ACL_DENIED;
	}
	switch(ed->access){
		case MOSQ_ACL_SUBSCRIBE:
			rc = check_roles(s, ed, SUB_LITERAL, SUB_PATTERN);
			fallback = c->default_sub;
			break;
		case MOSQ_ACL_UNSUBSCRIBE:
			rc = check_roles(s, ed, UNSUB_LITERAL, UNSUB_PATTERN);
			fallback = c->default_unsub;
			break;
		case MOSQ_ACL_WRITE:
			rc = check_roles(s, ed, PUB_SEND, PUB_SEND);
			fallback = c->default_pub_send;
			break;
		case MOSQ_ACL_READ:
			rc = check_roles(s, ed, PUB_RECV, PUB_RECV);
			fallback = c->default_pub_recv;
			break;
		default:
			return MOSQ_ERR_ACL_DENIED;
	}
	if(rc != MOSQ_ERR_NOT_FOUND){
		return rc;
	}
	if(!fallback || !strncmp(ed->topic, "$CONTROL", strlen("$CONTROL"))){
		return MOSQ_ERR_ACL_DENIED;
	}
	return MOSQ_ERR_SUCCESS;
}

static int on_disconnect(int event, void *event_data, void *userdata)
{
	struct mosquitto_evt_disconnect *ed = event_data;

	(void)event;
	(void)userdata;
	remove_session(ed->client);
	return MOSQ_ERR_SUCCESS;
}

/* A session that stays: kept, so that messages for it still pass the ACL check, until its token expires */
static int on_client_offline(int event, void *event_data, void *userdata)
{
	struct mosquitto_evt_client_offline *ed = event_data;

	(void)event;
	(void)userdata;
	for(struct session *s = plugin.sessions; s; s = s->next){
		if(s->client == ed->client){
			s->offline = true;
			plugin.clients_changed = true;
		}
	}
	return MOSQ_ERR_SUCCESS;
}

/* Those connected with a token, for the UI: dynamic security does not know them */
static void publish_clients(void)
{
	cJSON *list = cJSON_CreateArray();
	char *text;

	if(!list){
		return;
	}
	for(struct session *s = plugin.sessions; s; s = s->next){
		cJSON *item, *roles;
		if(s->offline || s->kicked){
			continue;
		}
		item = cJSON_CreateObject();
		if(!item){
			continue;
		}
		cJSON_AddStringToObject(item, "clientid", s->clientid);
		cJSON_AddStringToObject(item, "username", s->username ? s->username : "");
		cJSON_AddStringToObject(item, "issuer", s->issuer);
		if(s->sub){
			cJSON_AddStringToObject(item, "sub", s->sub);
		}
		if(s->jti){
			cJSON_AddStringToObject(item, "jti", s->jti);
		}
		cJSON_AddStringToObject(item, "address", s->address ? s->address : "");
		cJSON_AddNumberToObject(item, "expires", (double)s->exp);
		roles = cJSON_AddArrayToObject(item, "roles");
		for(int r = 0; roles && r < s->role_count; r++){
			cJSON_AddItemToArray(roles, cJSON_CreateString(s->roles[r]->name));
		}
		cJSON_AddItemToArray(list, item);
	}
	text = cJSON_PrintUnformatted(list);
	cJSON_Delete(list);
	if(text){
		mosquitto_broker_publish_copy(NULL, CLIENTS_TOPIC, (int)strlen(text), text, 0, true, NULL);
		cJSON_free(text);
	}
}

/* The tokens not accepted, the latest first */
static void publish_rejections(void)
{
	cJSON *list = cJSON_CreateArray();
	char *text;

	if(!list){
		return;
	}
	for(int i = plugin.rejection_count - 1; i >= 0; i--){
		const struct rejection *r = &plugin.rejections[i];
		cJSON *item = cJSON_CreateObject();
		if(!item){
			continue;
		}
		cJSON_AddNumberToObject(item, "at", (double)r->at);
		cJSON_AddNumberToObject(item, "count", r->count);
		cJSON_AddStringToObject(item, "clientid", r->clientid ? r->clientid : "");
		cJSON_AddStringToObject(item, "address", r->address ? r->address : "");
		if(r->issuer){
			cJSON_AddStringToObject(item, "issuer", r->issuer);
		}
		if(r->kid){
			cJSON_AddStringToObject(item, "kid", r->kid);
		}
		if(r->username){
			cJSON_AddStringToObject(item, "username", r->username);
		}
		if(r->sub){
			cJSON_AddStringToObject(item, "sub", r->sub);
		}
		if(r->jti){
			cJSON_AddStringToObject(item, "jti", r->jti);
		}
		cJSON_AddBoolToObject(item, "verified", r->verified);
		cJSON_AddStringToObject(item, "reason", r->reason);
		cJSON_AddItemToArray(list, item);
	}
	text = cJSON_PrintUnformatted(list);
	cJSON_Delete(list);
	if(text){
		mosquitto_broker_publish_copy(NULL, REJECTED_TOPIC, (int)strlen(text), text, 0, true, NULL);
		cJSON_free(text);
	}
}

static int on_tick(int event, void *event_data, void *userdata)
{
	struct mosquitto_evt_tick *ed = event_data;
	time_t now = time(NULL);
	char **expired = NULL;
	int count = 0;

	(void)event;
	(void)userdata;

	if(now != plugin.last_stat){
		plugin.last_stat = now;
		load_config(false);
		/* collected first: a kick removes the session through on_disconnect */
		for(struct session **p = &plugin.sessions; *p; ){
			struct session *s = *p;
			if(s->kicked && !s->offline){
				p = &s->next;
				continue;
			}
			if(!s->kicked && s->exp > now && s->role_count > 0){
				p = &s->next;
				continue;
			}
			if(s->offline){
				*p = s->next;
				free_session(s);
				plugin.clients_changed = true;
				continue;
			}
			char **more = realloc(expired, (size_t)(count + 1) * sizeof(char *));
			if(more){
				expired = more;
				expired[count++] = strdup(s->clientid);
			}
			/* kicked once; until the broker drops it, it has no rights */
			s->kicked = true;
			plugin.clients_changed = true;
			free(s->roles);
			s->roles = NULL;
			s->role_count = 0;
			p = &s->next;
		}
	}
	for(int i = 0; i < count; i++){
		if(expired[i]){
			mosquitto_log_printf(MOSQ_LOG_NOTICE, "token-auth: client %s disconnected, its token expired or lost its rights.", expired[i]);
			mosquitto_kick_client_by_clientid(expired[i], false);
			free(expired[i]);
		}
	}
	free(expired);
	if(plugin.clients_changed){
		plugin.clients_changed = false;
		publish_clients();
	}
	if(plugin.rejections_changed){
		plugin.rejections_changed = false;
		publish_rejections();
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
	mosquitto_plugin_set_info(identifier, PLUGIN_NAME, PLUGIN_VERSION);

	for(int i = 0; i < opt_count; i++){
		if(!strcmp(opts[i].key, "config_file")){
			plugin.path = strdup(opts[i].value);
		}
	}
	if(!plugin.path){
		mosquitto_log_printf(MOSQ_LOG_ERR, "token-auth: plugin_opt_config_file is missing.");
		return MOSQ_ERR_INVAL;
	}
	load_config(true);
	/* none yet; replaces what an earlier run left retained */
	plugin.clients_changed = true;
	plugin.rejections_changed = true;
	if(mosquitto_callback_register(identifier, MOSQ_EVT_BASIC_AUTH, on_basic_auth, NULL, NULL)
			|| mosquitto_callback_register(identifier, MOSQ_EVT_ACL_CHECK, on_acl_check, NULL, NULL)
			|| mosquitto_callback_register(identifier, MOSQ_EVT_DISCONNECT, on_disconnect, NULL, NULL)
			|| mosquitto_callback_register(identifier, MOSQ_EVT_CLIENT_OFFLINE, on_client_offline, NULL, NULL)
			|| mosquitto_callback_register(identifier, MOSQ_EVT_TICK, on_tick, NULL, NULL)){
		return MOSQ_ERR_UNKNOWN;
	}
	return MOSQ_ERR_SUCCESS;
}

int mosquitto_plugin_cleanup(void *userdata, struct mosquitto_opt *opts, int opt_count)
{
	(void)userdata;
	(void)opts;
	(void)opt_count;
	while(plugin.sessions){
		struct session *s = plugin.sessions;
		plugin.sessions = s->next;
		free_session(s);
	}
	for(int i = 0; i < plugin.rejection_count; i++){
		free_rejection(&plugin.rejections[i]);
	}
	free_config(plugin.config);
	free(plugin.path);
	memset(&plugin, 0, sizeof(plugin));
	return MOSQ_ERR_SUCCESS;
}
