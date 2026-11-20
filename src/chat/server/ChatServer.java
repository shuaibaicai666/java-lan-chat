package chat.server;

import chat.common.Protocol;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 聊天室服务器。
 *
 * <p>架构：主线程阻塞 accept；每个客户端连接交给线程池中的一个 ClientHandler；
 * 在线用户表使用 ConcurrentHashMap 保证并发安全；后台心跳监控线程定期踢除超时连接。</p>
 */
public class ChatServer implements AutoCloseable {

    /** 日志输出接口：GUI 模式转发到控制台窗口，CLI 模式输出到标准输出 */
    public interface LogSink {
        void log(String line);
    }

    private final int port;
    private final long heartbeatTimeoutMs;
    private final ConcurrentHashMap<String, ClientHandler> users = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "client-handler");
        t.setDaemon(true);
        return t;
    });

    private ServerSocket serverSocket;
    private ScheduledExecutorService monitor;
    private volatile boolean running = false;
    private LogSink sink = System.out::println;

    public ChatServer(int port, long heartbeatTimeoutMs) {
        this.port = port;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
    }

    public void setLogSink(LogSink sink) {
        this.sink = sink;
    }

    public void log(String line) {
        sink.log(line);
    }

    /** 启动服务器，阻塞直到 close()。端口被占用时抛出 BindException。 */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        log("服务器启动成功，监听端口 " + port
                + "（心跳超时 " + heartbeatTimeoutMs + " ms，人数上限 " + Protocol.MAX_USERS + "）");

        monitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-monitor");
            t.setDaemon(true);
            return t;
        });
        long interval = Math.max(200, Math.min(1000, heartbeatTimeoutMs / 4));
        monitor.scheduleAtFixedRate(this::sweepInactive, interval, interval, TimeUnit.MILLISECONDS);

        while (running) {
            try {
                Socket socket = serverSocket.accept();
                log("新连接：" + socket.getRemoteSocketAddress());
                pool.execute(new ClientHandler(this, socket));
            } catch (SocketException e) {
                if (running) {
                    log("监听结束：" + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running) {
                    log("接受连接异常：" + e.getMessage());
                }
            }
        }
        log("服务器已停止");
    }

    /** 心跳监控：踢除超时未活动的连接（socket 关闭会触发该连接的读线程完成清理） */
    private void sweepInactive() {
        long now = System.currentTimeMillis();
        for (ClientHandler handler : users.values()) {
            if (!handler.isClosed() && now - handler.lastActive() > heartbeatTimeoutMs) {
                handler.forceClose("心跳超时被踢除");
            }
        }
    }

    // ---- 在线用户管理（供 ClientHandler 调用）----

    boolean register(String name, ClientHandler handler) {
        return users.putIfAbsent(name, handler) == null;
    }

    boolean unregister(String name, ClientHandler handler) {
        return users.remove(name, handler);
    }

    ClientHandler findUser(String name) {
        return users.get(name);
    }

    int userCount() {
        return users.size();
    }

    // ---- 广播 ----

    void broadcast(String line) {
        for (ClientHandler handler : users.values()) {
            handler.sendLine(line);
        }
    }

    void broadcastToOthers(String line, ClientHandler except) {
        for (ClientHandler handler : users.values()) {
            if (handler != except) {
                handler.sendLine(line);
            }
        }
    }

    void broadcastUserList() {
        String list = String.join(",", new TreeSet<>(users.keySet()));
        broadcast(Protocol.CMD_USERLIST + "|" + list);
    }

    @Override
    public void close() {
        running = false;
        if (monitor != null) {
            monitor.shutdownNow();
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        for (ClientHandler handler : users.values()) {
            handler.forceClose("服务器关闭");
        }
        pool.shutdownNow();
    }

    /** 服务器入口：--port N / --nogui / --heartbeat-timeout N */
    public static void main(String[] args) {
        int port = Protocol.DEFAULT_PORT;
        long heartbeatTimeout = Protocol.SERVER_HEARTBEAT_TIMEOUT_MS;
        boolean gui = true;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--nogui" -> gui = false;
                case "--heartbeat-timeout" -> heartbeatTimeout = Long.parseLong(args[++i]);
                default -> System.err.println("忽略未知参数: " + args[i]);
            }
        }

        ChatServer server = new ChatServer(port, heartbeatTimeout);
        if (gui) {
            ServerFrame frame = new ServerFrame(server);
            server.setLogSink(frame::appendLog);
            javax.swing.SwingUtilities.invokeLater(() -> frame.setVisible(true));
        } else {
            server.setLogSink(System.out::println);
        }

        try {
            server.start();
        } catch (BindException e) {
            System.err.println("[错误] 端口 " + port + " 已被占用，无法启动：" + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("[错误] 服务器启动失败：" + e);
            System.exit(1);
        }
    }
}
