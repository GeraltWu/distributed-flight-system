# SC6103 Distributed Flight System

完整现场操作顺序和英文讲稿见 [演示脚本](docs/demo-script_ZH.md)；协议与实现提问复习见 [答辩指南](docs/project-defense-guide_ZH.md)。

English | [简体中文](README_ZH.md)

A UDP-based distributed flight information system implemented in Java. Network messages are encoded and decoded manually according to the [protocol specification](docs/protocol.md), without Java RMI, object serialization, or input/output stream classes for message encoding and decoding.
## Project structure

```text
distributed-flight-system/
├─ data/
│  └─ flights.tsv                    # Flight data loaded at server startup
└─ src/
   └─ flight/
      ├─ protocol/
      │  ├─ Protocol.java
      │  ├─ Message.java
      │  ├─ MessageCodec.java
      │  └─ ProtocolException.java
      ├─ server/
      │  ├─ ServerMain.java
      │  ├─ FlightServer.java
      │  ├─ FlightRequestHandler.java
      │  ├─ Flight.java
      │  ├─ FlightService.java
      │  ├─ InvocationMode.java
      │  ├─ LossSimulator.java
      │  ├─ MonitorRegistry.java
      │  └─ ReplyHistory.java
      └─ client/
         ├─ ClientMain.java
         └─ FlightClient.java
```

`out/` stores build outputs: Java `.class` files are in `out/flight/`, and C client executables are in `out/client_c/`. Git retains the `out/` and `out/client_c/` directory structure through `.gitkeep` files; build outputs are ignored by `.gitignore`.

## Directory responsibilities

- `flight.protocol`: protocol constants, message structures, and byte encoding/decoding shared by the client and server; no business state is stored here.
- `flight.server`: `FlightServer` handles UDP and invocation semantics; `FlightRequestHandler` handles business requests and replies; other classes maintain flight data, monitoring registrations, and reply history.
- `flight.client`: provides the console interface, generates request IDs, sends requests, receives replies, and displays results.
- `data/flights.tsv`: initial flight data, loaded into memory at server startup. Runtime changes are not written back to the file.

The server is the sole owner of flight data. Clients may validate user input, but the server validates all business parameters again.

## Current functionality

Six business operations are implemented: paginated route lookup (`ROUTE`), flight details (`DETAILS`), seat reservation (`RESERVE`), time-limited seat monitoring (`MONITOR`), idempotent fare setting (`SET_FARE`), and non-idempotent seat addition (`ADD_SEATS`). The protocol uses UTC epoch timestamps; client interfaces convert them to Singapore time and label them `SGT`. Clients wait one second per reply attempt and send a request at most four times. The server supports `at-least-once` and `at-most-once` modes. At-most-once mode uses reply history for deduplication. Clients acknowledge successful replies; error replies and successful replies with lost acknowledgements are cleaned up by a 60-second TTL. Successful `RESERVE` and `ADD_SEATS` operations trigger UDP callbacks to active monitors of that flight.

## Building and running

### Java

Build and run from the project root. The server reads data from the relative path `data/flights.tsv`.

Windows PowerShell:

```powershell
New-Item -ItemType Directory -Force out
javac --release 17 -encoding UTF-8 -d out (Get-ChildItem -Recurse src -Filter *.java).FullName
```

macOS / Linux:

```bash
mkdir -p out
find src -name '*.java' -print0 | xargs -0 javac --release 17 -encoding UTF-8 -d out
```

Start the server first:

```powershell
java -cp out flight.server.ServerMain 5000 at-most-once

java -cp out flight.server.ServerMain 5000 at-least-once
```

Then start the client in another terminal:

```powershell
java -cp out flight.client.ClientMain 127.0.0.1 5000

java -cp out flight.client.ClientMain 120.26.249.221 5000
```

The server's final argument selects invocation semantics; the client command stays the same. When running on separate computers, replace `127.0.0.1` with the server computer's IP address, `120.26.249.221`.

### C client

Download and install [MSYS2](https://www.msys2.org) using the default installation path, `C:\msys64`.

After installation, open MSYS2 UCRT64 from the Start menu and run:

```bash
pacman -Syu
```

If prompted to close the window, close it, reopen UCRT64, and run the command again.

Install the compiler:

```bash
pacman -S mingw-w64-ucrt-x86_64-gcc
```

Add `C:\msys64\ucrt64\bin` to the system `Path` environment variable (Settings → System → About → Advanced system settings → Environment Variables). This makes `gcc` available in regular PowerShell or Command Prompt windows.

Open a new terminal and check:

```powershell
gcc --version
```

Build and run from the project root, with outputs in `out/client_c/`:

```powershell
New-Item -ItemType Directory -Force out/client_c
gcc -std=c11 -Wall -Wextra -O2 -o out/client_c/flight_client.exe src/flight/client_c/client_main.c src/flight/client_c/flight_client.c src/flight/client_c/flight_protocol.c -lws2_32
.\out\client_c\flight_client.exe <server-host> <server-port>
```

On macOS / Linux, use the Makefile from the project root:

```bash
make -C src/flight/client_c
./out/client_c/flight_client 127.0.0.1 5000
```

Clean the C build output while retaining Java `.class` files:

```bash
make -C src/flight/client_c clean
```

## Packet-loss simulation

The server's optional third argument supports invocation-semantics experiments. For each new `ADD_SEATS` invocation, it drops the first request or first reply for that `(clientId, requestId)` pair. Later retransmissions of the same request are processed normally. Omitting this argument disables simulated loss:

```powershell
java -cp out flight.server.ServerMain 5000 at-most-once drop-request-once
java -cp out flight.server.ServerMain 5000 at-least-once drop-reply-once
java -cp out flight.server.ServerMain 5000 at-most-once drop-reply-once
```

Restarting the server restores the initial TSV data and clears the record of request IDs that have already triggered simulated loss.
