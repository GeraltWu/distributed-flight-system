# SC6103 Distributed Flight System

基于 UDP 的分布式航班信息系统，使用 Java 实现。网络消息按照 [协议文档](docs/protocol.md) 手动编码和解码，不使用 Java RMI、对象序列化或输入输出流类进行消息编解码。

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
      │  ├─ ProtocolException.java
      │  └─ InvocationMode.java       # 后续实现两种调用语义时添加
      ├─ server/
      │  ├─ ServerMain.java
      │  ├─ FlightServer.java
      │  ├─ Flight.java
      │  ├─ FlightService.java
      │  ├─ MonitorRegistry.java      # 后续实现监控时添加
      │  └─ ReplyHistory.java         # 后续实现 at-most-once 时添加
      └─ client/
         ├─ ClientMain.java
         └─ FlightClient.java
```

`out/` 用于保存编译生成的 `.class` 文件，不属于源码目录。

## 目录职责

- `flight.protocol`：客户端和服务端共享的协议常量、消息结构及字节编解码，不保存业务状态。
- `flight.server`：维护航班数据，执行业务操作，接收请求、发送回复并处理监控登记和回复历史。
- `flight.client`：提供控制台界面，生成请求编号，发送请求、接收回复并显示结果。
- `data/flights.tsv`：保存初始航班数据。服务端启动时载入内存，运行期间的修改不会写回文件。

服务端是航班数据的唯一维护者。客户端可以检查用户输入，但所有业务参数仍由服务端重新验证。

## 当前功能

目前已实现基本 UDP 客户端、服务端和 `DETAILS` 航班详情查询。客户端可以输入航班号，查询起飞时间、票价和剩余座位；不存在的航班会收到 `NOT_FOUND` 错误。其他操作、超时重传、两种调用语义和监控将在后续阶段实现。

## 编译和运行

在项目根目录使用 PowerShell 编译和运行。服务端会从相对路径 `data/flights.tsv` 读取数据：

```powershell
New-Item -ItemType Directory -Force out
javac --release 17 -encoding UTF-8 -d out (Get-ChildItem -Recurse src -Filter *.java).FullName
```

先启动服务端：

```powershell
java -cp out flight.server.ServerMain 5000
```

再打开另一个终端启动客户端：

```powershell
java -cp out flight.client.ClientMain 127.0.0.1 5000

java -cp out flight.client.ClientMain 120.26.249.221 5000
```

跨电脑运行时，把 `127.0.0.1` 换成服务端电脑的 IP 地址 `120.26.249.221`。
