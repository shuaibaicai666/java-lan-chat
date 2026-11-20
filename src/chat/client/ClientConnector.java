package chat.client;

import chat.common.Protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 客户端网络连接层（GUI 与 CLI 共用，可独立测试）。
 *
 * <p>职责：建立 TCP 连接、完成 LOGIN 握手、后台读线程分发服务器消息、
 * 心跳保活与失联检测、优雅退出。所有回调都发生在读线程（或调用线程），
 * GUI 层需自行转投事件分发线程（EDT）。</p>
 */
public class ClientConnector {

    /** 服务器消息回调接口（默认空实现，按需覆写） */
    public interface Listener {
        default void onWelcome(String myName) {
        }

        default void onUserList(List<String> users) {
        }

        default void onChat(String from, String text) {
        }

        default void onPrivate(String from, String text) {
        }

        default void onSystem(String text) {
        }

        default void onError(String text) {
        }

        default void onPong() {
        }

        /** 连接断开（服务器主动关闭 / 网络异常 / 心跳失联）。主动 close() 不会触发。 */
        default void onDisconnected(String reason) {
        }
    }

    private final Object writeLock = new Object();
    private volatile boolean closed = false;
    private volatile String myName;
    private volatile Listener listener;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Thread readerThread;
    private ScheduledExecutorService pinger;
    private volatile long lastPongAt = 0;

    /**
     * 连接服务器并完成登录握手。失败时抛出 IOException（消息为服务器返回的错误原因）。
     *
     * @param autoPing        是否自动发送心跳（默认间隔 10 秒）
     */
    public void connect(String host, int port, String name, Listener listener, boolean autoPing)
            throws IOException {
        connect(host, port, name, listener, autoPing, Protocol.CLIENT_PING_INTERVAL_MS);
    }

    /** 测试用重载：可指定心跳间隔 */
    public void connect(String host, int port, String name, Listener listener, boolean autoPing,
                        long pingIntervalMs) throws IOException {
        this.listener = listener;
        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress(host, port), 5000);
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

        sendLine(Protocol.CMD_LOGIN + "|" + name);
        String response = in.readLine();
        if (response == null) {
            closeSocketQuietly();
            throw new IOException("服务器关闭了连接");
        }
        if (response.startsWith(Protocol.CMD_ERROR + "|")) {
            String msg = response.substring(Protocol.CMD_ERROR.length() + 1);
            closeSocketQuietly();
            throw new IOException(msg);
        }
        if (response.startsWith(Protocol.CMD_WELCOME + "|")) {
            this.myName = response.substring(Protocol.CMD_WELCOME.length() + 1);
            this.lastPongAt = System.currentTimeMillis();
            listener.onWelcome(myName);
            this.readerThread = new Thread(this::readLoop, "client-reader-" + myName);
            this.readerThread.setDaemon(true);
            this.readerThread.start();
            if (autoPing) {
                startPinger(pingIntervalMs);
            }
        } else {
            closeSocketQuietly();
            throw new IOException("未知响应: " + response);
        }
    }

    public String getMyName() {
        return myName;
    }

    public boolean isClosed() {
        return closed;
    }

    /** 发送群聊消息 */
    public void sendChat(String text) {
        sendLine(Protocol.CMD_CHAT + "|" + text);
    }

    /** 发送私聊消息 */
    public void sendPrivate(String target, String text) {
        sendLine(Protocol.CMD_PRIVATE + "|" + target + "|" + text);
    }

    /** 发送心跳 */
    public void sendPing() {
        sendLine(Protocol.CMD_PING);
    }

    /** 发送原始协议行（供测试与调试使用） */
    public void sendRaw(String line) {
        sendLine(line);
    }

    /** 主动退出：发送 LOGOUT 并关闭连接，不触发 onDisconnected */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        stopPinger();
        try {
            // 直接写 LOGOUT（sendLine 在 closed 状态下会静默跳过，这里不走它）
            if (out != null && socket != null && !socket.isClosed()) {
                synchronized (writeLock) {
                    out.println(Protocol.CMD_LOGOUT);
                    out.flush();
                }
            }
        } catch (Exception ignored) {
            // 网络已不可用，忽略
        }
        closeSocketQuietly();
    }

    // ------------------------------------------------------------------

    private void sendLine(String line) {
        synchronized (writeLock) {
            if (closed || out == null) {
                return;
            }
            try {
                out.println(line);
                if (out.checkError()) {
                    throw new IOException("写入失败");
                }
            } catch (Exception e) {
                handleDisconnect("发送失败: " + e.getMessage());
            }
        }
    }

    private void readLoop() {
        try {
            String line;
            while (!closed && (line = in.readLine()) != null) {
                dispatch(line);
            }
            handleDisconnect("服务器关闭了连接");
        } catch (IOException e) {
            handleDisconnect(e.getMessage() == null ? "连接异常" : e.getMessage());
        }
    }

    private void dispatch(String line) {
        if (line.isEmpty()) {
            return;
        }
        String[] parts = line.split("\\|", -1);
        String cmd = parts[0];
        switch (cmd) {
            case Protocol.CMD_USERLIST -> {
                String body = parts.length > 1 ? parts[1] : "";
                List<String> users = body.isEmpty() ? List.of() : List.of(body.split(","));
                listener.onUserList(users);
            }
            case Protocol.CMD_CHAT -> listener.onChat(parts.length > 1 ? parts[1] : "", joinFrom(parts, 2));
            case Protocol.CMD_PRIVATE -> listener.onPrivate(parts.length > 1 ? parts[1] : "", joinFrom(parts, 2));
            case Protocol.CMD_SYSTEM -> listener.onSystem(joinFrom(parts, 1));
            case Protocol.CMD_PONG -> {
                lastPongAt = System.currentTimeMillis();
                listener.onPong();
            }
            case Protocol.CMD_ERROR -> listener.onError(joinFrom(parts, 1));
            default -> listener.onSystem("[未知消息] " + line);
        }
    }

    private static String joinFrom(String[] parts, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (i > from) {
                sb.append('|');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private void startPinger(long intervalMs) {
        pinger = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "client-pinger-" + myName);
            t.setDaemon(true);
            return t;
        });
        pinger.scheduleAtFixedRate(() -> {
            if (closed) {
                return;
            }
            sendLine(Protocol.CMD_PING);
            if (System.currentTimeMillis() - lastPongAt > Protocol.CLIENT_PING_TIMEOUT_MS) {
                handleDisconnect("心跳超时，服务器无响应");
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void stopPinger() {
        if (pinger != null) {
            pinger.shutdownNow();
            pinger = null;
        }
    }

    private void handleDisconnect(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        stopPinger();
        closeSocketQuietly();
        listener.onDisconnected(reason);
    }

    private void closeSocketQuietly() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }
}
