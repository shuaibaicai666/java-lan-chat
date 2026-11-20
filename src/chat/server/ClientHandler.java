package chat.server;

import chat.common.Protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每个客户端连接一个处理线程。
 *
 * <p>职责：解析协议行并分发；维护该连接的心跳时间戳；写入采用每连接一把锁
 * （多线程广播时保证行完整性）；异常退出时完成下线清理。</p>
 */
class ClientHandler implements Runnable {

    private final ChatServer server;
    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile String name;            // null = 尚未登录
    private volatile long lastActive = System.currentTimeMillis();
    private volatile String closeReason = "连接断开";

    ClientHandler(ChatServer server, Socket socket) throws IOException {
        this.server = server;
        this.socket = socket;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
    }

    @Override
    public void run() {
        try {
            String line;
            while (!closed.get() && (line = in.readLine()) != null) {
                lastActive = System.currentTimeMillis();
                if (!process(line)) {
                    break; // 该条消息要求断开连接
                }
            }
        } catch (IOException e) {
            // 客户端异常断开，或 socket 被心跳监控/服务器关闭强制关闭
        } finally {
            cleanup();
        }
    }

    /** 处理一条协议行；返回 false 表示随后应断开连接 */
    private boolean process(String line) {
        if (line.isEmpty()) {
            return true;
        }
        String[] head = line.split("\\|", 2);
        String cmd = head[0];
        switch (cmd) {
            case Protocol.CMD_LOGIN -> {
                if (name != null) {
                    reject("已登录，请勿重复登录");
                    return false;
                }
                String rawName = head.length > 1 ? head[1] : "";
                String err = Protocol.validateName(rawName);
                if (err != null) {
                    reject(err);
                    return false;
                }
                if (server.userCount() >= Protocol.MAX_USERS) {
                    reject("服务器人数已满，请稍后再试");
                    return false;
                }
                if (server.register(rawName, this)) {
                    name = rawName;
                    sendLine(Protocol.CMD_WELCOME + "|" + name);
                    server.broadcastToOthers(Protocol.CMD_SYSTEM + "|" + name + " 上线了", this);
                    server.broadcastUserList();
                    server.log("用户 " + name + " 登录（" + socket.getRemoteSocketAddress() + "）");
                } else {
                    reject("昵称已被使用，请换一个");
                    return false;
                }
            }
            case Protocol.CMD_CHAT -> {
                if (name == null) {
                    reject("请先登录");
                    return false;
                }
                String content = head.length > 1 ? head[1] : "";
                if (content.isBlank()) {
                    return true;
                }
                server.broadcast(Protocol.CMD_CHAT + "|" + name + "|" + content);
                server.log("[群聊] " + name + ": " + content);
            }
            case Protocol.CMD_PRIVATE -> {
                if (name == null) {
                    reject("请先登录");
                    return false;
                }
                String[] parts = Protocol.split(line, 3);
                if (parts == null) {
                    reject("私聊格式错误：应为 PRIVATE|目标昵称|内容");
                    return true;
                }
                String target = parts[1];
                String content = parts[2];
                ClientHandler targetHandler = server.findUser(target);
                if (targetHandler == null) {
                    sendLine(Protocol.CMD_ERROR + "|用户「" + target + "」不存在或已离线");
                    return true;
                }
                if (content.isBlank()) {
                    reject("私聊内容不能为空");
                    return true;
                }
                targetHandler.sendLine(Protocol.CMD_PRIVATE + "|" + name + "|" + content);
                server.log("[私聊] " + name + " -> " + target + ": " + content);
            }
            case Protocol.CMD_PING -> sendLine(Protocol.CMD_PONG);
            case Protocol.CMD_LOGOUT -> {
                if (name != null) {
                    closeReason = "主动退出";
                    server.log("用户 " + name + " 主动退出");
                }
                return false;
            }
            default -> reject("未知指令：" + cmd);
        }
        return true;
    }

    /** 发送错误提示（不主动断开；是否断开由调用方决定） */
    private void reject(String msg) {
        sendLine(Protocol.CMD_ERROR + "|" + msg);
    }

    /** 线程安全地发送一行 */
    void sendLine(String line) {
        synchronized (writeLock) {
            if (closed.get()) {
                return;
            }
            try {
                out.println(line);
                if (out.checkError()) {
                    forceClose("写入失败");
                }
            } catch (Exception e) {
                forceClose("写入异常");
            }
        }
    }

    /** 强制断开（心跳超时/服务器关闭时由其他线程调用），清理工作由读线程完成 */
    void forceClose(String reason) {
        if (!closed.get()) {
            closeReason = reason;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    long lastActive() {
        return lastActive;
    }

    boolean isClosed() {
        return closed.get();
    }

    private void cleanup() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (name != null && server.unregister(name, this)) {
            server.broadcast(Protocol.CMD_SYSTEM + "|" + name + " 下线了");
            server.broadcastUserList();
            server.log("用户 " + name + " 下线（" + closeReason + "）");
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
