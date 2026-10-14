# 现场演示完整流程与讲稿

本文档是一份可以现场照着执行的 runbook。核心流程约 12-15 分钟，使用三台电脑完成跨电脑 UDP 通信、callback 和调用语义实验。

## 1. 机器分工

| 电脑 | 程序 | 主要任务 |
| --- | --- | --- |
| S | Java server | 显示请求、回复、丢包、history 和 ACK 日志 |
| A | C client | 查询、预订、附加操作和丢包实验 |
| B | Java client | 登记 MONITOR 并接收 callback |

使用 C 客户端完成普通请求，可以同时展示 Java 服务端与 C 客户端遵守相同的字节协议。若 C 客户端临时出现环境问题，电脑 A 可以改用 Java 客户端，协议演示结论不变。

## 2. 演示前准备

### 2.1 三台电脑

1. 三台电脑连接同一可互通网络。
2. 电脑 S 执行 `ipconfig`，记录客户端可访问的 IPv4 地址，以下用 `SERVER_IP` 表示。
3. 电脑 S 的 Windows Firewall 允许 Java 使用 UDP 端口 `5000`。
4. 电脑 A、B 先运行一次 DETAILS，确认能够访问电脑 S。
5. 所有命令从项目根目录运行，确保服务端能够读取 `data/flights.tsv`。

### 2.2 编译

Java：

```powershell
New-Item -ItemType Directory -Force out
javac --release 17 -encoding UTF-8 -d out (Get-ChildItem -Recurse src -Filter *.java).FullName
```

C 客户端：

```powershell
New-Item -ItemType Directory -Force out/client_c
gcc -std=c11 -Wall -Wextra -O2 -o out/client_c/flight_client.exe src/flight/client_c/client_main.c src/flight/client_c/flight_client.c src/flight/client_c/flight_protocol.c -lws2_32
```

### 2.3 启动命令

电脑 S：

```powershell
java -cp out flight.server.ServerMain 5000 at-most-once
```

电脑 A：

```powershell
.\out\client_c\flight_client.exe SERVER_IP 5000
```

电脑 B：

```powershell
java -cp out flight.client.ClientMain SERVER_IP 5000
```

### 2.4 初始数据

每次重启服务端都会恢复：

```text
Flight 101
Route: Singapore -> Hong Kong
Departure: 2026-10-20 09:00:00 SGT
Fare: 350.00
Available seats: 10
```

所有调用语义实验开始前必须重启服务端，不能沿用上一组实验修改后的座位数。

## 3. 完整现场流程

### 3.1 开场与系统结构

**操作**

1. 展示电脑 S、A、B。
2. 在电脑 S 启动 `at-most-once`、无丢包的服务端。
3. 指出服务端启动日志中的 UDP 端口、调用语义和 loss mode。

**英文讲稿**

> Our system is a UDP-based distributed flight information system. The Java server owns the in-memory flight data. Computer A runs a C client, and computer B runs a Java client for monitoring. Both clients use the same manually defined byte protocol, so this also demonstrates cross-language interoperability.

> The client sends requests to a configured server address and port. The server learns each client's address and port from the received UDP datagram. No Java object serialization, RMI, or RPC framework is used.

### 3.2 ROUTE 和 DETAILS

**电脑 A 操作**

选择 `1. Find flights by route`：

```text
Source: Singapore
Destination: Hong Kong
```

预期：

```text
[RESULT] Matching flight IDs: [101, 102]
```

然后选择 `2. View flight details`，输入 `101`。

预期：

```text
Departure time: 2026-10-20 09:00:00 SGT
Fare: 350.00
Available seats: 10
```

**英文讲稿**

> ROUTE accepts two variable-length UTF-8 strings and returns all matching flight IDs in sorted, paginated results. DETAILS returns the UTC timestamp, a float fare, and the current seat count. The client converts the UTC timestamp to Singapore time for display.

> These results came from the Java server and were decoded by the C client using the same big-endian wire format.

### 3.3 RESERVE 和错误处理

**电脑 A 操作**

1. 选择 `3. Reserve seats`，输入航班 `101`、座位数 `2`，结果应为 `8`。
2. 再次选择 `3`，输入航班 `101`、座位数 `20`，应返回 `NO_SEATS`。
3. 查询 DETAILS，确认仍为 `8`，说明失败请求没有改变状态。

**英文讲稿**

> The server performs business validation even if the client has already checked the input. The first reservation succeeds and updates the authoritative server state. The second reservation fails because there are not enough seats, and the failed request leaves the state unchanged.

### 3.4 两个附加操作

**电脑 A 操作**

1. 选择 `5. Set fare`，将航班 `101` 设为 `375.0`，重复两次。
2. 两次结果都应为 `375.00`。
3. 选择 `6. Add seats`，为航班 `101` 增加 `1` 个座位，重复两次。
4. 从 8 个座位开始，两次结果依次为 `9` 和 `10`。

**英文讲稿**

> SET_FARE is our additional idempotent operation. It assigns an absolute value, so repeating SET_FARE with 375 produces the same final state.

> ADD_SEATS is our additional non-idempotent operation. Each execution adds to the current seat count, so repeating it changes the state again. We use this operation later to make duplicate execution visible.

### 3.5 MONITOR callback

**操作**

1. 停止并重启电脑 S 的普通 `at-most-once` 服务端，使航班 `101` 恢复为 10 个座位。
2. 电脑 B 选择 `4. Monitor seat availability`。
3. 输入航班 `101`，监控期限输入 `10` 秒。
4. 等电脑 B 显示注册成功后，电脑 A 为航班 `101` 预订 1 个座位。
5. 电脑 A 应显示剩余 `9` 个座位。
6. 电脑 B 应收到：

   ```text
   [EVENT] Flight 101 seats=9, sequence=1, time=... SGT
   ```

7. 等 10 秒监控结束，再由电脑 A 预订 1 个座位；电脑 B 不应收到新事件。

**英文讲稿**

> The monitoring client registers a flight and a monitor interval. The server records the source IP address and UDP port from the registration datagram; the client does not place a callback address in the request body.

> When another client reserves a seat, the server sends the latest absolute seat count to every unexpired registration. The event includes the registration identifiers and a sequence number, allowing the client to reject unrelated, duplicate, or old events.

> After the interval expires, the server removes the registration and no longer sends callbacks. Multiple clients can monitor concurrently even though each client remains single-threaded while waiting.

### 3.6 Request-loss 实验

每一行作为独立实验，开始前都停止并重启服务端，然后在电脑 A 重新启动客户端并执行一次 `ADD_SEATS(101, 1)`。

| 模式 | 电脑 S 启动命令 | 最终座位 |
| --- | --- | ---: |
| At-least-once | `java -cp out flight.server.ServerMain 5000 at-least-once drop-request-once` | 11 |
| At-most-once | `java -cp out flight.server.ServerMain 5000 at-most-once drop-request-once` | 11 |

电脑 S 应先显示：

```text
[LOSS] Simulated request loss: requestId=..., operation=ADD_SEATS(6)
```

约一秒后，电脑 A 重传相同请求并收到结果 `11`。

**英文讲稿**

> The first request is dropped before business execution. After one second, the client retransmits exactly the same bytes, including the same clientId and requestId. Because the dropped request never executed, the retry causes one state change in both modes. Therefore, both final results are 11.

### 3.7 Reply-loss：at-least-once

**电脑 S**

```powershell
java -cp out flight.server.ServerMain 5000 at-least-once drop-reply-once
```

**电脑 A**

执行一次 `ADD_SEATS(101, 1)`，预期最终结果为 `12`。

**英文讲稿**

> The first request executes and changes the state from 10 to 11, but its reply is dropped. The client cannot distinguish a lost request from a lost reply, so it retransmits the same request after the timeout.

> Under at-least-once semantics, every valid arrival is executed. The duplicate therefore changes the state again from 11 to 12. This is the incorrect result for one logical non-idempotent invocation.

### 3.8 Reply-loss：at-most-once

**电脑 S**

```powershell
java -cp out flight.server.ServerMain 5000 at-most-once drop-reply-once
```

**电脑 A**

执行一次 `ADD_SEATS(101, 1)`，预期最终结果为 `11`。

**电脑 S 关键日志**

```text
[LOSS] Simulated reply loss: requestId=..., operation=ADD_SEATS(6)
[HISTORY] Replaying cached reply for requestId=...
[ACK] Reply acknowledged for requestId=...
```

**英文讲稿**

> In at-most-once mode, the server executes a new request and stores its reply before attempting to send it. When the client retransmits, the server matches the same clientId, requestId, operation, and body.

> The server replays the cached reply without executing ADD_SEATS again, so the final state remains 11. After receiving and validating the successful reply, the client sends an ACK that allows the server to clear the cached reply early.

### 3.9 实验总结

在屏幕上展示：

| 丢失位置 | At-least-once | At-most-once |
| --- | ---: | ---: |
| 第一次 request | 11 | 11 |
| 第一次 reply | 12 | 11 |

**英文讲稿**

> Request loss is recovered by retransmission in both modes. Reply loss is different because the operation may already have executed. At-least-once can repeat a non-idempotent operation, whereas at-most-once uses duplicate detection and reply history to prevent the second state change.

> The loss simulator intentionally targets ADD_SEATS because its non-idempotent effect is deterministic and directly visible. The duplicate filter itself is operation-independent and runs before all business operations.

### 3.10 协议说明

打开 `docs/protocol.md`，展示 23 字节消息头和操作表。

**英文讲稿**

> Every datagram begins with a 23-byte fixed header containing magic, version, message type, operation, clientId, requestId, and body length. Multi-byte values use network byte order, and strings use a two-byte UTF-8 byte length followed by their bytes.

> Message type distinguishes request, reply, monitor event, and acknowledgement. Operation selects one of the six services. clientId and requestId are used both to match replies and to detect duplicate requests.

> The client retry algorithm is the same in both invocation modes. Only the server selects the mode because the semantic difference is whether the server re-executes a duplicate or replays its history entry.

### 3.11 结束总结

**英文讲稿**

> In summary, the project implements all required flight services over UDP, manual cross-language marshalling, timed callbacks to multiple clients, and both invocation semantics. The experiments show why reply history is necessary when a non-idempotent request may be retransmitted.

> The at-most-once guarantee is scoped to one server process lifetime because flight state and request history are stored in memory. UDP callbacks are best-effort, while sequence numbers help clients identify duplicate or old events.

## 4. 常见追问与回答

### 为什么只有服务端选择 invocation mode？

> The client performs the same timeout and retransmission algorithm in both modes. The difference is entirely in how the server handles a duplicate request, so only the server needs the mode argument.

### 为什么丢包只针对 ADD_SEATS？

> We use deterministic loss on ADD_SEATS because it is non-idempotent and its effect is directly visible. The at-most-once duplicate filter is outside the business handler and applies to every operation.

### ACK 丢失怎么办？

> ACK only performs early history cleanup. If it is lost, the cached reply remains protected by a 60-second TTL and can still be replayed for a duplicate request.

### 为什么不能收到 ACK 就删除所有 client history？

> The server clears the cached reply but retains the highest processed request ID. Otherwise, a delayed old non-idempotent request could be treated as new and execute again.

### Client 或 server 重启后还能保证 at-most-once 吗？

> The guarantee covers repeated datagrams with the same clientId and requestId during one server process lifetime. A client restart creates a new clientId, and a server restart clears in-memory history.

### Callback 会不会丢？

> Yes. Callback events use UDP and are best-effort. Each event carries the latest absolute seat count and a sequence number, so a later event can still provide the current state and duplicates can be rejected.

### 为什么不用线程？

> The assignment allows the monitoring client to block during its interval and assumes server requests are sufficiently separated. The implementation therefore uses one receive loop, while still supporting multiple monitor registrations.

### 为什么需要分页？

> Pagination keeps route results within the bounded UDP application datagram size. Each page is an independent request; it is not application-layer fragmentation.

## 5. 现场应急方案

### Callback 收不到

1. 确认电脑 B 已显示注册成功，再执行预订。
2. 确认使用同一航班 `101`，且 10 秒期限没有结束。
3. 检查电脑 S、B 的防火墙。
4. 暂时改用同一台电脑的两个客户端验证功能，同时说明跨电脑网络被防火墙阻断。

### 座位初始值不为 10

停止并重启服务端。不要仅重新启动客户端。

### Reply-loss 结果不是 12 和 11

1. 确认每组实验前重启了服务端。
2. 确认使用的是 `drop-reply-once`，不是 `drop-request-once`。
3. 确认操作是 `ADD_SEATS(101, 1)`。
4. 检查服务端日志是否出现 `[LOSS]` 和 `[HISTORY]`。

### 时间不足

优先保留：

1. ROUTE 和 DETAILS；
2. RESERVE 与错误处理；
3. MONITOR callback；
4. 两种模式的 reply-loss 对比；
5. 23 字节协议头和最终结论。

Request-loss 两组和监控到期后的第二次预订可以口头说明。

## 6. 最终检查清单

- [ ] 三台电脑使用同一版本代码和数据文件。
- [ ] Java、C 都已经编译，运行文件位于 `out/`。
- [ ] 已记录 `SERVER_IP`，三台电脑均能访问 UDP 端口 `5000`。
- [ ] 服务端日志窗口足够大，能看到 `[LOSS]`、`[HISTORY]` 和 `[ACK]`。
- [ ] 基础功能、callback 和四组丢包实验均至少预演一次。
- [ ] 每组状态实验前都会重启服务端。
- [ ] 汇报人熟悉本页的常见追问答案。
