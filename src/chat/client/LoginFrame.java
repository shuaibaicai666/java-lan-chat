package chat.client;

import chat.common.Protocol;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;

/**
 * 登录窗口：输入服务器地址、端口、昵称，连接成功后打开聊天主窗口。
 */
public class LoginFrame extends JFrame {

    private final JTextField ipField = new JTextField("127.0.0.1", 14);
    private final JTextField portField = new JTextField(String.valueOf(Protocol.DEFAULT_PORT), 14);
    private final JTextField nameField = new JTextField(14);
    private final JButton loginButton = new JButton("登录");

    public LoginFrame() {
        super("登录 - 局域网聊天室");
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 8, 6, 8);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = 0;
        panel.add(new JLabel("服务器地址："), c);
        c.gridy = 1;
        panel.add(new JLabel("端口："), c);
        c.gridy = 2;
        panel.add(new JLabel("昵称："), c);

        c.gridx = 1;
        c.gridy = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(ipField, c);
        c.gridy = 1;
        panel.add(portField, c);
        c.gridy = 2;
        panel.add(nameField, c);

        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.CENTER;
        panel.add(loginButton, c);
        add(panel);

        loginButton.addActionListener(e -> doLogin());
        nameField.addActionListener(e -> doLogin());
        getRootPane().setDefaultButton(loginButton);

        pack();
        setLocationRelativeTo(null);
    }

    private void doLogin() {
        String host = ipField.getText().trim();
        String name = nameField.getText();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "端口必须是数字", "输入错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String err = Protocol.validateName(name);
        if (err != null) {
            JOptionPane.showMessageDialog(this, err, "昵称不合法", JOptionPane.ERROR_MESSAGE);
            return;
        }

        loginButton.setEnabled(false);
        // 网络连接放到后台线程，避免阻塞 EDT 导致界面卡死
        new Thread(() -> {
            ClientConnector connector = new ClientConnector();
            ChatFrame chatFrame = new ChatFrame(connector, name.trim());
            try {
                connector.connect(host, port, name.trim(), chatFrame, true);
                SwingUtilities.invokeLater(() -> {
                    chatFrame.setVisible(true);
                    dispose();
                });
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    chatFrame.dispose();
                    JOptionPane.showMessageDialog(LoginFrame.this,
                            "连接失败：" + e.getMessage(), "登录失败", JOptionPane.ERROR_MESSAGE);
                    loginButton.setEnabled(true);
                });
            }
        }, "login-thread").start();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new LoginFrame().setVisible(true));
    }
}
