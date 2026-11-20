package chat.server;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 服务器图形控制台：实时显示连接、上下线、消息流日志。
 * 日志来自多线程，统一经 SwingUtilities.invokeLater 转投 EDT。
 */
public class ServerFrame extends JFrame {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final JTextArea logArea = new JTextArea(24, 70);
    private final ChatServer server;

    public ServerFrame(ChatServer server) {
        super("局域网聊天室 - 服务器控制台");
        this.server = server;

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        add(scrollPane, BorderLayout.CENTER);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                server.close();
                dispose();
                System.exit(0);
            }
        });
        pack();
        setLocation(50, 50);
    }

    /** 供多线程调用：追加一行带时间戳的日志（转投 EDT） */
    public void appendLog(String line) {
        if (SwingUtilities.isEventDispatchThread()) {
            doAppend(line);
        } else {
            SwingUtilities.invokeLater(() -> doAppend(line));
        }
    }

    private void doAppend(String line) {
        logArea.append("[" + LocalTime.now().format(TIME) + "] " + line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }
}
