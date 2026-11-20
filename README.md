# 局域网聊天室（Java 21）

《Java语言及网络编程》课程报告项目 —— 纯 JDK 标准库实现的局域网多人在线聊天系统。
支持登录、群聊、私聊、在线用户列表、心跳保活与异常掉线检测，带 Swing 图形界面，
附命令行客户端与自动化验收测试（T01~T12）。

## 目录结构

```
LanChat/
├── src/
│   └── chat/
│       ├── common/Protocol.java        # 应用层协议定义与昵称校验
│       ├── server/ChatServer.java      # 服务器：accept 主循环 + 线程池 + 心跳监控
│       ├── server/ClientHandler.java   # 每连接处理线程：协议解析与分发
│       ├── server/ServerFrame.java     # 服务器图形控制台
│       ├── client/ClientConnector.java # 客户端网络层（GUI/CLI 共用，可测试）
│       ├── client/LoginFrame.java      # 登录窗口
│       ├── client/ChatFrame.java       # 聊天主窗口
│       ├── client/CliClient.java       # 命令行客户端（测试/调试用）
│       ├── client/DemoClient.java      # 演示客户端（自动发消息，截图取证用）
│       └── test/TestRunner.java        # 自动化验收测试 T01~T12
├── docs/
│   ├── spec-skill.md                   # AI 工程化规约（需求分解 + 验收标准）
│   └── test-cases.md                   # 测试与评测集
├── build.bat                           # 编译
├── run-server.bat                      # 启动服务器（图形控制台）
├── run-client.bat                      # 启动图形客户端
├── run-client-cli.bat                  # 启动命令行客户端
└── run-test.bat                        # 运行自动化验收测试
```

## 编译与运行

### 方式一：bat 脚本（推荐）

```
build.bat                     编译（输出到 out 目录）
run-server.bat                启动服务器（默认端口 8888）
run-client.bat                启动客户端（可开多个窗口）
run-client-cli.bat 小明       命令行客户端
run-test.bat                  自动化验收测试
```

### 方式二：IntelliJ IDEA

File → Open 选择 LanChat 目录 → 项目结构选择 JDK 21 →
直接运行 `chat.server.ChatServer`、`chat.client.LoginFrame`、`chat.test.TestRunner` 的 main 方法。

### 方式三：手动 javac

```
javac -encoding UTF-8 -d out src\chat\common\Protocol.java src\chat\server\*.java src\chat\client\*.java src\chat\test\TestRunner.java
java -cp out chat.server.ChatServer
```

## 使用说明

1. 先启动服务器（`run-server.bat`），控制台显示"监听端口 8888"。
2. 启动一个或多个客户端（`run-client.bat`），输入 127.0.0.1 / 8888 / 昵称，点登录。
3. 直接输入文字回车 = 群聊；输入 `@昵称 内容` = 私聊。
4. 同一局域网内其他电脑：客户端地址填服务器电脑的 IP，防火墙放行 8888 端口即可。

## 应用层协议（行文本，UTF-8，| 分隔）

| 方向 | 格式 | 含义 |
|---|---|---|
| C→S | `LOGIN\|昵称` | 登录 |
| C→S | `CHAT\|内容` | 群聊 |
| C→S | `PRIVATE\|目标\|内容` | 私聊 |
| C→S | `PING` / `LOGOUT` | 心跳 / 退出 |
| S→C | `WELCOME\|昵称` | 登录成功 |
| S→C | `USERLIST\|u1,u2,...` | 在线列表 |
| S→C | `CHAT\|发送者\|内容` | 群聊广播 |
| S→C | `PRIVATE\|发送者\|内容` | 私聊投递 |
| S→C | `SYSTEM\|文本` / `ERROR\|文本` | 系统通知 / 错误 |
| S→C | `PONG` | 心跳应答 |

## 常见问题

- **端口被占用**：`run-server.bat --port 9999` 换端口，客户端端口同步修改。
- **局域网连不上**：Windows 防火墙放行 java.exe / 8888 端口；两台电脑 ping 通。
- **服务器控制台乱码**：bat 已设置 UTF-8；IDEA 中运行时在 Run 配置加 `-Dfile.encoding=UTF-8`。
