# Builds the token login plugin against the headers of Mosquitto (MOSQUITTO_INCLUDE). The functions of the broker it calls
# come from the broker process at run time, OpenSSL and cJSON it links itself.
MOSQUITTO_INCLUDE ?= /usr/local/include/mosquitto-src
CFLAGS ?= -O2
CFLAGS += -std=gnu11 -fPIC -Wall -Wextra -Werror -Wno-unused-parameter -I$(MOSQUITTO_INCLUDE)
LDLIBS = -lcrypto -lcjson

mosquitto_token_auth.so: token_auth.c
	$(CC) $(CFLAGS) -shared $< -o $@ $(LDFLAGS) $(LDLIBS)

clean:
	rm -f mosquitto_token_auth.so

.PHONY: clean
