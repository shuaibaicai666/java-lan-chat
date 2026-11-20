package chat.client;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.io.IOException;

/**
 * 演示客户端：自动登录并定时发送预设消息，用于无人值守演示与截图取证。
 * 普通用户请使用 LoginFrame（图形）或 CliClient（命令行）。
 *
 * <p>用法：java chat.client.DemoClient --name 昵称 [--host H] [--port P]
 * [--message 群聊内容] [--peer 对方昵称] [--private 私聊内容]</p>
 */
public class DemoClient {

    public static void main(String[] args) {
        String name = "演示用户";
        String host = "127.0.0.1";
        int port = chat.common.Protocol.DEFAULT_PORT;
        String message = null;
        String peer = null;
        String privateMsg = null;
        int x = -1;
        int y = -1;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--name" -> name = args[++i];
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--message" -> message = args[++i];
                case "--peer" -> peer = args[++i];
                case "--private" -> privateMsg = args[++i];
                case "--x" -> x = Integer.parseInt(args[++i]);
                case "--y" -> y = Integer.parseInt(args[++i]);
            }
        }

        ClientConnector connector = new ClientConnector();
        ChatFrame frame = new ChatFrame(connector, name);
        if (x >= 0 && y >= 0) {
            frame.setLocation(x, y);
        }
        SwingUtilities.invokeLater(() -> frame.setVisible(true));

        try {
            connector.connect(host, port, name, frame, true);
        } catch (IOException e) {
            System.err.println("[错误] 连接失败: " + e.getMessage());
            System.exit(1);
        }

        if (message != null) {
            final String fMessage = message;
            Timer t = new Timer(1500, e -> connector.sendChat(fMessage));
            t.setRepeats(false);
            t.start();
        }
        if (peer != null && privateMsg != null) {
            final String fPeer = peer;
            final String fPrivateMsg = privateMsg;
            Timer t = new Timer(4000, e -> connector.sendPrivate(fPeer, fPrivateMsg));
            t.setRepeats(false);
            t.start();
        }
    }
}
