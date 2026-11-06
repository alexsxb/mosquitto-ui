# Builds the inspect plugin against the headers of Mosquitto (MOSQUITTO_INCLUDE). The functions of the broker it calls
# come from the broker process at run time, cJSON it links itself.
MOSQUITTO_INCLUDE ?= /usr/local/include/mosquitto-src
CFLAGS ?= -O2
CFLAGS += -std=gnu11 -fPIC -Wall -Wextra -Werror -Wno-unused-parameter -I$(MOSQUITTO_INCLUDE)
LDLIBS = -lcjson

mosquitto_inspect.so: inspect.c rules.c rules.h
	$(CC) $(CFLAGS) -shared inspect.c rules.c -o $@ $(LDFLAGS) $(LDLIBS)

clean:
	rm -f mosquitto_inspect.so

.PHONY: clean
