package chat.client;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 聊天主窗口：消息区 + 在线用户列表 + 输入框。
 * 群聊：直接输入内容；私聊：@昵称 内容。
 * 所有网络回调经 SwingUtilities.invokeLater 转投 EDT。
 */
public class ChatFrame extends JFrame implements ClientConnector.Listener {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ClientConnector connector;
    private final JTextArea messageArea = new JTextArea(22, 46);
    private final DefaultListModel<String> userModel = new DefaultListModel<>();
    private final JLabel userLabel = new JLabel("在线用户 (0)");
    private final JTextField inputField = new JTextField();
    private final JButton sendButton = new JButton("发送");
    private boolean connected = true;

    public ChatFrame(ClientConnector connector, String myName) {
        super("局域网聊天室 - " + myName + "（连接中...）");
        this.connector = connector;

        // ---- 中部：消息区 ----
        messageArea.setEditable(false);
        messageArea.setLineWrap(true);
        messageArea.setWrapStyleWord(true);
        messageArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        JScrollPane messageScroll = new JScrollPane(messageArea);
        messageScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        // ---- 右侧：在线用户列表 ----
        JList<String> userList = new JList<>(userModel);
        userList.setFixedCellWidth(120);
        JScrollPane userScroll = new JScrollPane(userList);
        JPanel userPanel = new JPanel(new BorderLayout(0, 4));
        userPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        userPanel.add(userLabel, BorderLayout.NORTH);
        userPanel.add(userScroll, BorderLayout.CENTER);
        userPanel.setPreferredSize(new Dimension(150, 0));

        // ---- 底部：提示 + 输入框 + 发送按钮 ----
        JPanel inputPanel = new JPanel(new BorderLayout(6, 0));
        JLabel hintLabel = new JLabel("直接输入 = 群聊；@昵称 内容 = 私聊");
        hintLabel.setForeground(Color.GRAY);
        JPanel southPanel = new JPanel(new BorderLayout(0, 2));
        southPanel.add(hintLabel, BorderLayout.NORTH);
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        southPanel.add(inputPanel, BorderLayout.CENTER);
        southPanel.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));

        add(messageScroll, BorderLayout.CENTER);
        add(userPanel, BorderLayout.EAST);
        add(southPanel, BorderLayout.SOUTH);

        sendButton.addActionListener(e -> sendMessage());
        inputField.addActionListener(e -> sendMessage());
        getRootPane().setDefaultButton(sendButton);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                connector.close();
                dispose();
                System.exit(0);
            }
        });
        setSize(720, 480);
        setLocationRelativeTo(null);
    }

    private void sendMessage() {
        if (!connected) {
            return;
        }
        String raw = inputField.getText();
        if (raw.isBlank()) {
            return;
        }
        if (raw.startsWith("@")) {
            int space = raw.indexOf(' ');
            if (space <= 1) {
                append("【提示】私聊格式：@昵称 内容", true);
                return;
            }
            String target = raw.substring(1, space).trim();
            String text = raw.substring(space + 1);
            if (target.equals(connector.getMyName())) {
                append("【提示】不能给自己发私聊", true);
                return;
            }
            connector.sendPrivate(target, text);
            append("[私聊] 我 -> " + target + ": " + text, true);
        } else {
            connector.sendChat(raw);
        }
        inputField.setText("");
    }

    private void append(String text, boolean stamp) {
        String line = stamp ? "[" + LocalTime.now().format(TIME) + "] " + text : text;
        if (SwingUtilities.isEventDispatchThread()) {
            doAppend(line);
        } else {
            SwingUtilities.invokeLater(() -> doAppend(line));
        }
    }

    private void doAppend(String line) {
        messageArea.append(line + "\n");
        messageArea.setCaretPosition(messageArea.getDocument().getLength());
    }

    // ---- 网络回调（读线程），全部转投 EDT ----

    @Override
    public void onWelcome(String myName) {
        SwingUtilities.invokeLater(() -> {
            setTitle("局域网聊天室 - " + myName);
            append("欢迎 " + myName + " 加入聊天室！", true);
        });
    }

    @Override
    public void onUserList(List<String> users) {
        SwingUtilities.invokeLater(() -> {
            userModel.clear();
            for (String u : users) {
                if (!u.isEmpty()) {
                    userModel.addElement(u);
                }
            }
            userLabel.setText("在线用户 (" + userModel.size() + ")");
        });
    }

    @Override
    public void onChat(String from, String text) {
        append(from + ": " + text, true);
    }

    @Override
    public void onPrivate(String from, String text) {
        append("[私聊] " + from + " -> 我: " + text, true);
    }

    @Override
    public void onSystem(String text) {
        append("[系统] " + text, true);
    }

    @Override
    public void onError(String text) {
        append("[错误] " + text, true);
    }

    @Override
    public void onDisconnected(String reason) {
        SwingUtilities.invokeLater(() -> {
            if (!connected) {
                return;
            }
            connected = false;
            setTitle(getTitle() + "（已断开）");
            append("[系统] 与服务器断开连接：" + reason, true);
            inputField.setEnabled(false);
            sendButton.setEnabled(false);
        });
    }
}
