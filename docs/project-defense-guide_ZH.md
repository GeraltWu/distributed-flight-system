# 项目答辩复习指南

[English](project-defense-guide.md) | 简体中文

本文档用于准备老师对协议设计、调用语义、callback 和代码结构的提问。回答时建议遵循“**结论 -> 实现方法 -> 为什么 -> 保证边界**”的顺序。

## 1. 一分钟项目概述

本项目是一个基于 UDP 的分布式航班信息系统。Java 服务端保存内存中的航班状态；Java 客户端和 C 客户端通过同一套手动定义的字节协议调用六个操作。协议支持请求、回复、监控事件和回复确认四种消息，客户端使用 1 秒 timeout 和最多四次发送处理报文丢失。服务端可选择 at-least-once 或 at-most-once；后者使用 `clientId + requestId`、reply history、ACK 和 TTL 防止同一请求重复执行。MONITOR 使用服务端观察到的 UDP 来源地址进行 callback，并用单调时钟管理期限。

一句话总结设计重点：

> We built a manual request-reply protocol over UDP, then added retries, duplicate detection, reply history, and timed callbacks at the application layer.

## 2. 系统结构

### 2.1 分层

| 层 | 职责 |
| --- | --- |
| 控制台界面 | 读取用户输入、显示结果和错误 |
| 客户端调用层 | 构造请求、生成编号、超时重传、匹配回复、发送 ACK、处理事件 |
| 通用协议层 | 定义消息头、常量、`Message`、字节编码和解码 |
| 服务端通信层 | 接收 UDP、选择调用语义、处理 history、发送回复和 callback |
| 业务层 | 验证业务值、查询或修改航班、构造成功或错误回复 |
| 状态组件 | 航班数据、reply history、monitor registry、丢包记录 |

通用协议层与业务层分离。协议层知道如何读写整数、字符串和消息头，但不判断座位是否足够；业务层判断座位和票价，但不直接实现重传。

### 2.2 Java 类职责

| 类 | 主要职责 |
| --- | --- |
| `Protocol` | 头部偏移、长度、消息类型、操作号、状态码和大小上限 |
| `Message` | 一条不可变消息；提供 request、reply、ACK 和 event 工厂方法 |
| `MessageCodec` | 手动编解码消息头与 body；`BodyWriter/BodyReader` 读写各数据类型 |
| `ClientMain` | 控制台菜单和输入检查 |
| `FlightClient` | 请求编号、重传、回复匹配、ACK、分页和 MONITOR 接收逻辑 |
| `ServerMain` | 解析端口、调用语义和丢包模式，加载数据并启动服务端 |
| `FlightServer` | UDP receive loop、调用语义、history、丢包位置和 callback 发送 |
| `FlightRequestHandler` | 检查 body 结构、执行业务操作、构造协议回复 |
| `FlightService` / `Flight` | 加载 TSV、查找航班、维护票价和座位状态 |
| `ReplyHistory` | 至多一次模式的最高请求号、请求内容、缓存回复和 TTL |
| `MonitorRegistry` | 保存监控登记、到期时间、endpoint 和事件序号 |
| `LossSimulator` | 对每个 `ADD_SEATS` 请求键确定性丢第一次 request 或 reply |

### 2.3 C 客户端结构

| 文件 | 主要职责 |
| --- | --- |
| `client_main.c` | 菜单、输入、SGT 时间显示和结果输出 |
| `flight_client.c/.h` | socket、请求调用、timeout、重传、ACK、分页和 MONITOR |
| `flight_protocol.c/.h` | 与 Java 完全相同的常量、消息结构、大端编解码和 UTF-8 检查 |

C 客户端没有复制 Java 对象，而是独立按照协议文档读写字节，因此能够证明协议与编程语言无关。

## 3. 一次普通调用如何经过代码

### 3.1 客户端

1. `ClientMain` 调用 `FlightClient` 的业务方法。
2. 业务方法使用 `MessageCodec.BodyWriter` 编码参数。
3. `invoke()` 分配新的 `requestId`，并创建不可变 `Message`。
4. `sendWithRetry()` 编码整条消息并发送；每次等待 1 秒，最多发送四次。
5. `receiveNextServerMessage()` 只接受配置的服务端地址和端口。
6. `isMatchingReply()` 检查 `messageType`、`operation`、`clientId` 和 `requestId`。
7. 业务方法完整解码成功结果；只有完全读完合法 body 后才发送 ACK。

重发复用已经编码好的同一条 request，因此 ID 和 body 不会改变。

### 3.2 服务端

1. `FlightServer.run()` 在单线程 receive loop 中接收 UDP 数据报。
2. `MessageCodec.decode()` 检查消息头、长度和基本字段。
3. ACK 交给 `handleAcknowledgement()`；非 request 消息被拒绝。
4. request-loss 模拟发生在业务执行和 history 之前。
5. `validateRequestBody()` 先检查 body 结构是否能完整解码。
6. at-most-once 模式调用 `ReplyHistory.classify()`；at-least-once 直接执行。
7. `executeAndNotify()` 调用 `FlightRequestHandler.executeRequest()`。
8. 成功的 `RESERVE/ADD_SEATS` 触发 `sendMonitorEvents()`。
9. 至多一次模式先 `remember()` 保存结果，再尝试发送 reply。
10. reply-loss 模拟发生在执行及 history 保存之后、`socket.send()` 之前。

“先保存回复再发送”是 at-most-once 能处理 reply loss 的关键。

## 4. 协议设计

### 4.1 固定 23 字节消息头

| 偏移 | 长度 | 字段 | 作用 |
| ---: | ---: | --- | --- |
| 0 | 2 | `magic` | ASCII `FI`，识别本协议 |
| 2 | 1 | `version` | 当前为 1，支持版本检查 |
| 3 | 1 | `messageType` | 1 request、2 reply、3 event、4 ACK |
| 4 | 1 | `operation` | 选择六个业务操作之一 |
| 5 | 8 | `clientId` | 标识一次客户端运行实例 |
| 13 | 8 | `requestId` | 标识该实例中的一次逻辑调用 |
| 21 | 2 | `bodyLength` | body 的实际字节数 |
| 23 | 可变 | `body` | 操作参数、结果、错误或事件 |

#### 为什么同时需要 messageType 和 operation？

`messageType` 表示通信角色，例如 request、reply 或 event；`operation` 表示业务含义，例如 RESERVE 或 MONITOR。一个 RESERVE request 和它的 reply 使用同一个 operation，但 messageType 不同。

#### 为什么需要 magic 和 version？

`magic` 可以快速拒绝发到同一端口的其他 UDP 数据；`version` 防止双方按不同格式解释相同字节。它们各自解决协议识别和兼容性问题。

#### 为什么同时需要 clientId 和 requestId？

不同客户端都可能从 request 1 开始，所以 requestId 单独不唯一。`clientId` 标识运行实例，`requestId` 标识该实例内的调用；两者组合用于匹配回复和检测重传。

### 4.2 外部数据表示

- 多字节整数使用网络字节序，即 big-endian。
- `float32` 传输 IEEE 754 原始 32 位模式。
- 字符串使用 `u16 UTF-8 byte length + bytes`。
- 时间在网络上传 UTC epoch；客户端显示时转为 SGT。
- Java 的无符号 `u64` 使用 `long` 保存原始位模式，并用无符号比较方法处理。

选择 big-endian 和显式字段宽度，使 Java 与 C 不依赖本机字节序、对象布局或编译器 padding。

### 4.3 消息大小与分页

应用数据报上限是 548 字节：23 字节 header + 525 字节 body。加上常规 IPv4 头 20 字节和 UDP 头 8 字节后为 576 字节。这个大小降低普通 IPv4 环境发生分片的风险，但不能保证所有隧道或更小 MTU 网络都不分片。

ROUTE 每页最多返回 100 个 ID。每页是新的 request/reply，不是把一条大消息拆成多个 UDP fragment。这样每个页面可以独立重传、匹配和去重。

### 4.4 六个操作

| 操作 | 请求参数 | 成功结果 | 性质 |
| --- | --- | --- | --- |
| `ROUTE` | source、destination、offset | hasMore、ID 数组 | 只读、分页 |
| `DETAILS` | flightId | 时间、票价、座位 | 只读 |
| `RESERVE` | flightId、seatCount | 最新座位数 | 非幂等 |
| `MONITOR` | flightId、intervalSeconds | 当前座位和剩余期限 | 限时登记 |
| `SET_FARE` | flightId、newFare | 当前票价 | 幂等赋值 |
| `ADD_SEATS` | flightId、seatCount | 最新座位数 | 非幂等增量 |

幂等指同一操作重复执行后最终状态与执行一次相同。`SET_FARE(375)` 重复执行仍为 375；`ADD_SEATS(1)` 重复执行会继续增加。

### 4.5 状态码与非法报文

每个 reply body 以 `u8 status` 开头。0 为成功；错误包括 `BAD_ARGUMENT`、`NOT_FOUND`、`NO_SEATS`、`BAD_PACKET`、`UNSUPPORTED` 和 `STALE_REQUEST`。

- 头部不可信，例如 magic、version 或长度错误：直接丢弃，因为无法安全相信匹配字段。
- 头部可信但已知操作的 body 结构错误：返回 `BAD_PACKET`。
- 操作号未知：返回 `UNSUPPORTED`。
- 业务值不合法：返回相应业务错误，不修改状态。

## 5. 调用语义与 history

### 5.1 Timeout 能说明什么？

Timeout 只表示客户端在期限内没有收到匹配 reply。它不能区分 request 丢失、reply 丢失、服务端慢或网络延迟，也不能证明操作没有执行。

### 5.2 At-least-once

每个到达的合法 request 都执行。request loss 可通过重传恢复；reply loss 会让非幂等操作再次执行。因此它不是“恰好一次”。

### 5.3 At-most-once

客户端同一时间只有一个未完成请求，并使用递增 requestId。`ReplyHistory.classify()` 有四种结果：

| 条件 | 决策 | 行为 |
| --- | --- | --- |
| 没有 client 状态，或 ID 更高 | `NEW_REQUEST` | 执行一次，保存 reply 后发送 |
| ID 相同、内容相同、缓存存在 | `REPLAY_CACHED` | 不执行，返回缓存 |
| ID 相同但 operation/body 不同 | `CONFLICT` | 返回 `BAD_ARGUMENT`，不覆盖 history |
| ID 更低，或相同 ID 的缓存已清除 | `STALE` | 返回 `STALE_REQUEST`，不执行 |

比较 body 很重要：如果只比较 ID，客户端错误复用同一 ID 调用不同操作时，可能收到不相关的旧结果。

### 5.4 ACK、TTL 和 high-water mark

- 客户端只 ACK 完整验证后的成功 reply。
- ACK 让服务端提前删除 reply body，减少内存占用。
- ACK 丢失不会影响已获得的结果；60 秒 TTL 负责最终清理。
- 错误 reply 不 ACK，由 TTL 或更高编号请求清理。
- 删除 reply 后仍保留最高 requestId。否则延迟到达的旧 RESERVE 可能再次执行。

因此缓存 reply 最多每个 client 一条，但最高编号表会随不同 clientId 数量增长，直到服务端重启。

### 5.5 STALE_REQUEST 的含义

它表示服务端已见过这个编号，但现在无法重放原结果。服务端拒绝再次执行以保护非幂等操作。客户端只能报告“执行结果未知”，不能说操作失败。

### 5.6 保证边界

at-most-once 只保证同一个服务端进程中，具有相同 `clientId + requestId` 的重复数据报最多执行一次。它不是 durable exactly-once：

- 服务端重启会丢失 history。
- 客户端重启生成新 clientId。
- 项目没有持久事务、用户身份或订单 ID。

## 6. MONITOR 与 callback

### 6.1 登记

服务端使用 request 数据报的真实源 IP 和源端口作为 callback endpoint，不接受 body 中声明的地址。这避免地址不一致，也满足服务端预先不知道客户端地址的要求。

登记以 `(clientId, requestId)` 为键，保存 flightId、endpoint、单调时钟到期值和 eventSequence。

### 6.2 为什么使用单调时钟？

`System.nanoTime()` 和 C 的 monotonic clock 只用于计算经过时间，不受用户调整系统时间或 NTP 校时影响。事件在报文中携带 UTC 毫秒时间戳，客户端把它转换为 `Asia/Singapore` 时区后显示；这个 wall-clock 时间不参与期限判断。

到期判断使用 `expiresAtNanos - nowNanos <= 0`，而不是依赖 nanoTime 的任意起点。

### 6.3 事件

成功的 RESERVE 或 ADD_SEATS 会发送座位事件。事件携带最新绝对座位数，而不是变化量，因此丢失一条事件不会让下一条增量基于错误状态。事件还携带每个登记独立递增的 sequence，客户端丢弃重复或更旧的序号。

事件本身不 ACK、不重传，所以 callback 是 best-effort。

### 6.4 为什么 event 可能先于注册 reply？

服务端可能已经登记成功但 reply 丢失，之后另一个客户端修改了座位；也可能 UDP 乱序。客户端进入 MONITOR 模式后同时接收 reply 和 event，只要 endpoint 和登记 ID 匹配，就立即处理 event。

### 6.5 MONITOR 重传的两种语义

- at-most-once：重放登记结果，不重新登记、不延长期限；重新计算 `remainingMillis`。history 保存原 expiry，并保留第一次 reply 的 availableSeats。
- at-least-once：重新执行登记并可能延长期限，但相同键覆盖旧登记，不产生多个副本；eventSequence 继续递增。

MONITOR 是 history 的明确特例，因为普通缓存 reply 可以原样重放，而监控 reply 中的剩余时间必须随时间减少。

## 7. 关键设计选择及理由

### 为什么使用 UDP？

这是作业要求，也让我们必须在应用层明确处理 timeout、duplicate、ordering 和 callback。UDP 不提供可靠交付、顺序和重复抑制。

### 为什么服务端是数据唯一所有者？

所有客户端看到同一状态，业务校验集中在服务端，客户端不能通过修改本地数据绕过规则。

### 为什么客户端和服务端都检查输入？

客户端检查改善用户体验；服务端检查保证安全和一致性，因为网络数据不能被信任。

### 为什么只有服务端选择 invocation mode？

客户端在两种模式下都执行完全相同的 timeout、重传和 ACK。差异只在服务端收到 duplicate 后是重新执行还是重放 history。

### 为什么丢包模拟只针对 ADD_SEATS？

它是确定性的实验工具。ADD_SEATS 非幂等且状态变化直接可见，最适合展示 reply loss 导致的重复执行。真正的 deduplication 位于业务 handler 之前，对所有合法操作生效。

### 为什么单线程也能支持多个 monitor？

“支持多个 monitor”表示 registry 可以同时保存多个登记并逐一 callback，不要求为每个登记创建线程。作业允许监控客户端阻塞，并假设请求在时间上充分分离。

### 为什么 ADD_SEATS 也触发 callback？

监控的是 seat availability。RESERVE 和 ADD_SEATS 都改变这个属性，因此发送最新状态是一致的扩展；作业要求的 RESERVE callback 已完整满足。

## 8. 老师常见提问与简答

### Q1：你们是否实现了 exactly-once？

没有。我们实现的是进程生命周期内、针对重复数据报的 at-most-once execution。服务端重启、客户端新身份和业务意图重复不在保证范围内。

### Q2：为什么 history 不能只用 LRU 随便淘汰？

被淘汰的旧非幂等 request 再次到达时可能被当成新请求执行。我们的 reply 可以由 ACK/TTL 清理，但最高 requestId 仍保留，用于返回 STALE_REQUEST。

### Q3：为什么错误 reply 也进入 history？

同一 request 重传时应该得到一致结果，并且不应再次运行 handler。错误 reply 不 ACK，由 TTL 或下一个请求清理。

### Q4：为什么冲突和 STALE 错误不写入 history？

它们是 history 分类过程产生的临时响应。写入会覆盖原来正确的请求和 reply，使真正的 duplicate 无法重放。

### Q5：ACK 本身丢失怎么办？

ACK 只优化清理。reply 仍保留到 60 秒 TTL；同一 request 重发时仍可安全重放。

### Q6：为什么 TTL 是 60 秒？

客户端最大正常重试窗口约 4 秒，60 秒明显更长，能覆盖延迟重传并保持实现简单。它是课程项目参数，不是通用网络常数。

### Q7：同一个 requestId 使用不同 body 会怎样？

如果该最高编号的缓存仍在，返回 BAD_ARGUMENT，不执行也不覆盖 history；若缓存已清理，则返回 STALE_REQUEST。

### Q8：为什么 Message 要不可变并复制 byte array？

防止构造后 body 被外部修改，否则编码、history 比较和缓存 reply 可能不一致。

### Q9：为什么 unknown operation 不在 Message 解码时直接拒绝？

只要 operation 能放入 u8，就保留它，让服务端返回可匹配的 UNSUPPORTED reply，而不是无声丢弃可信请求。

### Q10：为什么不把 mode 放在报文中？

调用语义是服务端提供的保证，不应由单个 request 自行声明。服务端启动参数和日志确定本次运行的模式。

### Q11：客户端收到其他 requestId 的 reply 怎么办？

忽略。客户端同时检查来源 endpoint、messageType、operation、clientId 和 requestId，且无关报文不会重新开始等待计时。

### Q12：为什么不使用 UDP 分片？

IP 分片由网络层决定且任一片丢失会导致整条数据报不可用。应用层采用有界消息和独立分页请求，更容易重传和去重。

### Q13：ROUTE 分页期间结果会变化吗？

本项目没有修改航线集合的操作，服务端按 ID 排序，所以同一服务端运行内分页顺序稳定。

### Q14：callback 地址为什么不由客户端直接填写？

服务端可以从 UDP request 的来源得到实际可回复 endpoint，避免客户端填错或声明与来源不一致的地址。

### Q15：如果 MONITOR reply 丢了，登记存在吗？

可能存在。Timeout 不能证明未执行；期间合法 event 仍应处理。四次失败后客户端报告 registration status unknown。

### Q16：监控过期后 history 为什么还可能存在？

monitor registry 和 reply history 目的不同。登记过期停止 event，但 history 仍可能需要识别 registration request 的 duplicate。

### Q17：为什么重放 MONITOR reply 时不返回当前座位数？

至多一次重放代表原调用的同一结果，因此保留首次 reply 的 availableSeats，只更新随时间变化的 remainingMillis。

### Q18：事件丢失后怎样恢复？

当前协议不补发；callback 是 best-effort。事件传绝对座位数，因此后续事件仍能恢复当前视图。生产系统可加入 ACK、状态刷新或可靠传输。

### Q19：为什么服务端定期 setSoTimeout？

没有请求时 `receive()` 会一直阻塞。约 200 ms timeout 让单线程循环定期清除到期 monitor 和 reply cache。

### Q20：Java 与 C 的 float 如何保持一致？

双方都把 32 位 IEEE 754 位模式按 big-endian 传输；C 还在编译期检查 `sizeof(float)==4`。

### Q21：C 和 Java 如何处理 u64 差异？

C 使用 `uint64_t`；Java `long` 保存相同 64 位位模式，需要排序时使用 `Long.compareUnsigned`，打印时使用无符号转换。

### Q22：为什么错误 body 有 512 字节限制？

它限制内存和报文大小，并保证 header、status、长度和说明合计不超过 548 字节。

### Q23：为什么不保存乘客或登录信息？

作业只要求更新剩余座位，没有要求身份、订单或付款。`clientId` 是协议实例标识，不是用户身份。

### Q24：单线程会不会有 race condition？

当前服务端在一个 receive loop 顺序修改 flight、history 和 registry，避免了并发写入 race。代价是吞吐量有限，这符合课程给出的简化假设。

### Q25：你们的丢包位置为什么重要？

request loss 必须发生在执行和 history 之前；reply loss 必须发生在执行并保存 history 之后。否则无法正确展示两种语义的差别。

## 9. 必须熟记的数字

| 项目 | 数值 |
| --- | ---: |
| 固定 header | 23 bytes |
| 最大 body | 525 bytes |
| 最大应用数据报 | 548 bytes |
| message types | 4 |
| operations | 6 |
| 每次 reply timeout | 1 second |
| 最大发送次数 | 4 |
| reply history TTL | 60 seconds |
| monitor interval | 1–600 seconds |
| ROUTE 每页最多 ID | 100 |
| 服务端清理唤醒间隔 | about 200 ms |

## 10. 回答问题的模板

老师问设计问题时，可以使用以下结构：

1. **结论**：直接说采用什么规则。
2. **实现**：指出字段、类或方法。
3. **原因**：说明它解决的 UDP 或业务问题。
4. **边界**：说明它没有保证什么。

例子：

> We use clientId and requestId together to identify a logical invocation. FlightClient preserves both values on retransmission, and ReplyHistory compares the ID, operation, and body before replaying a result. This prevents duplicate execution after reply loss. The guarantee remains process-scoped because server restart clears the in-memory history.

