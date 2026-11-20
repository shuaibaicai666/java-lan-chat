# 开发迭代记录（AI 生成 → 人工审批 → 修正）

> 按 AI 工程化流程：规约（spec-skill.md）→ AI Agent 生成代码 → 人工对高风险动作审批 →
> 编译/测试发现问题 → 回到规约或代码修正 → 再次验证。本文件记录真实迭代过程。

## 迭代 1：初版代码生成与首次编译

- **动作**：将 spec-skill.md 提交给 AI Agent，生成 Protocol / ChatServer / ClientHandler /
  ClientConnector / LoginFrame / ChatFrame / CliClient / DemoClient / TestRunner 全部源码。
- **人工审批结果**：编译失败，2 处错误——`DemoClient.java` 中 Swing `Timer` 的 lambda 捕获了
  非 final 局部变量（`message`、`peer`、`privateMsg`）。
- **判定**：属于语言规则问题，不属于需求边界问题，直接修正（将变量复制为 final 局部变量）。
- **结果**：重新编译通过（exit 0）。

## 迭代 2：自动化验收测试首轮

- **动作**：运行 TestRunner（对应 test-cases.md 的 T01~T12）。
- **结果**：12 / 12 全部 PASS，其中：
  - T04 私聊定向：目标收到、第三方未收到、无回声 ✓
  - T08 异常掉线：模拟进程崩溃后 2 秒内被服务器踢除并广播 ✓
  - T11 并发：30 客户端全部登录，一条广播全部收到，实测广播延迟 0ms（< 1s 达标）✓
- **审批结论**：验收标准全部达成，无需回到需求重新定义边界。

## 迭代 3：人工代码走查发现的问题（高风险动作审批）

对 spec-skill.md 第 5 节列出的 6 个高风险点逐项走查：

| 风险点 | 审批结论 | 备注 |
|---|---|---|
| 1 端口绑定 | 通过 | 端口被占用抛出 BindException，给出明确错误后退出，不静默换端口（T12 验证） |
| 2 广播线程安全 | 通过 | 在线用户表 ConcurrentHashMap；每连接写入加锁（writeLock）保证行完整性 |
| 3 异常掉线资源释放 | 通过 | 读线程 EOF/异常 → cleanup：移除在线表、关 Socket、广播新列表（T08 验证） |
| 4 畸形输入 | 通过 | 所有解析失败走 ERROR 分支，服务器不崩溃（T10 验证） |
| 5 GUI 线程规则 | 通过 | 所有网络回调经 SwingUtilities.invokeLater 转投 EDT；连接在后台线程执行 |
| 6 心跳与退出 | 通过（修正 1 处） | 走查发现 close() 先置 closed=true 再调 sendLine(LOGOUT) 会被静默跳过，导致 LOGOUT 未发出；修正为直接写入并 flush。修正后 T06 通过 |

## 迭代 4：GUI 联调冒烟

- **动作**：启动服务器图形控制台 + 3 个图形客户端（DemoClient 自动发送预设消息），截图取证。
- **结果**：服务器控制台正确显示连接/上下线/群聊/私聊日志；3 个客户端窗口群聊、私聊、
  在线列表显示正常（见报告"系统验证"章节截图）。

## 结论

需求边界在迭代过程中未发生改变，仅两处实现级缺陷（lambda 捕获、LOGOUT 发送），
均通过人工审批环节发现并修正，最终 12/12 验收用例通过。
