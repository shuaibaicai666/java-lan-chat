package chat.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 命令行客户端（无界面）：用于自动化测试、服务器远程调试与演示。
 *
 * <p>用法：java chat.client.CliClient 昵称 [--host H] [--port P] [--no-ping]</p>
 * <p>交互：直接输入 = 群聊；@昵称 内容 = 私聊；:quit = 退出</p>
 */
public class CliClient implements ClientConnector.Listener {

    private final ClientConnector connector = new ClientConnector();
    private volatile boolean running = true;
    private String myName;

    public void start(String name, String host, int port, boolean autoPing) throws IOException {
        this.myName = name;
        connector.connect(host, port, name, this, autoPing);
        System.out.println("[系统] 已登录为 " + name + "（输入 :quit 退出）");
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String line;
            while (running && (line = stdin.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (":quit".equals(line)) {
                    break;
                }
                if (line.startsWith("@")) {
                    int sp = line.indexOf(' ');
                    if (sp <= 1) {
                        System.out.println("[提示] 私聊格式：@昵称 内容");
                        continue;
                    }
                    connector.sendPrivate(line.substring(1, sp).trim(), line.substring(sp + 1));
                } else {
                    connector.sendChat(line);
                }
            }
        } finally {
            running = false;
            connector.close();
        }
    }

    // ---- 网络回调 ----

    @Override
    public void onWelcome(String name) {
        myName = name;
    }

    @Override
    public void onUserList(List<String> users) {
        System.out.println("[系统] 在线用户: " + String.join(", ", users));
    }

    @Override
    public void onChat(String from, String text) {
        System.out.println(from + ": " + text);
    }

    @Override
    public void onPrivate(String from, String text) {
        System.out.println("[私聊] " + from + " -> 我: " + text);
    }

    @Override
    public void onSystem(String text) {
        System.out.println("[系统] " + text);
    }

    @Override
    public void onError(String text) {
        System.out.println("[错误] " + text);
    }

    @Override
    public void onDisconnected(String reason) {
        running = false;
        System.out.println("[系统] 连接已断开: " + reason);
    }

    public static void main(String[] args) throws Exception {
        String name = null;
        String host = "127.0.0.1";
        int port = chat.common.Protocol.DEFAULT_PORT;
        boolean autoPing = true;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--no-ping" -> autoPing = false;
                default -> {
                    if (name == null && !args[i].startsWith("--")) {
                        name = args[i];
                    }
                }
            }
        }
        if (name == null) {
            System.out.println("用法: java chat.client.CliClient 昵称 [--host H] [--port P] [--no-ping]");
            System.exit(2);
        }
        new CliClient().start(name, host, port, autoPing);
    }
}
