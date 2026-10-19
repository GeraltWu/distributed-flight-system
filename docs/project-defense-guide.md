# Project Defense Study Guide

English | [简体中文](project-defense-guide_ZH.md)

This guide prepares the group for questions about protocol design, invocation semantics, callbacks, and implementation structure. A strong answer should follow: **conclusion -> implementation -> rationale -> guarantee boundary**.

## 1. One-minute overview

This project is a UDP-based distributed flight information system. A Java server owns the in-memory flight state, while Java and C clients invoke six operations through the same manually defined byte protocol. The protocol has request, reply, monitoring event, and reply-acknowledgement messages. Clients handle omission with a one-second timeout and at most four transmissions. The server supports at-least-once and at-most-once execution; at-most-once combines `clientId + requestId`, reply history, acknowledgements, and a TTL to prevent duplicate execution. MONITOR callbacks use the source endpoint observed by the server and monotonic deadlines.

Core summary:

> We built a manual request-reply protocol over UDP, then added retries, duplicate detection, reply history, and timed callbacks at the application layer.

## 2. System structure

### 2.1 Layers

| Layer | Responsibility |
| --- | --- |
| Console interface | Read user input and display results or errors |
| Client invocation layer | Build requests, allocate IDs, retry, match replies, send ACKs, and handle events |
| General protocol layer | Define headers, constants, `Message`, and byte encoding/decoding |
| Server communication layer | Receive UDP, select semantics, manage history, and send replies/callbacks |
| Business layer | Validate values, query or mutate flights, and build success/error replies |
| State components | Flight data, reply history, monitor registry, and loss records |

The protocol layer knows how to read and write fields but does not decide whether seats are sufficient. The business layer validates seats and fares but does not implement UDP retransmission.

### 2.2 Java classes

| Class | Responsibility |
| --- | --- |
| `Protocol` | Header offsets, sizes, message types, operations, status codes, and limits |
| `Message` | Immutable complete message; factories for request, reply, ACK, and event |
| `MessageCodec` | Manual header/body encoding; typed `BodyWriter/BodyReader` |
| `ClientMain` | Console menu and input handling |
| `FlightClient` | IDs, retry, reply matching, ACK, pagination, and MONITOR receive logic |
| `ServerMain` | Parse port, invocation mode, and loss mode; load data and start server |
| `FlightServer` | UDP loop, invocation semantics, history decisions, loss points, callbacks |
| `FlightRequestHandler` | Validate bodies, execute operations, and construct protocol replies |
| `FlightService` / `Flight` | Load TSV, find flights, and maintain fare/seat state |
| `ReplyHistory` | Highest request ID, request content, cached reply, and TTL |
| `MonitorRegistry` | Registrations, endpoints, expiry, and event sequence numbers |
| `LossSimulator` | Deterministically drop the first request/reply of each ADD_SEATS invocation |

### 2.3 C client modules

| File | Responsibility |
| --- | --- |
| `client_main.c` | Menu, input, SGT formatting, and output |
| `flight_client.c/.h` | Socket, invocation, timeout, retry, ACK, pagination, and MONITOR |
| `flight_protocol.c/.h` | Java-compatible constants, message representation, big-endian codec, UTF-8 validation |

The C client independently reads and writes protocol bytes; it does not exchange Java objects. This demonstrates language-neutral external data representation.

## 3. Normal request call path

### 3.1 Client

1. `ClientMain` calls a business method in `FlightClient`.
2. The method encodes parameters with `MessageCodec.BodyWriter`.
3. `invoke()` allocates a request ID and creates an immutable `Message`.
4. `sendWithRetry()` sends the encoded message, waits one second, and transmits at most four times.
5. `receiveNextServerMessage()` accepts only the configured server endpoint.
6. `isMatchingReply()` checks type, operation, client ID, and request ID.
7. The business method decodes the complete valid body, then sends a success ACK.

Retransmission reuses the already encoded request, so its IDs and body remain identical.

### 3.2 Server

1. `FlightServer.run()` receives datagrams in one loop.
2. `MessageCodec.decode()` validates the header, length, and structural fields.
3. ACKs go to `handleAcknowledgement()`; non-request messages are rejected.
4. Simulated request loss happens before execution and history.
5. `validateRequestBody()` verifies complete structural decoding.
6. At-most-once calls `ReplyHistory.classify()`; at-least-once executes directly.
7. `executeAndNotify()` calls `FlightRequestHandler.executeRequest()`.
8. Successful RESERVE/ADD_SEATS operations trigger `sendMonitorEvents()`.
9. At-most-once calls `remember()` before attempting to send the reply.
10. Simulated reply loss occurs after execution/history but before `socket.send()`.

Caching before sending is the key to handling reply loss without repeating a mutation.

## 4. Protocol design

### 4.1 Fixed 23-byte header

| Offset | Length | Field | Purpose |
| ---: | ---: | --- | --- |
| 0 | 2 | `magic` | ASCII `FI`, identifies the protocol |
| 2 | 1 | `version` | Current value 1 |
| 3 | 1 | `messageType` | 1 request, 2 reply, 3 event, 4 ACK |
| 4 | 1 | `operation` | Selects one of six business operations |
| 5 | 8 | `clientId` | Identifies one client process run |
| 13 | 8 | `requestId` | Identifies one logical invocation in that run |
| 21 | 2 | `bodyLength` | Exact body byte count |
| 23 | variable | `body` | Arguments, results, error, or event |

`messageType` describes the communication role; `operation` describes the business meaning. A RESERVE request and reply share an operation but have different types.

Magic rejects unrelated UDP traffic. Version prevents peers from interpreting bytes under incompatible layouts. Request ID alone is insufficient because multiple clients start at 1, so the server uses client ID and request ID together.

### 4.2 External data representation

- Multibyte integers use network byte order (big-endian).
- `float32` carries the raw IEEE 754 32-bit pattern.
- Strings use `u16 UTF-8 byte length + bytes`.
- Network timestamps use UTC epoch units; clients display SGT.
- Java stores unsigned `u64` bit patterns in `long` and uses unsigned comparisons where needed.

Explicit widths and byte order allow Java and C to interoperate without relying on native layout, endianness, object serialization, or compiler padding.

### 4.3 Datagram size and pagination

The application datagram limit is 548 bytes: a 23-byte header plus a 525-byte body. With ordinary IPv4 and UDP headers, the IP packet is 576 bytes. This reduces fragmentation risk in common IPv4 environments but cannot guarantee no fragmentation on every tunnel or smaller-MTU link.

ROUTE returns at most 100 IDs per page. Each page is an independent request/reply rather than application-layer fragmentation, so it can be retried, matched, and deduplicated independently.

### 4.4 Operations

| Operation | Request | Success result | Property |
| --- | --- | --- | --- |
| `ROUTE` | source, destination, offset | hasMore and IDs | Read-only, paginated |
| `DETAILS` | flightId | time, fare, seats | Read-only |
| `RESERVE` | flightId, seatCount | latest seats | Non-idempotent |
| `MONITOR` | flightId, intervalSeconds | seats, remaining time | Timed registration |
| `SET_FARE` | flightId, newFare | current fare | Idempotent assignment |
| `ADD_SEATS` | flightId, seatCount | latest seats | Non-idempotent increment |

Idempotent means repeated execution has the same final state as one execution. Repeating SET_FARE(375) remains 375; repeating ADD_SEATS(1) changes state again.

### 4.5 Status and invalid packets

Every reply body begins with `u8 status`. Zero means success. Errors include BAD_ARGUMENT, NOT_FOUND, NO_SEATS, BAD_PACKET, UNSUPPORTED, and STALE_REQUEST.

- Untrusted header (magic, version, or length failure): drop; matching fields are unsafe.
- Trusted header but malformed known-operation body: return BAD_PACKET.
- Unknown operation: return UNSUPPORTED.
- Invalid business value: return the relevant error without changing state.

## 5. Invocation semantics and history

### 5.1 What does a timeout prove?

Only that no matching reply arrived in time. It cannot distinguish a lost request, lost reply, slow server, or network delay, and it does not prove non-execution.

### 5.2 At-least-once

Every valid arrival executes. Retransmission recovers request loss, but reply loss can re-execute a non-idempotent mutation. It is not exactly-once.

### 5.3 At-most-once

The client has one outstanding request and increasing request IDs. `ReplyHistory.classify()` returns:

| Condition | Decision | Action |
| --- | --- | --- |
| No state or higher ID | `NEW_REQUEST` | Execute once, cache reply, then send |
| Same ID/content with cache | `REPLAY_CACHED` | Return cache without execution |
| Same ID but different operation/body | `CONFLICT` | BAD_ARGUMENT; preserve history |
| Lower ID, or same ID after cache cleanup | `STALE` | STALE_REQUEST; no execution |

Comparing the operation and body prevents an incorrectly reused ID from receiving an unrelated old result.

### 5.4 ACK, TTL, and high-water mark

- Clients ACK only fully validated successful replies.
- ACK removes the cached reply early.
- A lost ACK is harmless; the 60-second TTL eventually cleans the cache.
- Error replies are not acknowledged; TTL or a higher request replaces them.
- Cleanup retains the highest request ID, preventing delayed old mutations from executing.

Reply payload storage is therefore at most one entry per client, while the high-water map grows with distinct client IDs until server restart.

### 5.5 STALE_REQUEST

The server has seen the number but can no longer replay the original result. It refuses re-execution to protect non-idempotent operations. The client reports an unknown execution outcome, not definite failure.

### 5.6 Guarantee scope

At-most-once covers duplicate datagrams with the same client ID and request ID during one server process lifetime. It is not durable exactly-once: server restart clears history, client restart creates a new ID, and there is no persistent business transaction or order identity.

## 6. MONITOR and callbacks

### 6.1 Registration

The server records the actual source IP and port of the registration datagram. A registration key is `(clientId, requestId)` and stores flight ID, endpoint, monotonic expiry, and event sequence.

### 6.2 Monotonic clock

`System.nanoTime()` and the C monotonic clock measure elapsed time and are unaffected by wall-clock or NTP changes. An event carries a UTC millisecond timestamp on the wire, and the client converts it to `Asia/Singapore` for display. That wall-clock value is not used for expiry. Expiry checks use `expiresAtNanos - nowNanos <= 0`.

### 6.3 Events

Successful RESERVE and ADD_SEATS operations send events containing the latest absolute seat count. Absolute state means a later event can restore the current view after one event is lost. A per-registration sequence lets clients reject duplicates and older events.

Events are not acknowledged or retransmitted, so callbacks are best-effort.

### 6.4 Event before registration reply

Registration may have succeeded while its reply was lost, or UDP may reorder datagrams. The client enters monitor receive mode before sending and processes any event with the correct endpoint and registration IDs immediately.

### 6.5 Retransmitted MONITOR

- At-most-once replays the original registration without extending it. History retains the original expiry, preserves the first reply's seat count, and recalculates only remaining milliseconds.
- At-least-once re-executes registration and may extend expiry. The same key replaces the record, and event sequence continues.

MONITOR is the explicit history special case because an ordinary cached reply is static, while remaining time must decrease.

## 7. Design decisions

### Why UDP?

It is required by the assignment and exposes application-level handling of omission, duplicates, ordering, and callbacks.

### Why is the server authoritative?

All clients observe shared state, and all business validation is enforced in one place.

### Why validate on both sides?

Client validation improves user experience; server validation protects authoritative state from untrusted network input.

### Why does only the server select invocation mode?

The client performs identical timeout, retry, and ACK behavior in both modes. Only duplicate handling differs at the server.

### Why does loss simulation target only ADD_SEATS?

It is deterministic and non-idempotent, so duplicate execution is directly visible. The actual deduplication layer precedes the business handler and applies to all valid operations.

### How can a single-threaded server support multiple monitors?

It stores multiple registrations and iterates over them when sending events. Supporting multiple registrations does not require one thread per registration, and the assignment permits sequential request processing.

### Why does ADD_SEATS also trigger callbacks?

The monitored property is seat availability. Both RESERVE and ADD_SEATS change it, so sending the latest state is a consistent extension.

## 8. Common questions and short answers

### Q1: Did you implement exactly-once?

No. We implement process-scoped at-most-once execution for duplicate datagrams. Restarts and repeated business intent are outside the guarantee.

### Q2: Why not use an LRU cache alone?

An evicted old mutation could arrive again and execute. We may remove reply bodies, but we retain the high-water request ID and return STALE_REQUEST.

### Q3: Why cache error replies?

A duplicate invocation should receive the same result without re-running the handler. Error caches expire by TTL or are replaced by a higher request.

### Q4: Why do conflict and stale errors not replace history?

They are classification responses. Replacing history would destroy the valid cached request/result needed for a true duplicate.

### Q5: What if ACK is lost?

ACK is only an early-cleanup optimization. The reply remains protected until the 60-second TTL.

### Q6: Why 60 seconds?

It is much longer than the roughly four-second client retry window and is simple for a course project. It is not a universal network constant.

### Q7: Same request ID, different body?

If the highest reply is cached, return BAD_ARGUMENT without execution or replacement. If the cache is gone, return STALE_REQUEST.

### Q8: Why is Message immutable?

Defensive copies prevent body mutation after construction, which could break encoding, history comparison, and cached replies.

### Q9: Why preserve unknown operations during decoding?

If the u8 field is structurally valid, the server can return a matching UNSUPPORTED reply instead of silently dropping it.

### Q10: Why is invocation mode absent from the packet?

It is a server-provided guarantee, not a per-request claim. Startup configuration defines the run's semantics.

### Q11: What happens to a reply for another request ID?

The client ignores it. It validates source endpoint, type, operation, client ID, and request ID without restarting the wait timer.

### Q12: Why not rely on IP fragmentation?

Losing one fragment loses the full datagram. Bounded independent pages are easier to retry and deduplicate.

### Q13: Can ROUTE pagination change midway?

Routes cannot be mutated in this project, and IDs are sorted, so order is stable within a server run.

### Q14: Why derive the callback address from the request?

It is the endpoint actually visible to the server and avoids trusting a mismatched address in the body.

### Q15: If the MONITOR reply is lost, does registration exist?

Possibly. Timeout does not prove non-execution, and matching events are still processed. After four sends, the client reports unknown registration status.

### Q16: Why can monitor registration expire while history remains?

They serve different purposes: registration controls future events; history recognizes duplicate registration requests.

### Q17: Why preserve the original seat count in a replayed MONITOR reply?

At-most-once replay represents the same invocation result. Only the time-dependent remaining duration is rebuilt.

### Q18: How do you recover a lost callback?

The protocol does not retransmit events. A later absolute-state event can refresh the view; stronger delivery would need event ACKs or refresh.

### Q19: Why does the server use a receive timeout?

Without traffic, receive would block indefinitely. A roughly 200 ms timeout lets the single loop clean expired monitor and reply entries.

### Q20: How do C and Java agree on float?

Both transfer the big-endian IEEE 754 32-bit pattern; C also checks that `float` is four bytes.

### Q21: How do they handle u64 differently?

C uses `uint64_t`. Java keeps the same bits in `long` and uses unsigned comparison and formatting where required.

### Q22: Why limit error text to 512 bytes?

It bounds memory and ensures the complete error reply remains within the 548-byte application limit.

### Q23: Why no passenger/login data?

The assignment requires seat availability updates, not identities, orders, or payment. Client ID is a protocol instance ID, not a user ID.

### Q24: Does the single-threaded server have races?

Flight, history, and registry mutations occur sequentially in one receive loop, avoiding write races. Throughput is limited, matching the assignment assumption.

### Q25: Why do loss-injection positions matter?

Request loss must occur before execution/history. Reply loss must occur after execution and history storage. Those positions expose the semantic difference correctly.

## 9. Numbers to memorize

| Item | Value |
| --- | ---: |
| Fixed header | 23 bytes |
| Maximum body | 525 bytes |
| Maximum application datagram | 548 bytes |
| Message types | 4 |
| Operations | 6 |
| Reply timeout per attempt | 1 second |
| Maximum transmissions | 4 |
| Reply-history TTL | 60 seconds |
| Monitor interval | 1–600 seconds |
| Maximum ROUTE IDs per page | 100 |
| Server cleanup wake-up | about 200 ms |

## 10. Answer template

Use this structure for a design question:

1. **Conclusion:** state the rule directly.
2. **Implementation:** name the field, class, or method.
3. **Rationale:** identify the UDP or business problem it solves.
4. **Boundary:** state what it does not guarantee.

Example:

> We use clientId and requestId together to identify a logical invocation. FlightClient preserves both values on retransmission, and ReplyHistory compares the ID, operation, and body before replaying a result. This prevents duplicate execution after reply loss. The guarantee remains process-scoped because server restart clears the in-memory history.

