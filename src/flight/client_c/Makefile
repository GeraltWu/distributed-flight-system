# Run make in this directory; outputs go to the project-root out/client_c/ directory.
CC ?= gcc
CFLAGS ?= -std=c11 -Wall -Wextra -pedantic -O2
OUT_DIR := ../../../out/client_c
ifeq ($(OS),Windows_NT)
TARGET := $(OUT_DIR)/flight_client.exe
LDLIBS := -lws2_32
else
TARGET := $(OUT_DIR)/flight_client
LDLIBS := -lm
endif

.PHONY: all clean
all: $(TARGET)

$(TARGET): client_main.c flight_client.c flight_protocol.c flight_client.h flight_protocol.h | $(OUT_DIR)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ client_main.c flight_client.c flight_protocol.c $(LDLIBS)

$(OUT_DIR):
	mkdir -p $@

clean:
	rm -f $(TARGET)
