# 作业演示指南

## 1. 作业要求与演示证据

| 作业要求 | 演示时提供的证据 | 当前状态 |
| --- | --- | --- |
| 客户端和服务端通过 UDP 通信 | 不同电脑运行服务端和客户端，完成查询与修改 | 已实现 |
| 按航线查询航班号 | 查询 `Singapore -> Hong Kong`，返回 `[101, 102]` | 已实现 |
| 查询航班详情 | 查询航班 `101`，显示新加坡时间、票价和剩余座位 | 已实现 |
| 预订座位 | 预订成功后座位减少；余票不足时返回错误 | 已实现 |
| 在指定期限内监控座位变化 | 一个客户端监控，另一个客户端预订，监控端收到 callback | 已实现 |
| 一个幂等附加操作 | 对同一航班重复执行 `SET_FARE(375.0)`，票价始终为 `375.0` | 已实现 |
| 一个非幂等附加操作 | 连续执行两次 `ADD_SEATS(1)`，座位累计增加两次 | 已实现 |
| `at-least-once` 和 `at-most-once` | 丢失第一次回复后比较 `ADD_SEATS` 的最终结果 | 已实现 |
| 模拟 request 和 reply 丢失 | 分别进行 request-loss 和 reply-loss 实验 | 已实现 |
| 手动 marshalling/unmarshalling | 说明固定消息头、变长字符串和大端字节编码 | 已实现 |

## 2. 机器与网络安排

按照作业的 callback 演示要求，建议准备三台处于同一网络的电脑：

| 电脑 | 角色 | 运行程序 |
| --- | --- | --- |
| S | 服务端 | `ServerMain` |
| A | 普通客户端 | 查询、预订和附加操作 |
| B | 监控客户端 | 登记 `MONITOR` 并等待 callback |

演示前完成以下准备：

1. 三台电脑安装 Java 17。
2. 在服务端电脑运行 `ipconfig`，记录可由客户端访问的 IPv4 地址。
3. 确认 Windows 防火墙允许所选 UDP 端口，例如 `5000`。
4. 服务端从项目根目录运行，以便读取 `data/flights.tsv`。
5. 客户端命令中的 `SERVER_IP` 替换为服务端电脑的实际地址。

编译命令：

```powershell
New-Item -ItemType Directory -Force out
javac --release 17 -encoding UTF-8 -d out (Get-ChildItem -Recurse src -Filter *.java).FullName
```

服务端启动命令：

```powershell
java -cp out flight.server.ServerMain 5000 at-most-once
```

客户端启动命令：

```powershell
java -cp out flight.client.ClientMain SERVER_IP 5000
```

## 3. 重置演示数据

服务端每次启动都会重新读取 `data/flights.tsv`。演示不同实验前重启服务端，可以恢复相同的初始状态。

本文档使用航班 `101`：

- 航线：`Singapore -> Hong Kong`
- 初始票价：`350.00`
- 初始剩余座位：`10`

每次重启后先查询一次 `DETAILS(101)`，确认起始数据正确，再开始实验。

## 4. 基础业务功能演示

基础功能使用 `at-most-once` 服务端，避免网络波动影响业务结果。

### 4.1 航线查询

在客户端选择 `1. Find flights by route`：

```text
Source: Singapore
Destination: Hong Kong
```

预期结果：

```text
[RESULT] Matching flight IDs: [101, 102]
```

说明：结果按航班号升序排列；协议支持每页最多 100 个航班号，客户端自动请求后续页面。

### 4.2 航班详情

选择 `2. View flight details`，输入航班号 `101`。

预期结果包括：

```text
[RESULT] Flight details:
  Departure time: 2026-10-20 09:00:00 SGT
  Fare: 350.00
  Available seats: 10
```

UTC 是协议传输和数据文件使用的时间基准；客户端显示时转换为新加坡时间，并明确标注 `SGT`。

### 4.3 座位预订

选择 `3. Reserve seats`，为航班 `101` 预订 2 个座位。

预期结果：

```text
[RESULT] Reservation completed. Available seats: 8
```

随后尝试预订 20 个座位，预期收到 `NO_SEATS`，再次查询详情仍为 8 个座位。

### 4.4 幂等操作

选择 `5. Set fare`，连续两次把航班 `101` 的票价设置为 `375.0`。

两次结果都应为：

```text
[RESULT] Fare updated: 375.00
```

说明：重复执行相同的 `SET_FARE` 不会继续改变状态，因此该操作是幂等的。

### 4.5 非幂等操作

选择 `6. Add seats`，连续两次为航班 `101` 增加 1 个座位。

如果开始时有 8 个座位，两次结果应依次为 9 和 10。说明：重复执行会继续改变状态，因此 `ADD_SEATS` 是非幂等操作。

## 5. Callback 演示

1. 重启服务端，恢复航班 `101` 的座位数为 10。
2. 在电脑 B 启动客户端，选择 `4. Monitor seat availability`，为航班 `101` 登记 30 秒监控。
3. 电脑 B 进入等待状态，在期限结束前不输入其他请求。
4. 在电脑 A 启动另一个客户端，为航班 `101` 预订 1 个座位。
5. 电脑 A 显示预订成功，剩余座位为 9。
6. 电脑 B 收到服务端主动发送的 callback，显示航班号、最新座位数 9、事件序号和以 `SGT` 标注的事件时间，例如：

   ```text
   [EVENT] Flight 101 seats=9, sequence=1, time=2026-10-08 14:00:23 SGT
   ```
7. 等待 30 秒期限结束，确认监控客户端退出等待状态。
8. 再进行一次预订，确认已经过期的监控登记不再收到 callback。

演示时说明：服务端从 `MONITOR` 请求的数据报来源取得客户端 IP 和端口；客户端不在消息体中自行填写 callback 地址。

## 6. 两种 invocation semantics 对比

服务端的可选第三个参数会针对每个新的 `ADD_SEATS` 调用，确定性地丢弃该 `(clientId, requestId)` 的第一次请求或第一次回复。同一调用的重传不会再次被丢弃；下一次 `ADD_SEATS` 使用新的 `requestId`，因此又会丢弃它自己的第一次请求或回复。客户端命令不变。

选择 `ADD_SEATS(101, 1)` 作为实验操作，因为它是非幂等操作。每组实验开始前都重启服务端，使初始座位数恢复为 10。

### 6.1 Request-loss 实验

让程序丢弃本次 `ADD_SEATS` 调用的第一次请求：

```powershell
java -cp out flight.server.ServerMain 5000 at-least-once drop-request-once
java -cp out flight.server.ServerMain 5000 at-most-once drop-request-once
```

1. 第一次请求没有进入业务处理。
2. 客户端等待约 1 秒后，以相同 `clientId/requestId` 重传。
3. 重传请求被执行一次。
4. 最终座位数为 11。

分别使用 `at-least-once` 和 `at-most-once` 运行，预期结果都为 11。该实验说明 timeout 和重传可以处理请求丢失。

### 6.2 Reply-loss 与 `at-least-once`

启动 `at-least-once` 服务端，并让程序丢弃本次 `ADD_SEATS` 调用的第一次回复：

```powershell
java -cp out flight.server.ServerMain 5000 at-least-once drop-reply-once
```

1. 第一次请求已经执行，座位数从 10 变为 11。
2. 第一次回复被丢弃。
3. 客户端超时后重发相同请求。
4. 服务端再次执行业务操作，座位数从 11 变为 12。
5. 查询详情，最终结果为 12。

需要展示的服务端日志：两个请求具有相同的 `clientId/requestId`，并且都被执行。

### 6.3 Reply-loss 与 `at-most-once`

重启服务端，改用 `at-most-once`，再次丢弃本次 `ADD_SEATS` 调用的第一次回复：

```powershell
java -cp out flight.server.ServerMain 5000 at-most-once drop-reply-once
```

1. 第一次请求执行，座位数从 10 变为 11，服务端保存回复。
2. 第一次回复被丢弃。
3. 客户端超时后重发相同请求。
4. 服务端命中 reply history，不再执行 `ADD_SEATS`，而是返回缓存回复。
5. 客户端发送回复确认，服务端清理缓存。
6. 查询详情，最终结果仍为 11。

需要展示的服务端日志：

```text
[HISTORY] Replaying cached reply for requestId=...
[ACK] Reply acknowledged for requestId=...
```

### 6.4 对比结论

| 丢失位置 | `at-least-once` 最终座位 | `at-most-once` 最终座位 |
| --- | ---: | ---: |
| 第一次 request | 11 | 11 |
| 第一次 reply | 12 | 11 |

结论：至少一次语义可以保证客户端重试，但非幂等操作可能执行多次；至多一次语义利用请求编号和 reply history 阻止重复执行。

## 7. 协议设计说明

演示时简要打开 `docs/protocol.md`，说明以下几点即可：

1. UDP 应用层报文包含固定 23 字节消息头。
2. `messageType` 区分请求、回复、监控事件和回复确认。
3. `operation` 区分六个业务操作。
4. `clientId/requestId` 用于匹配回复和识别重复请求。
5. 整数、浮点数和字符串由项目代码手动转换为字节，不使用 Java 对象序列化或网络 I/O stream 类进行编解码。
6. 变长字符串使用 `u16 UTF-8字节数 + UTF-8字节`。
7. 分页是多个独立请求，不是 UDP 应用层分片。

## 8. 建议演示顺序

建议控制在以下顺序，避免不断切换程序：

1. 展示三台电脑和 UDP 启动命令。
2. 演示 `ROUTE`、`DETAILS`、`RESERVE` 和错误处理。
3. 演示 `SET_FARE` 的幂等性和 `ADD_SEATS` 的非幂等性。
4. 使用两个客户端演示 `MONITOR` callback。
5. 重启服务端，完成 request-loss 实验。
6. 分别完成两种模式下的 reply-loss 实验。
7. 展示 `protocol.md` 和关键服务端日志，总结实验结果。

## 9. 演示前检查清单

- [ ] 三台电脑均能运行 Java 17。
- [ ] 客户端能通过服务端 IP 和 UDP 端口完成 `DETAILS` 查询。
- [ ] Windows 防火墙不会阻止 UDP 数据报和 callback。
- [ ] `data/flights.tsv` 中的初始数据与本文档一致。
- [ ] 六个业务操作均已实现。
- [ ] `MONITOR` 到期后不再发送事件。
- [ ] request-loss 和 reply-loss 对每个 `ADD_SEATS` 调用都只丢第一次对应报文。
- [ ] 两种实验之间会重启服务端以恢复数据。
- [ ] 服务端日志能显示模式、请求编号、操作、丢包、缓存重放和 ACK。
- [ ] 报告中的实验表格与现场结果一致。

## 10. 常见问题

### 客户端一直重传

检查服务端 IP、UDP 端口、防火墙和服务端是否从项目根目录启动。

### 实验开始时座位数不正确

结束服务端并重新启动。服务端会重新读取 `data/flights.tsv`，不会保存上一次运行的修改。

### 两种语义的结果没有差异

确认丢失的是第一次 reply，并且实验使用 `ADD_SEATS` 等非幂等操作。丢失第一次 request 时，两种模式都只会执行一次。

### Callback 没有到达监控客户端

确认监控期限尚未结束、登记使用正确航班号，并检查监控客户端电脑的防火墙是否允许该 UDP socket 接收数据。
