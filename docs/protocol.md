# Flight Information System UDP Protocol

English | [简体中文](protocol_ZH.md)

Each user operation is represented by a numbered request and a reply with the same number. Monitoring updates are sent as event messages. Clients and the server exchange byte arrays exclusively through UDP datagrams. This document defines the complete protocol followed by the current implementation, including retransmission, reply acknowledgement, at-most-once deduplication, and time-limited monitoring.

The protocol has two layers. The **general request–reply layer** handles byte encoding, headers, request IDs, retries, and deduplication. The **flight business layer** defines operation IDs, parameters, results, business errors, and monitoring events. The general layer does not validate fares or seat counts; the business layer does not directly handle UDP retransmission. `MONITOR` is an explicit exception to cached-reply handling: history stores the original expiry time alongside the original reply and rebuilds the remaining duration using this operation's reply format, allowing `remainingMillis` to be updated on replay.

## 1. General encoding conventions

- Each UDP datagram has an application payload of at most **548 bytes**: a fixed **23-byte** header and a body of at most **525 bytes**. Each request, reply, monitoring event, or reply acknowledgement occupies exactly one UDP datagram. Adding a standard 20-byte IPv4 header and an 8-byte UDP header gives 576 bytes. IP fragmentation can still occur if the IPv4 header contains options, the link MTU is smaller, or additional encapsulation is present.
- All multibyte integers use network byte order (most significant byte first). `u8/u16/u32/u64` are unsigned integers; `i32/i64` are signed two's-complement integers. Java must convert unsigned fields to sufficiently wide types when reading them.
- `float32` uses the raw four-byte IEEE 754 bit pattern. `departureUtcSeconds` is UTC seconds since the Unix epoch; `eventUtcMillis` is UTC milliseconds since the Unix epoch. Strings use `u16 byte count + UTF-8 bytes`; length is measured in **bytes**. Section 3 defines business-value ranges.

This encoding is the project's **external data representation**: senders marshal manually, and receivers unmarshal manually in the same field order. As with the compact structured representations discussed in the course, bodies do not repeat a type tag for every field. Both sides use the operation table in this document to determine types and order. Strings are length-prefixed, and integers use big-endian order. This protocol does not implement CORBA CDR alignment, byte-order flags, or its full specification, and does not use Java object serialization.

## 2. Fixed header

| Offset | Length | Field | Meaning |
| ---: | ---: | --- | --- |
| 0 | 2 | magic | ASCII `FI`, or `0x46 0x49` |
| 2 | 1 | version | Fixed at `1` |
| 3 | 1 | messageType | `1` request, `2` reply, `3` monitoring event, `4` reply acknowledgement |
| 4 | 1 | operation | Operation ID from the table below; events use the `MONITOR` ID |
| 5 | 8 | clientId | Random `u64` generated at each client startup and unchanged during that run; identifies a running instance, not a user account |
| 13 | 8 | requestId | Client-generated `u64` increasing from 1; unchanged on retransmission |
| 21 | 2 | bodyLength | Number of body bytes in this datagram |
| 23 | `bodyLength` | body | Operation-specific message body |

Receivers must verify that the actual datagram length is exactly `23 + bodyLength` and reject incorrect versions, oversized messages, and invalid fields. Clients accept only messages from the configured server address and port whose identifiers match the current request or monitoring registration. Invocation semantics are selected by server startup arguments and are not repeated in network messages. Clients use the same timeout/retry behavior in both modes.

The four message types populate headers as follows:

- Request: `messageType=1`; `operation` identifies the invoked operation; the client supplies `clientId/requestId`.
- Reply: `messageType=2`; copy `operation`, `clientId`, and `requestId` unchanged from the corresponding request. This applies to both success and error replies.
- Monitoring event: `messageType=3`, `operation=MONITOR`; `clientId` identifies the monitor, and `requestId` identifies its `MONITOR` registration request.
- Reply acknowledgement: clients acknowledge only successful replies with `status=0`. Use `messageType=4`, copy `operation`, `clientId`, and `requestId` unchanged from the received reply, and leave the body empty. An at-most-once server uses the ACK to clear the corresponding successful reply history early; an at-least-once server ignores it. No server reply is required. Error replies are not acknowledged; their cache entries are cleared by a 60-second TTL or a subsequent higher-numbered request.

If a datagram is shorter than the full header, or its header structure is invalid—including `magic`, `version`, `messageType`, or disagreement between `bodyLength` and actual length—the server logs and discards it because its matching fields cannot be trusted safely. A nonempty acknowledgement body is also invalid. If the header is complete and valid, so `operation/clientId/requestId` can be obtained reliably, but the request body cannot be decoded for that operation, return `BAD_PACKET`; unknown operations return `UNSUPPORTED`. Clients log and ignore messages with invalid header structure, the wrong source, or identifiers that do not match the current request.

## 3. Flight business interface and bodies

Operation IDs are: `1 ROUTE`, `2 DETAILS`, `3 RESERVE`, `4 MONITOR`, `5 SET_FARE`, and `6 ADD_SEATS`.

Every **reply** body starts with `u8 status`. `status=0` means success and is followed by the result in the table below. A nonzero `status` is the error code and may be followed only by `u16 explanation byte count + UTF-8 explanation`, without success-result fields. Error codes are `1 BAD_ARGUMENT`, `2 NOT_FOUND`, `3 NO_SEATS`, `4 BAD_PACKET`, `5 UNSUPPORTED`, and `6 STALE_REQUEST`. Explanations are limited to 512 UTF-8 bytes. Before displaying an explanation, clients must verify that its length is at most 512, exactly matches the remaining bytes, and contains valid UTF-8. Otherwise, treat the whole reply as malformed and ignore it.

| Operation | Request body | Successful reply contents after `status` | Effect |
| --- | --- | --- | --- |
| `ROUTE` | `string source, string destination, u32 offset` | `u8 hasMore, u16 count, i32[count] flightIds` | Paginated matching flight IDs; initial `offset=0`; no matches returns `NOT_FOUND` |
| `DETAILS` | `i32 flightId` | `i64 departureUtcSeconds, float32 fare, i32 availableSeats` | Query details; missing flight returns `NOT_FOUND` |
| `RESERVE` | `i32 flightId, i32 seatCount` | `i32 availableSeats` | Decrease seats; insufficient seats returns `NO_SEATS` |
| `MONITOR` | `i32 flightId, u32 intervalSeconds` | `i32 availableSeats, u32 remainingMillis` | Register the current UDP source address and port; missing flight returns `NOT_FOUND` |
| `SET_FARE` | `i32 flightId, float32 newFare` | `float32 currentFare` | Set the fare to the specified value; **idempotent** |
| `ADD_SEATS` | `i32 flightId, i32 seatCount` | `i32 availableSeats` | Add the specified number of seats; **non-idempotent** |

Business validation rules: flight IDs must be positive `i32` values; available seats must be nonnegative `i32` values. Reservation and seat-addition counts must be positive `i32` values, with overflow checks during addition. Fares must be finite, nonnegative `float32` values. Monitoring periods range from 1 to 600 seconds. Source and destination are each limited to 1–255 UTF-8 bytes. Before lookup, trim surrounding whitespace, normalize to Unicode NFC, and compare case-insensitively using `Locale.ROOT`; retain original names for display. Protocol timestamps use UTC; clients convert them to Singapore time (SGT, UTC+8) and explicitly display the timezone.

This assignment has no user login, passenger details, or order records. `RESERVE` only decreases the available seats in server memory and returns confirmation. `clientId` identifies requests and retransmissions within one client run; it does not represent seat ownership. If a user restarts the client and submits the same reservation again, it is a new request. The server cannot determine whether it comes from the same person or represents the same intent as the pre-restart reservation.

`ROUTE` returns at most **100 flight IDs** per page, giving a maximum message length of `23 + 1 + 1 + 2 + 100×4 = 427` bytes. The longest `ROUTE` request contains two 255-byte place names and has length `23 + (2+255)×2 + 4 = 541` bytes. The longest error reply has length `23 + 1 + 2 + 512 = 538` bytes. All remain within 548 bytes. The server sorts matches by ascending flight ID, and `offset` is a zero-based result position. Since the project has no operations to add, remove, or change routes, pagination order stays stable within one server run. `hasMore` must be 0 or 1. Clients begin at `offset=0` and validate each page's `count`, message length, and ordering. If `hasMore=1`, request `offset + count` with a **new request ID**, continuing until `hasMore=0`, then display all collected IDs. When `hasMore=1`, `count` must not be zero. An out-of-range offset returns `BAD_ARGUMENT`. Each page is an independent request and reply, not application-layer fragmentation of a UDP datagram.

After a successful `RESERVE`, the server sends one `messageType=3, operation=MONITOR` event to each active monitor of that flight. The event header's `clientId` is the monitor's ID, and `requestId` is its registration request ID. The event body is `i32 flightId, i32 availableSeats, u32 eventSequence, i64 eventUtcMillis`. Sequence numbers increase from 1 within each registration, allowing clients to ignore duplicate or out-of-order old events. `ADD_SEATS` also changes seat availability and sends the same update events. Fare changes do not send seat events.

## 4. Retries, deduplication, and monitoring periods

### 4.1 General request–reply rules

After sending a request, the client waits **one second** for a reply per attempt and sends at most four times. Failure to receive the matching reply is treated as a timeout. This duration is an empirical value for the demonstration environment. A timeout only triggers retransmission; it does not prove that a request or reply was lost. **Retransmissions use exactly the same `clientId`, `requestId`, and request body.** After exhausting attempts, the client reports a timeout. The operation may have executed zero, one, or multiple times depending on server mode and which requests arrived; a timeout alone does not prove non-execution. After fully validating a matching successful reply, the client sends one acknowledgement. Error replies are not acknowledged. An ACK send failure does not change the result already obtained.

- **At-least-once mode:** the server executes every valid request it receives, even if its request ID has appeared before. A retransmission following reply loss may repeat `RESERVE` or `ADD_SEATS` mutations.
- **At-most-once mode:** within one run, a client permits only one outstanding request and sends requests with increasing IDs; each page of a paginated query follows this rule. For each `clientId`, the server stores the highest processed request ID and the operation, original body, and reply of the **most recent** identifiable request. Both business successes and business errors are cached. After receiving a request with a valid header and body, the server applies these rules in order:
  1. `requestId` exceeds the highest ID: execute once, update the highest ID, cache the request and reply, and then send the reply.
  2. `requestId` equals the highest ID and the cache still exists: if `operation` and the original body match exactly, return the cached result without re-execution; otherwise return `BAD_ARGUMENT`.
  3. `requestId` is below the highest ID: return `STALE_REQUEST` without re-execution, even if the content happens to match an old request.
  4. `requestId` equals the highest ID but its cache has been cleared: return `STALE_REQUEST` without re-execution.
- `BAD_ARGUMENT` caused by the conflict in rule 2 and `STALE_REQUEST` from rules 3 and 4 are sent only as error replies to the current datagram. They do not enter or replace existing history.
- **Cleanup and protection against old requests:** successful reply acknowledgements immediately remove the corresponding reply cache. Error replies and successful replies whose ACKs are lost are protected by a 60-second TTL, longer than the client's maximum retry window of approximately four seconds. A new higher-numbered request can also replace the old reply immediately because the client will no longer retransmit the old request. Removing a reply cache retains that `clientId`'s highest processed ID. A delayed old reservation receives `STALE_REQUEST` instead of deducting seats again. Reply history therefore stores at most one reply per client. Client restarts generate new `clientId` values; server restarts clear in-memory state, so the at-most-once guarantee applies only within one server run. The highest-ID table still grows with the number of distinct client instances but does not retain old reply bodies.
- On `STALE_REQUEST`, the client reports: “The server has already seen this request, but its cached result is unavailable; the execution outcome is unknown.” It must not interpret this as proof that the operation failed.
- Select the mode at server startup; startup logs print it. Clients need no mode configuration and use the same retransmission and acknowledgement rules in both modes.

### 4.2 Monitoring periods

#### 4.2.1 Initial registration

The client sends `MONITOR(flightId, intervalSeconds)`, with a period of 1–600 seconds. After validating the parameters, the server:

1. Uses the datagram's source IP and port as the callback address; it does not accept another address in the body.
2. Records `registeredAtNanos` using a monotonic clock and calculates `expiresAtNanos = registeredAtNanos + intervalSeconds × 1_000_000_000`. Wall-clock adjustments do not change the period.
3. Identifies the registration by `(clientId, requestId)` and stores `flightId`, callback address, expiry time, and an event sequence starting at 0.
4. Returns current available seats and `remainingMillis`. The first successful reply calculates the actual remaining duration rather than assuming it equals the requested number of seconds.

Calculate `remainingMillis` as:

```text
remainingMillis = max(0, ceil((expiresAtNanos - nowNanos) / 1_000_000))
```

Rounding up prevents the client from ending early due to nanosecond-to-millisecond truncation. The value does not exceed 600,000 and fits in the protocol's `u32`.

#### 4.2.2 Retransmitted registration requests

A retransmitted `MONITOR` request retains exactly the same `clientId`, `requestId`, and body. The server handles it according to its mode:

- `at-most-once`: the same ID and content match history; do not re-register or extend the original period. History stores the original expiry time. Recalculate `remainingMillis` using the current monotonic clock, then encode a successful reply.
- `at-least-once`: execute registration again for every received request. `MonitorRegistry` overwrites the registration keyed by `(clientId, requestId)`, so duplicate registrations do not accumulate. However, expiry is recalculated from this execution, potentially extending the period. The existing `eventSequence` continues increasing instead of resetting.
- The same `clientId/requestId` with a different operation or body is handled by the ID rules in Section 4.1 and does not modify an existing registration.

In `at-most-once` mode, if the original registration has expired by the time a retry arrives, the server still returns success with `remainingMillis=0`. It does not restore the registration, extend its period, or resend missed events. Expiry does not immediately remove reply history: while history exists, the server can recognize the retry and return this result. MONITOR history stores its own `expiresAtNanos` independently of a potentially cleaned-up `MonitorRegistry`. Rebuilt replies retain the first successful reply's `availableSeats` and recalculate only `remainingMillis`; they do not query current seat availability again.

#### 4.2.3 Seat-change events

After successful `RESERVE` or `ADD_SEATS`, the server processes the flight's monitoring registrations:

1. Read the monotonic clock first; do not send events for expired registrations.
2. Increment `eventSequence` by 1 for each active registration.
3. Populate the header and body as specified in Section 3 and send one UDP datagram to the IP and port stored at registration.

Multiple clients can monitor the same flight simultaneously; the server sends separately to each active registration. Events do not require acknowledgements, and the server does not retransmit them. UDP events may be lost, duplicated, or reordered. Clients accept only events matching their monitoring registration and ignore duplicate or old events whose `eventSequence` is no greater than the highest sequence received. `eventUtcMillis` is only for displaying event time; monitoring expiry is always determined by a monotonic clock.

#### 4.2.4 Client waiting behavior

Before its first `MONITOR` send, the client enters monitoring receive mode and handles messages on the same UDP socket according to `messageType`:

- A `messageType=2` message matching the current registration request is a registration reply.
- A `messageType=3, operation=MONITOR` message with matching `clientId/requestId` is a seat event for that registration.

Seat events may arrive before the registration reply. One case is that the server registers successfully but its reply is lost in the UDP network. The registration remains active, so a later seat change by another client still triggers an event. Another case is that the server sends the reply before the event, but UDP does not guarantee delivery order, and the event reaches the client first.

The client therefore cannot interpret “no registration reply received yet” as “the server has not registered the monitor.” If the source is correct and `messageType`, `operation`, `clientId`, and `requestId` match the current monitor, display the event immediately and update the highest received `eventSequence`. There is no need to wait for the reply or buffer the event separately.

After receiving a successful reply, the client uses its local monotonic clock to calculate `localEndNanos = nowNanos + remainingMillis × 1_000_000` and receives events until that time. `remainingMillis=0` ends monitoring immediately. If no registration reply arrives after four sends, the client stops waiting and reports “registration status unknown.” This does not prove that registration failed. The server registration may remain active and continue sending events to that socket. When the client starts another request, these residual events are ignored because their `requestId` does not match.

#### 4.2.5 Expiry checks and cleanup

The server checks expiry before sending each event, so expired registrations cannot receive events even if they have not yet been physically removed from the collection. All expiry checks based on `System.nanoTime()` use the difference `expiresAtNanos - nowNanos <= 0`, avoiding dependence on its arbitrary origin and handling counter wraparound correctly. The single-threaded implementation uses a socket receive timeout of approximately 200 milliseconds. After receiving a datagram or experiencing a receive timeout, it scans and removes expired registrations and clears expired reply history. Registrations and reply history are cleaned up separately: removing an expired monitor does not prematurely remove history needed for at-most-once semantics.

## 5. Java implementation boundaries

Communication uses `DatagramSocket`, `DatagramPacket`, and `byte[]`. Implement offset-based reads and writes for `u16/u32/u64/i32/i64/float32/string` manually. `Float.floatToIntBits` and `Float.intBitsToFloat` only convert numeric bit patterns. Do not use RMI, RPC, CORBA, object serialization, `DataInputStream`, `DataOutputStream`, or other input/output stream classes to encode or decode network messages. Console input/output is used only for menus and logs.
