# Project Demo Guide

English | [简体中文](demo-guide_ZH.md)

## 1. Assignment requirements and demonstration evidence

| Requirement | Evidence to show | Status |
| --- | --- | --- |
| UDP communication between client and server | Run the server and clients on separate computers; perform queries and updates | Implemented |
| Find flight IDs by route | Query `Singapore -> Hong Kong`, returning `[101, 102]` | Implemented |
| Query flight details | Query flight `101`, showing Singapore time, fare, and available seats | Implemented |
| Reserve seats | Seats decrease after success; insufficient availability returns an error | Implemented |
| Monitor seat changes for a specified period | One client monitors while another reserves; the monitor receives a callback | Implemented |
| One additional idempotent operation | Repeat `SET_FARE(375.0)` for the same flight; fare stays `375.0` | Implemented |
| One additional non-idempotent operation | Execute `ADD_SEATS(1)` twice; seats increase twice | Implemented |
| `at-least-once` and `at-most-once` semantics | Compare final `ADD_SEATS` results after dropping the first reply | Implemented |
| Simulated request and reply loss | Run separate request-loss and reply-loss experiments | Implemented |
| Manual marshalling/unmarshalling | Explain the fixed header, variable-length strings, and big-endian encoding | Implemented |

## 2. Machines and network setup

For the assignment's callback demonstration, prepare three computers on the same network:

| Computer | Role | Program |
| --- | --- | --- |
| S | Server | `ServerMain` |
| A | Regular client | Queries, reservations, and additional operations |
| B | Monitoring client | Registers `MONITOR` and waits for callbacks |

Before the demonstration:

1. Install Java 17 on all three computers.
2. Run `ipconfig` on the server computer and record an IPv4 address reachable by the clients.
3. Ensure Windows Firewall permits the selected UDP port, such as `5000`.
4. Run the server from the project root so it can read `data/flights.tsv`.
5. Replace `SERVER_IP` in client commands with the server computer's actual address.

Build command:

```powershell
New-Item -ItemType Directory -Force out
javac --release 17 -encoding UTF-8 -d out (Get-ChildItem -Recurse src -Filter *.java).FullName
```

Server command:

```powershell
java -cp out flight.server.ServerMain 5000 at-most-once
```

Client command:

```powershell
java -cp out flight.client.ClientMain SERVER_IP 5000
```

## 3. Resetting demonstration data

The server reloads `data/flights.tsv` at every startup. Restart it before each experiment to restore the same initial state.

This guide uses flight `101`:

- Route: `Singapore -> Hong Kong`
- Initial fare: `350.00`
- Initial available seats: `10`

After each restart, query `DETAILS(101)` to confirm the initial data before beginning the experiment.

## 4. Basic business operations

Use an `at-most-once` server for the basic demonstration to avoid repeated business effects caused by network fluctuations.

### 4.1 Route lookup

Select `1. Find flights by route`:

```text
Source: Singapore
Destination: Hong Kong
```

Expected result:

```text
[RESULT] Matching flight IDs: [101, 102]
```

Explain that results are sorted by ascending flight ID. The protocol supports up to 100 IDs per page, and the client requests subsequent pages automatically.

### 4.2 Flight details

Select `2. View flight details` and enter flight ID `101`.

Expected output includes:

```text
[RESULT] Flight details:
  Departure time: 2026-10-20 09:00:00 SGT
  Fare: 350.00
  Available seats: 10
```

UTC is the time basis for the protocol and data file. The client converts timestamps to Singapore time and explicitly labels them `SGT`.

### 4.3 Seat reservation

Select `3. Reserve seats` and reserve two seats on flight `101`.

Expected result:

```text
[RESULT] Reservation completed. Available seats: 8
```

Then attempt to reserve 20 seats. Expect `NO_SEATS`; querying details again should still show eight seats.

### 4.4 Idempotent operation

Select `5. Set fare` and set flight `101`'s fare to `375.0` twice.

Both results should be:

```text
[RESULT] Fare updated: 375.00
```

Explain that repeating the same `SET_FARE` does not change the state further, so the operation is idempotent.

### 4.5 Non-idempotent operation

Select `6. Add seats` and add one seat to flight `101` twice.

Starting from eight seats, the results should be nine and then ten. Explain that repeated execution changes state again, so `ADD_SEATS` is non-idempotent.

## 5. Callback demonstration

1. Restart the server to restore flight `101` to ten available seats.
2. Start a client on computer B, select `4. Monitor seat availability`, and register a 30-second monitor for flight `101`.
3. Computer B enters the waiting state; do not enter other requests before the period ends.
4. Start another client on computer A and reserve one seat on flight `101`.
5. Computer A shows a successful reservation with nine seats remaining.
6. Computer B receives a server-initiated callback showing the flight ID, updated seat count of nine, event sequence number, and event time labeled `SGT`, for example:

   ```text
   [EVENT] Flight 101 seats=9, sequence=1, time=2026-10-08 14:00:23 SGT
   ```
7. Wait for the 30-second period to end and confirm that the monitoring client leaves the waiting state.
8. Make another reservation and confirm that the expired registration receives no further callbacks.

Explain that the server obtains the callback IP and port from the source of the `MONITOR` request datagram. The client does not supply its own callback address in the message body.

## 6. Comparing invocation semantics

For each new `ADD_SEATS` invocation, the server's optional third argument deterministically drops the first request or first reply for that `(clientId, requestId)` pair. Retransmissions of the same invocation are not dropped again. The next `ADD_SEATS` uses a new `requestId`, so its own first request or reply is dropped again. Client commands stay the same.

Use `ADD_SEATS(101, 1)` because it is non-idempotent. Restart the server before each experiment to restore the initial seat count to ten.

### 6.1 Request-loss experiment

Drop the first request of this `ADD_SEATS` invocation:

```powershell
java -cp out flight.server.ServerMain 5000 at-least-once drop-request-once
java -cp out flight.server.ServerMain 5000 at-most-once drop-request-once
```

1. The first request never reaches business processing.
2. After approximately one second, the client retransmits with the same `clientId/requestId`.
3. The retransmitted request executes once.
4. The final seat count is 11.

Run separately with `at-least-once` and `at-most-once`; both should produce 11. This demonstrates how timeouts and retransmissions handle request loss.

### 6.2 Reply loss with `at-least-once`

Start an `at-least-once` server and drop this invocation's first reply:

```powershell
java -cp out flight.server.ServerMain 5000 at-least-once drop-reply-once
```

1. The first request executes, changing seats from ten to 11.
2. The first reply is dropped.
3. The client times out and retransmits the same request.
4. The server executes the operation again, changing seats from 11 to 12.
5. Query details; the final result is 12.

Show server logs demonstrating that both requests have the same `clientId/requestId` and both execute.

### 6.3 Reply loss with `at-most-once`

Restart the server in `at-most-once` mode and again drop this invocation's first reply:

```powershell
java -cp out flight.server.ServerMain 5000 at-most-once drop-reply-once
```

1. The first request executes, changing seats from ten to 11; the server caches the reply.
2. The first reply is dropped.
3. The client times out and retransmits the same request.
4. The server finds the reply history entry and returns the cached reply without executing `ADD_SEATS` again.
5. The client acknowledges the reply, and the server clears the cache entry.
6. Query details; the final result remains 11.

Show these server log messages:

```text
[HISTORY] Replaying cached reply for requestId=...
[ACK] Reply acknowledged for requestId=...
```

### 6.4 Comparison

| Loss location | Final seats with `at-least-once` | Final seats with `at-most-once` |
| --- | ---: | ---: |
| First request | 11 | 11 |
| First reply | 12 | 11 |

At-least-once semantics allow client retries, but non-idempotent operations may execute multiple times. At-most-once semantics use request IDs and reply history to prevent repeated execution.

## 7. Explaining the protocol design

Open the [protocol specification](protocol.md) and briefly explain:

1. The UDP application message has a fixed 23-byte header.
2. `messageType` distinguishes requests, replies, monitoring events, and reply acknowledgements.
3. `operation` distinguishes the six business operations.
4. `clientId/requestId` match replies and identify duplicate requests.
5. Project code manually converts integers, floating-point values, and strings into bytes, without Java object serialization or network I/O stream classes for encoding/decoding.
6. Variable-length strings use `u16 UTF-8 byte count + UTF-8 bytes`.
7. Pagination consists of independent requests, not application-layer UDP fragmentation.

## 8. Suggested demonstration order

Use this order to reduce repeated switching between programs:

1. Show the three computers and UDP startup commands.
2. Demonstrate `ROUTE`, `DETAILS`, `RESERVE`, and error handling.
3. Demonstrate `SET_FARE` idempotency and `ADD_SEATS` non-idempotency.
4. Demonstrate `MONITOR` callbacks using two clients.
5. Restart the server and complete the request-loss experiment.
6. Complete the reply-loss experiment in each invocation mode.
7. Show [the protocol specification](protocol.md) and key server logs, then summarize the results.

## 9. Pre-demonstration checklist

- [ ] All three computers can run Java 17.
- [ ] Clients can query `DETAILS` using the server IP and UDP port.
- [ ] Windows Firewall permits UDP datagrams and callbacks.
- [ ] Initial data in `data/flights.tsv` matches this guide.
- [ ] All six business operations are implemented.
- [ ] No events are sent after a `MONITOR` registration expires.
- [ ] Request-loss and reply-loss modes drop only the first corresponding datagram of each `ADD_SEATS` invocation.
- [ ] The server is restarted between experiments to restore data.
- [ ] Server logs show the mode, request IDs, operations, dropped packets, cached replay, and ACKs.
- [ ] The report's experiment table matches the live results.

## 10. Troubleshooting

### The client keeps retransmitting

Check the server IP, UDP port, firewall, and whether the server was started from the project root.

### The initial seat count is incorrect

Stop and restart the server. It reloads `data/flights.tsv` and does not retain changes from the previous run.

### Both invocation modes produce the same result

Confirm that the first reply is being dropped and that the experiment uses a non-idempotent operation such as `ADD_SEATS`. When the first request is dropped, both modes execute the operation only once.

### Callbacks do not reach the monitoring client

Confirm that the monitoring period has not ended and the registration uses the correct flight ID. Check that the monitoring computer's firewall allows its UDP socket to receive data.
