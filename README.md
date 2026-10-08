# SC6103 Distributed Flight System

基于 UDP 的分布式航班信息系统，使用 Java 实现。网络消息按照 [协议文档](docs/protocol.md) 手动编码和解码，不使用 Java RMI、对象序列化或输入输出流类进行消息编解码。

课堂展示的机器安排、操作顺序和预期结果见 [演示指南](docs/demo-guide.md)。

## 项目目录

```text
distributed-flight-system/
├─ data/
│  └─ flights.tsv                    # 服务端启动时读取的航班数据
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

`out/` 用于保存编译生成的 `.class` 文件，不属于源码目录。

## 目录职责

- `flight.protocol`：客户端和服务端共享的协议常量、消息结构及字节编解码，不保存业务状态。
- `flight.server`：`FlightServer` 负责 UDP 和调用语义，`FlightRequestHandler` 负责业务请求与回复，其他类维护航班数据、监控登记和回复历史。
- `flight.client`：提供控制台界面，生成请求编号，发送请求、接收回复并显示结果。
- `data/flights.tsv`：保存初始航班数据。服务端启动时载入内存，运行期间的修改不会写回文件。

服务端是航班数据的唯一维护者。客户端可以检查用户输入，但所有业务参数仍由服务端重新验证。

## 当前功能

目前已实现六个业务操作：分页航线查询 `ROUTE`、航班详情 `DETAILS`、座位预订 `RESERVE`、限时座位监控 `MONITOR`、幂等的票价设置 `SET_FARE`，以及非幂等的座位增加 `ADD_SEATS`。协议使用 UTC epoch 时间，客户端界面统一转换为新加坡时间并标注 `SGT`。客户端每次等待回复 1 秒，最多发送 4 次请求；服务端启动时可选择 `at-least-once` 或 `at-most-once`。至多一次模式使用 reply history 去重，客户端确认与 60 秒 TTL 负责清理缓存。`RESERVE` 和 `ADD_SEATS` 成功后，服务端会向该航班的有效监控客户端发送 UDP callback。

## 编译和运行

在项目根目录使用 PowerShell 编译和运行。服务端会从相对路径 `data/flights.tsv` 读取数据：

```powershell
New-Item -ItemType Directory -Force out
javac --release 17 -encoding UTF-8 -d out (Get-ChildItem -Recurse src -Filter *.java).FullName
```

先启动服务端：

```powershell
java -cp out flight.server.ServerMain 5000 at-most-once

java -cp out flight.server.ServerMain 5000 at-least-once
```

再打开另一个终端启动客户端：

```powershell
java -cp out flight.client.ClientMain 127.0.0.1 5000

java -cp out flight.client.ClientMain 120.26.249.221 5000
```

服务端的最后一个参数选择调用语义，客户端命令不变。跨电脑运行时，把 `127.0.0.1` 换成服务端电脑的 IP 地址 `120.26.249.221`。

## 丢包模拟

服务端可选的第三个参数用于调用语义实验。对每个新的 `ADD_SEATS` 调用，它会丢弃该 `(clientId, requestId)` 的第一次请求或第一次回复；同一请求的后续重传正常处理。省略时不模拟丢包：

```powershell
java -cp out flight.server.ServerMain 5000 at-most-once drop-request-once
java -cp out flight.server.ServerMain 5000 at-least-once drop-reply-once
java -cp out flight.server.ServerMain 5000 at-most-once drop-reply-once
```

重启服务端会恢复 TSV 初始数据，并清空已经触发过丢包的请求编号记录。
