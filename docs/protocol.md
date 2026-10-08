# 航班信息系统 UDP 协议

把每次用户操作表示为一条带编号的请求，把结果表示为同编号的回复；监控更新则用事件报文发送。客户端和服务端都只通过 UDP 数据报交换字节数组。本文件定义当前实现遵守的完整协议规则，包括重传、回复确认、至多一次去重和限时监控。

协议分为两层：**通用请求应答层**负责字节编码、消息头、请求编号、重传和去重；**航班业务层**定义操作编号、参数、结果、业务错误与监控事件。通用层不判断票价或座位是否有效，业务层不直接处理 UDP 重传。`MONITOR` 是缓存回复的一个明确特例：为了在重发时更新 `remainingMillis`，history 除原回复外还保存原到期时间，并按该操作的回复格式重建剩余时长。

## 1. 通用编码约定

- 每个 UDP 数据报的应用载荷最多 **548 字节**；固定消息头为 **23 字节**，消息体最多 **525 字节**。每条请求、回复、监控事件或回复确认恰好占用一个 UDP 数据报。548 字节加标准 IPv4 头 20 字节及 UDP 头 8 字节，合计 576 字节；若 IPv4 头含选项、链路 MTU 更小或存在额外封装，仍可能发生 IP 分片。
- 所有多字节整数按网络字节序（高位字节在前）编码。`u8/u16/u32/u64` 为无符号整数，`i32/i64` 为二进制补码有符号整数。Java 读取无符号字段时须转换到足够大的类型。
- `float32` 使用 IEEE 754 的原始 4 字节位模式。`departureUtcSeconds` 使用自 Unix epoch 起的 UTC 秒数，`eventUtcMillis` 使用自 Unix epoch 起的 UTC 毫秒数。字符串格式为 `u16 字节数 + UTF-8 字节`，长度按**字节**计算。各字段的业务取值范围由第 3 节规定。

这套编码是本项目的 **external data representation**：发送方手动 marshalling，接收方按相同字段顺序手动 unmarshalling。与课件讨论的紧凑结构化表示一样，消息体不为每个字段重复写类型标签，双方依靠本文件中的操作表确定类型和顺序；字符串使用长度前缀，整数统一使用大端序。本协议没有实现 CORBA CDR 的对齐、字节序标志或完整规范，也没有使用 Java 对象序列化。

## 2. 固定消息头

| 偏移 | 长度 | 字段 | 含义 |
| ---: | ---: | --- | --- |
| 0 | 2 | magic | ASCII `FI`，即 `0x46 0x49` |
| 2 | 1 | version | 固定为 `1` |
| 3 | 1 | messageType | `1` 请求、`2` 回复、`3` 监控事件、`4` 回复确认 |
| 4 | 1 | operation | 下表中的操作编号；事件使用 `MONITOR` 的编号 |
| 5 | 8 | clientId | 客户端每次启动生成的随机 `u64`，本次运行保持不变；它标识运行实例，不是用户账号 |
| 13 | 8 | requestId | 客户端从 1 开始递增的 `u64`；重发时保持不变 |
| 21 | 2 | bodyLength | 本数据报的消息体字节数 |
| 23 | `bodyLength` | body | 按操作定义的消息体 |

接收方必须检查实际数据报长度恰好等于 `23 + bodyLength`，并拒绝错误版本、超长报文和非法字段。客户端只接收来自所配置服务端地址及端口、且标识与当前请求或监控注册相符的报文。调用语义由服务端启动参数选择，不在网络报文中重复携带。客户端在两种模式下采用相同的超时重传行为。

四类报文的消息头按以下规则填写：

- 请求：`messageType=1`，`operation` 为客户端调用的操作，`clientId/requestId` 由客户端填写。
- 回复：`messageType=2`，`operation`、`clientId` 和 `requestId` 原样复制对应请求。成功与错误回复均遵守此规则。
- 监控事件：`messageType=3`，`operation=MONITOR`，`clientId` 为监控者的 ID，`requestId` 为对应的 `MONITOR` 注册请求编号。
- 回复确认：客户端只确认 `status=0` 的成功回复。确认报文使用 `messageType=4`，`operation`、`clientId` 和 `requestId` 原样复制已收到的回复，消息体为空。至多一次服务端用它提前清理对应的成功 reply history，至少一次服务端直接忽略，不需要服务端回复。错误回复不发送确认，由 60 秒 TTL 或后续更高编号请求清理其缓存。

若数据报短于完整消息头，或者 `magic`、`version`、`messageType`、`bodyLength` 与实际长度等头部结构不合法，服务端直接丢弃并记录日志，因为无法安全地相信其中的匹配字段。确认报文的消息体不为空也属于非法格式。若消息头完整合法、能可靠取得 `operation/clientId/requestId`，但请求消息体无法按该操作解码，则返回 `BAD_PACKET`；未知操作返回 `UNSUPPORTED`。客户端收到头部结构不合法、来源不符或与当前请求不匹配的报文时直接忽略并记录日志。

## 3. 航班业务接口及消息体

操作编号为：`1 ROUTE`、`2 DETAILS`、`3 RESERVE`、`4 MONITOR`、`5 SET_FARE`、`6 ADD_SEATS`。

所有**回复**的消息体以 `u8 status` 开头。`status=0` 表示成功，后面跟随下表结果；`status` 非 0 时，其值就是错误码，后面只能跟 `u16 错误说明字节数 + UTF-8 错误说明`，不再携带成功结果。错误码：`1 BAD_ARGUMENT`、`2 NOT_FOUND`、`3 NO_SEATS`、`4 BAD_PACKET`、`5 UNSUPPORTED`、`6 STALE_REQUEST`。错误说明最多 512 个 UTF-8 字节。客户端必须检查说明长度不超过 512、与报文剩余字节数完全一致且 UTF-8 合法，通过检查后再显示；否则把整个回复视为非法报文并忽略。

| 操作 | 请求消息体 | 成功回复中 `status` 之后的内容 | 效果 |
| --- | --- | --- | --- |
| `ROUTE` | `string source, string destination, u32 offset` | `u8 hasMore, u16 count, i32[count] flightIds` | 按页查询匹配航班号；首次 `offset=0`，零匹配用 `NOT_FOUND` |
| `DETAILS` | `i32 flightId` | `i64 departureUtcSeconds, float32 fare, i32 availableSeats` | 查询详情；不存在用 `NOT_FOUND` |
| `RESERVE` | `i32 flightId, i32 seatCount` | `i32 availableSeats` | 扣减座位；不足用 `NO_SEATS` |
| `MONITOR` | `i32 flightId, u32 intervalSeconds` | `i32 availableSeats, u32 remainingMillis` | 登记当前 UDP 来源地址和端口；不存在用 `NOT_FOUND` |
| `SET_FARE` | `i32 flightId, float32 newFare` | `float32 currentFare` | 把票价设为指定值，**幂等** |
| `ADD_SEATS` | `i32 flightId, i32 seatCount` | `i32 availableSeats` | 增加指定数量的座位，**非幂等** |

业务层验证规则：航班号须为正 `i32`，剩余座位须为非负 `i32`；预订和增加座位的数量须为正 `i32`，相加时检查溢出；票价须为有限、非负的 `float32`；监控时长为 1–600 秒。出发地和目的地各限 1–255 个 UTF-8 字节，查询前去除首尾空白、采用 Unicode NFC 规范化并按 `Locale.ROOT` 忽略大小写，显示时保留原始名称。协议中的时间戳以 UTC 为基准传输；客户端将其转换为新加坡时间（SGT，UTC+8）后显示，并明确标注时区。

本作业没有用户登录、乘客资料或订单记录。`RESERVE` 的业务效果仅是扣减服务端内存中的剩余座位，并返回确认；`clientId` 只用于识别同一次客户端运行中的请求与重传，不表示座位归属。客户端重启后若用户再次发起相同预订，那是一个新请求，服务端不能据此判断它是否与重启前的预订出自同一人或同一意图。

`ROUTE` 每页最多返回 **100 个航班号**，最大报文为 `23 + 1 + 1 + 2 + 100×4 = 427` 字节。最长的 `ROUTE` 请求包含两个各 255 字节的地名，报文长度为 `23 + (2+255)×2 + 4 = 541` 字节；最长的错误回复为 `23 + 1 + 2 + 512 = 538` 字节，均不超过 548 字节。服务端按航班号升序排列所有匹配项，`offset` 是从 0 开始的结果位置；本项目没有新增、删除或改动航线的操作，因此同一次运行中的分页顺序稳定。`hasMore` 只允许 0 或 1。客户端从 `offset=0` 开始，每收到一页就校验 `count`、报文长度和结果顺序；若 `hasMore=1`，以 `offset + count` 发起**新的请求编号**，直到 `hasMore=0`，再向用户显示收齐的全部航班号。`hasMore=1` 时 `count` 不得为 0；偏移超出结果范围时返回 `BAD_ARGUMENT`。每一页是独立的请求与回复，不是 UDP 数据报的应用层分片。

成功执行 `RESERVE` 后，服务端向该航班所有仍有效的监控者各发一条 `messageType=3, operation=MONITOR` 事件。事件头中的 `clientId` 是监控者的 ID，`requestId` 是该监控注册请求的 ID。事件消息体为 `i32 flightId, i32 availableSeats, u32 eventSequence, i64 eventUtcMillis`。同一监控注册的序号从 1 递增，客户端可忽略重复或乱序的旧事件。`ADD_SEATS` 也改变座位数，因此同样发送更新事件；票价变化不发送座位事件。

## 4. 重试、去重与监控期限

### 4.1 通用请求应答规则

客户端在发送请求后等待回复；每次等待 **1 秒**，最多发送 4 次。没有收到对应回复就视为超时。这个时间是演示环境中的经验值；超时只触发重传，不能证明请求或回复已经丢失。**重发使用完全相同的 `clientId`、`requestId` 和请求消息体**。超过次数后显示超时，此时操作可能执行零次、一次或多次，取决于服务端模式和实际收到的请求；客户端不能单凭超时声称服务端没有执行。客户端完整验证匹配的成功回复后发送一次回复确认；错误回复不确认。确认发送失败不改变已经取得的调用结果。

- **至少一次模式**：服务端对每个收到的有效请求执行操作，即使请求编号曾出现过。回复丢失后的重发可能让 `RESERVE` 或 `ADD_SEATS` 重复修改数据。
- **至多一次模式**：客户端在同一运行期只允许一个未完成请求，按递增编号发出请求；分页查询的每页也遵守这一规则。服务端为每个 `clientId` 保存最高已处理请求编号，以及**最近一次**可识别请求的操作号、原始消息体和回复。业务成功和业务错误都保存。收到头部及消息体合法的请求后，按以下顺序处理：
  1. `requestId` 大于最高编号：执行一次，更新最高编号并保存请求和回复，然后发送回复。
  2. `requestId` 等于最高编号且缓存仍存在：若 `operation` 和原始消息体完全相同，返回缓存结果且不重新执行；若内容不同，返回 `BAD_ARGUMENT`。
  3. `requestId` 小于最高编号：返回 `STALE_REQUEST`，即使碰巧与某个旧请求内容相同也不重新执行。
  4. `requestId` 等于最高编号但缓存已经清理：返回 `STALE_REQUEST`，不重新执行。
- 上述第 2 项冲突产生的 `BAD_ARGUMENT`，以及第 3、4 项产生的 `STALE_REQUEST`，只作为当前数据报的错误回复发送，不写入、不替换原有 history。
- **清理与旧请求保护**：客户端确认成功回复后，服务端立即删除该回复缓存；错误回复以及丢失确认的成功回复由 60 秒 TTL 兜底，TTL 超过客户端约 4 秒的最大重试窗口。收到更高编号的新请求时也可以立即替换旧回复，因为客户端此后不会再重发旧请求。删除回复缓存时仍保留该 `clientId` 的最高已处理编号，延迟到达的旧预订只会收到 `STALE_REQUEST`，不会再次扣座位。这样 reply history 最多每个客户端一条；客户端每次重启生成新的 `clientId`，服务端重启会清空内存状态，因此至多一次保证限定在同一次服务端运行内。最高编号表仍会随不同客户端运行实例的数量增长，但不会保存旧回复消息体。
- 客户端收到 `STALE_REQUEST` 时显示“服务端已经见过该请求，但缓存结果已不可用，执行结果未知”，不得把它解释为操作一定失败。
- 服务端启动时指定模式，并在启动日志中打印所选模式。客户端不需要配置模式；它在两种模式下都按相同规则重传和确认回复。

### 4.2 监控操作的期限

#### 4.2.1 首次登记

客户端发送 `MONITOR(flightId, intervalSeconds)`，其中期限必须在 1–600 秒内。服务端完成参数校验后：

1. 以收到数据报的源 IP 和源端口作为回调地址，不接受消息体提供其他地址。
2. 使用单调时钟记录 `registeredAtNanos`，并计算 `expiresAtNanos = registeredAtNanos + intervalSeconds × 1_000_000_000`。系统时间调整不会改变监控期限。
3. 以 `(clientId, requestId)` 标识本次登记，保存 `flightId`、回调地址、到期时间和从 0 开始的事件序号。
4. 返回当前剩余座位数和 `remainingMillis`。首次成功回复中的 `remainingMillis` 按实际剩余时间计算，不直接假定等于请求秒数。

`remainingMillis` 的计算规则为：

```text
remainingMillis = max(0, ceil((expiresAtNanos - nowNanos) / 1_000_000))
```

向上取整可以避免客户端因纳秒到毫秒的截断而提前结束。其值不超过 600,000，能够放入协议规定的 `u32`。

#### 4.2.2 注册请求重发

客户端超时重发 `MONITOR` 时，仍使用完全相同的 `clientId`、`requestId` 和消息体。服务端按当前调用模式处理：

- `at-most-once`：同编号同内容的请求命中 history，不重新登记，也不延长原期限。history 保存原到期时间，服务端用当前单调时钟重新计算 `remainingMillis`，再编码成功回复。
- `at-least-once`：每次收到请求都重新执行登记。`MonitorRegistry` 以 `(clientId, requestId)` 为键覆盖原登记，因此不会保留多个相同登记，但到期时间会从这次执行重新计算，监控期限可能被延长；已经使用的 `eventSequence` 继续递增，不因覆盖登记而归零。
- 同一 `clientId/requestId` 但操作或消息体不同的情况，按照 4.1 的编号规则处理，不修改已有登记。

在 `at-most-once` 模式下，如果重发到达时原登记已经过期，服务端仍返回成功及 `remainingMillis=0`。它不会恢复登记、延长期限或补发已经错过的事件。监控登记到期不等于立即删除 reply history；只要 history 尚在，服务端仍能识别该次重发并返回上述结果。MONITOR history 自己保存 `expiresAtNanos`，不依赖可能已经清理的 `MonitorRegistry`；重建回复时保留首次成功回复中的 `availableSeats`，只重新计算 `remainingMillis`，不会重新查询当前座位数。

#### 4.2.3 座位变化事件

成功执行 `RESERVE` 或 `ADD_SEATS` 后，服务端依次处理该航班的监控登记：

1. 先读取单调时钟；到期登记不发送事件。
2. 对每个仍有效的登记，将 `eventSequence` 加 1。
3. 按第 3 节规定填写事件头和消息体，并向登记时保存的 IP 和端口发送一个 UDP 数据报。

多个客户端可以同时监控同一航班，服务端对每个有效登记分别发送。事件本身不要求客户端确认，服务端也不重传事件；UDP 事件可能丢失、重复或乱序。客户端只接受匹配监控登记的事件，并忽略 `eventSequence` 不大于已接收最大序号的重复或旧事件。`eventUtcMillis` 仅用于显示事件发生时间，监控是否到期始终由单调时钟决定。

#### 4.2.4 客户端等待行为

客户端在第一次发送 `MONITOR` 前进入监控接收状态，并在同一个 UDP socket 上按 `messageType` 处理报文：

- 匹配当前注册请求的 `messageType=2` 报文是注册回复。
- `messageType=3, operation=MONITOR` 且 `clientId/requestId` 匹配的报文是该登记的座位事件。

注册回复到达前也可能先收到座位事件。第一种情况是服务端已经完成登记，但注册回复在 UDP 网络中丢失；登记仍然有效，其他客户端随后改变座位时，服务端仍会向该监控者发送事件。第二种情况是服务端先发送注册回复、后发送座位事件，但 UDP 不保证两个数据报按发送顺序到达，事件可能先到客户端。

因此，客户端不能把“尚未收到注册回复”理解为“服务端尚未登记”。只要事件来源正确，且 `messageType`、`operation`、`clientId` 和 `requestId` 都与当前监控匹配，客户端就立即显示该事件并更新已接收的最大 `eventSequence`；不需要等注册回复到达，也不需要另外暂存。

收到成功回复后，客户端以本地单调时钟计算 `localEndNanos = nowNanos + remainingMillis × 1_000_000`，并继续接收事件至该时刻。`remainingMillis=0` 时立即结束监控。四次发送后仍没有注册回复时，客户端停止等待并提示“登记状态未知”；这不能证明服务端没有成功登记。此后服务端登记可能仍然有效并继续向该 socket 发送事件，客户端开始其他请求时会因 `requestId` 不匹配而忽略这些残留事件。

#### 4.2.5 到期检查与清理

服务端发送每个事件前都检查到期时间，因此即使过期记录还没有从集合中物理删除，也不能再收到事件。所有基于 `System.nanoTime()` 的到期判断都使用差值 `expiresAtNanos - nowNanos <= 0`，不依赖其任意起点，也能正确处理计数值回绕。单线程实现使用约 200 毫秒的 socket 接收超时；每次收到数据报或发生接收超时后，扫描并删除过期监控登记，同时清理到期的 reply history。监控登记与 reply history 分别清理：删除过期监控不会提前删除至多一次语义所需的历史记录。

## 5. Java 实现边界

通信使用 `DatagramSocket`、`DatagramPacket` 和 `byte[]`。自行编写按偏移读写 `u16/u32/u64/i32/i64/float32/string` 的方法；`Float.floatToIntBits` 与 `Float.intBitsToFloat` 仅转换数值的位模式。不要用 RMI、RPC、CORBA、对象序列化、`DataInputStream`、`DataOutputStream` 或其他输入输出流类承担网络消息的编码与解码。控制台输入输出仅用于用户菜单与日志。
