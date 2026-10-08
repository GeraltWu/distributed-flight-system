# Linux / macOS:  make
# Windows(MinGW): gcc -std=c11 -o flight_client.exe client_main.c flight_client.c flight_protocol.c -lws2_32
CC ?= gcc
CFLAGS ?= -std=c11 -Wall -Wextra -pedantic -O2
flight_client: client_main.c flight_client.c flight_protocol.c flight_client.h flight_protocol.h
	$(CC) $(CFLAGS) -o $@ client_main.c flight_client.c flight_protocol.c -lm
clean:
	rm -f flight_client
