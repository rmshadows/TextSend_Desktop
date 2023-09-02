package application;

import ScheduleTask.ScheduleTask;
import utils.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.File;
import java.io.IOException;
import java.net.*;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * TextSend Ryan Yim Java Swing
 */
public class TextSendMain {
    final public static String VERSION = "4.0.2";
    // Server
    // 服务器消息自带的ID
    final public static String SERVER_ID = "-200";
    // 服务器成功接收的反馈信息
    final public static String FB_MSG = "cn.rmshadows.TextSend.ServerStatusFeedback";
    // 服务运行的端口号
    private static String serverListenPort;
    // 猜测的IP地址
    private static String preferIpAddr;
    // 服务端服务是否正在运行
    private static boolean serverRunning = false;
    // 客户端最大链接数量
    public static int maxConnection = 1;

    // Client
    // 作为客户端使用时，生成的Socket
    private static Socket clientSocket = null;
    // 客户端是否连接
    public static boolean isClientConnected = false;
    // 计时器停止信号
    public static AtomicBoolean scheduleControl = new AtomicBoolean(false);

    // Public
    // 单个Msg拆分的长度
    final public static int MSG_LEN = 1000;
    // 加密用的Token
    final public static String AES_TOKEN = "cn.rmshadows.TS_TOKEN";
    // 是否是服务界面
    private static boolean isServerMode = true;
    // 网卡IP地址
    private static LinkedList<String> netIps = new LinkedList<>();

    // 下面是Swing界面组件
    private static JFrame frame;
    private static JPanel panelForTextField;
    private static JPanel panelForButtons;
    private static JButton buttonStart;
    private static JButton buttonMode;
    private static JScrollPane scrollPane;
    private static JTextArea textArea;
    private static JComboBox<String> stringJComboBoxIps;

    /*
    Getter & Setter
     */
    public static int getServerListenPort() {
        return Integer.parseInt(serverListenPort);
    }

    public static boolean isServerRunning() {
        return serverRunning;
    }

    public static void setServerRunning(boolean serverRunning) {
        TextSendMain.serverRunning = serverRunning;
    }

    /**
     * 客户端界面
     */
    private static void textSendClient() {
        System.out.println("Enter the client graphical user interface.");
        JFrame.setDefaultLookAndFeelDecorated(true);
        // Init
        frame = new JFrame();
        panelForTextField = new JPanel();
        panelForButtons = new JPanel();
        buttonStart = new JButton();
        buttonMode = new JButton();
        scrollPane = new JScrollPane();
        textArea = new JTextArea();

        // frame
        frame.setTitle("Text Send PC Client - " + VERSION);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setIconImage(null);
        frame.setSize(400, 300);
        frame.setLayout(null);
        frame.setResizable(false);
        frame.setAlwaysOnTop(true);
        frame.setLocationRelativeTo(null);// 居中显示

        // button
        buttonStart.setText("连接");
        buttonMode.setText("切换");
        buttonStart.setBounds(50, 3, 90, 35);
        buttonMode.setBounds(250, 3, 90, 35);

        // textArea
        // 设置字体
        textArea.setFont(new Font(null, Font.PLAIN, 20));
        // 设置自动换行
        textArea.setLineWrap(true);

        // scrollPane
        scrollPane.setViewportView(textArea);
        scrollPane.setBounds(1, 1, 388, 223);

        // Panel
        panelForTextField.setLayout(null);
        panelForButtons.setLayout(null);
        panelForTextField.setBounds(0, 0, 390, 225);
        panelForButtons.setBounds(0, 225, 390, 48);
        panelForTextField.add(scrollPane);
        panelForButtons.add(buttonStart);
        panelForButtons.add(buttonMode);
        frame.add(panelForTextField);
        frame.add(panelForButtons);

        /*
          连接按钮事件
         */
        buttonStart.addActionListener(e -> {
            if (isClientConnected) {
                // 已连接则断开
                stopClient();
            } else { // 如果未连接，连接
                startClient();
            }
        });

        /*
         * 发送按钮事件
         */
        buttonMode.addActionListener(e -> {
            // 如果服务端运行，则发送信息，否则 切换模式
            if (isClientConnected) {
                sendMessage();
            } else {
                switchMode(true);
            }
        });

        frame.setVisible(true);
    }

    /**
     * 启动客户端
     */
    private static void startClient() {
        // 先设置界面
        buttonMode.setText("发送");
        buttonStart.setText("断开");
        // 连接
        try {
            String serverIpAddr;
            int port = 54300;
            // 如果有IP:端口
            if (textArea.getText().contains(":")) {
                String[] connection = textArea.getText().split(":");
                serverIpAddr = connection[0];
                port = Integer.parseInt(connection[1]);
            } else {
                serverIpAddr = textArea.getText();
            }
            System.out.println("地址：" + serverIpAddr + "       端口：" + port);
            // 有一定机率卡死（服务端未回复时），所以替换成下一句
//            clientSocket = new Socket(serverIpAddr, port);
            clientSocket = new Socket();
            clientSocket.connect(new InetSocketAddress(serverIpAddr, port), 5000);
            // 如果连接成功
            isClientConnected = true;
            new Thread(new ClientMessageController(clientSocket)).start();
            // 开始监视连接状况
            scheduleControl.set(true);
            // 监视连接断开就恢复按钮状态
            new Thread(() -> {
                // 周期任务
                Runnable checkConnection = () -> {
                    // 如果断开连接
                    if (!isClientConnected) {
                        stopClient();
                    }
                };
                // 运行周期任务，并在clientconnect未false时停止
                ScheduleTask scheduleTask = new ScheduleTask(checkConnection, 1, 1, scheduleControl, SECONDS);
                scheduleTask.startTask();
            }).start();
        } catch (Exception e) {
            // 连接失败
            e.printStackTrace();
            System.out.println("客户端连接失败。");
            stopClient();
        }
    }

    /**
     * 停止客户端
     */
    private static void stopClient() {
        isClientConnected = false;
        scheduleControl.set(false);
        buttonStart.setText("连接");
        buttonMode.setText("切换");
        try {
            clientSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 服务端界面
     */
    private static void textSendServer() {
        System.out.println("Enter the server graphical user interface.");
        // Swing setup
        JFrame.setDefaultLookAndFeelDecorated(true);
        // Init
        frame = new JFrame();
        panelForTextField = new JPanel();
        panelForButtons = new JPanel();
        buttonStart = new JButton();
        buttonMode = new JButton();
        scrollPane = new JScrollPane();
        textArea = new JTextArea();
        stringJComboBoxIps = new JComboBox<>();

        // ComboBox
        for (String ip : netIps) {
            // 添加IP
            stringJComboBoxIps.addItem(ip);
        }
        // 选择默认IP
        stringJComboBoxIps.setSelectedItem(preferIpAddr.split(":")[0]);
        stringJComboBoxIps.setBounds(0, 0, 122, 30);

        // Frame
        frame.setTitle("Server");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(130, 90);
        frame.setLayout(null);

        // Button
        buttonStart.setText("启动");
        buttonMode.setText("切换");

        // textArea
        // 设置字体
        // textArea.setFont(new Font(null, 0, 20));
        // 设置自动换行
        textArea.setLineWrap(true);
        textArea.setBounds(0, 0, 122, 30);

        // scrollPane
        scrollPane.setViewportView(textArea);
        scrollPane.setBounds(1, 1, 388, 223);
        scrollPane.setBounds(0, 0, 122, 30);

        buttonStart.setBounds(0, 0, 60, 30);
        buttonMode.setBounds(60, 0, 60, 30);

        // panel
        panelForTextField.setLayout(null);
        panelForButtons.setLayout(null);
        panelForTextField.add(stringJComboBoxIps);
        panelForButtons.add(buttonStart);
        panelForButtons.add(buttonMode);
        panelForTextField.setBounds(0, 0, 120, 30);
        panelForButtons.setBounds(0, 30, 120, 30);

        frame.add(panelForTextField);
        frame.add(panelForButtons);
        frame.setAlwaysOnTop(true);
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);// 居中显示

        /*
         * 启动按钮时事件
         */
        buttonStart.addMouseListener(new MouseListener() {
            @Override
            public void mouseClicked(MouseEvent e) {
                /*
                 鼠标左键 启动浏览器生成二维码
                 中间支持多开（最多7个）
                 其他按键（右键）支持一个 不启动浏览器
                 */
                boolean showQrImg = false;
                if (e.getButton() == 1) {
                    maxConnection = 1;
                    showQrImg = true;
                } else if (e.getButton() == 2) { // 鼠标中键支持多连
                    maxConnection = 7;
                    System.out.println("服务端多客户端模式，支持7个客户端");
                } else {
                    maxConnection = 1;
                }
                if (isServerRunning()) {
                    // 断开
                    stopServer();
                } else {
                    startServer(showQrImg);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
            }

            @Override
            public void mouseReleased(MouseEvent e) {
            }

            @Override
            public void mouseEntered(MouseEvent e) {
            }

            @Override
            public void mouseExited(MouseEvent e) {
            }
        });

        /*
         * 发送按钮事件
         */
        buttonMode.addActionListener(e -> {
            // 如果服务端运行，则发送信息，否则 切换模式
            if (isServerRunning()) {
                sendMessage();
            } else {
                switchMode(false);
            }
        });

        // 显示放最后
        frame.setVisible(true);
    }

    /**
     * 启动服务端
     *
     * @param showQrImg 是否显示二维码图片
     */
    private static void startServer(boolean showQrImg) {
        // 替换组件
        panelForTextField.remove(stringJComboBoxIps);
        panelForTextField.add(scrollPane);
        buttonStart.setText("停止");
        buttonMode.setText("发送");
        setServerRunning(true);
        // 打开二维码图片
        if (showQrImg) {
            String selected_ip = (String) stringJComboBoxIps.getSelectedItem();
            // 生成二维码 启动默认浏览器
            File QR_file = QR_Util.serverQRCode(String.format("%s:%d", selected_ip, getServerListenPort()));
            QR_file.deleteOnExit();
            // 用浏览器打开
            // 解决Windows下的兼容问题
            String qr_url = QR_file.getAbsolutePath().replace("\\", "/");
            // 转义空格
            qr_url = qr_url.replace(" ", "%20");
            // 打开二维码
            browserOpenUrl(String.format("file:///%s", qr_url));
        }
        new Thread(new SocketDeliver()).start();
    }

    /**
     * 外部设置按钮文字
     * @param count 值
     */
    public static void setClientCount(int count){
        buttonMode.setText("("+count+")");
    }

    /**
     * 停止服务
     */
    public static void stopServer() {
        panelForTextField.remove(scrollPane);
        panelForTextField.add(stringJComboBoxIps);
        buttonStart.setText("启动");
        buttonMode.setText("切换");
        setServerRunning(false);
        SocketDeliver.stopSocketDeliver();
    }

    /**
     * 发送信息的方法
     */
    private static void sendMessage() {
        if (isServerMode) {
            Message m = new Message(SERVER_ID, textArea.getText(), MSG_LEN, null);
            // 这个是发送给所有客户端
            SocketDeliver.sendMessageToAllClients(m);
        } else {
            ClientMessageController.sendMessageToServer(new Message(ClientMessageController.clientId, textArea.getText(), MSG_LEN, null));
        }
    }


    /**
     * 切换模式
     *
     * @param switchToServerMode 是否切换到服务端模式
     */
    private static void switchMode(boolean switchToServerMode) {
        frame.setVisible(false);
        isServerMode = switchToServerMode;
        if (switchToServerMode) {
            textSendServer();
        } else {
            textSendClient();
        }
    }

    /**
     * 清空文本框中的文字
     */
    public static void cleanTextArea() {
        if(!Objects.equals(textArea.getText(), "")){
            textArea.setText("");
        }
    }

    /**
     * 打印网卡IP并返回可能的局域网IP
     */
    public static String getIP() throws SocketException {
        // get all local ips
        LinkedList<String> ip_addr = new LinkedList<>();
        Enumeration<NetworkInterface> interfs = NetworkInterface.getNetworkInterfaces();
        System.out.println("正在獲取电脑本地IP....");
        int n = 1;
        boolean getStatus = false;
        while (interfs.hasMoreElements()) {
            NetworkInterface interf = interfs.nextElement();
            Enumeration<InetAddress> addres = interf.getInetAddresses();
            if (n == 1 | getStatus) {
                System.out.println("<------第" + n + "组网卡------>");
                getStatus = false;
            }
            while (addres.hasMoreElements()) {
                InetAddress in = addres.nextElement();
                if (in instanceof Inet4Address) {
                    System.out.println(" - IPv4地址:" + in.getHostAddress());
                    ip_addr.add(in.getHostAddress());
                    getStatus = true;
                } else if (in instanceof Inet6Address) {
                    System.out.println(" - IPv6地址:" + in.getHostAddress());
                    // 不要IPv6
//					ip_addr.add(in.getHostAddress());
                    getStatus = true;
                }
            }
            if (getStatus) {
                n += 1;
            }
        }
        String prefer_ip = null;
        boolean find172 = false;
        netIps = ip_addr;

        for (String ip : ip_addr) {
            try {
                if (ip.startsWith("192.168")) {
                    prefer_ip = ip;
                    break;
                } else if (ip.startsWith("172.")) {
                    prefer_ip = ip;
                    find172 = true;
                } else if (ip.startsWith("10.")) {
                    if (!find172) {
                        prefer_ip = ip;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        System.out.println("<--没有第" + n + "组网卡，如果以上结果没有显示出你所在局域网的IP地址。请手动查看您的IPv4地址谢谢-->\n");
        System.out.println("请在您的TextSend安卓客户端中输入手机与电脑同在的局域网的IPv4地址(不出问题的话上面应该有你需要的IP)。");
        if (prefer_ip != null) {
            if (prefer_ip.startsWith("192.168")) {
                System.out.printf("猜测您当前的局域网IP是：%s ，具体请根据实际情况进行选择。%n", prefer_ip);
            } else {
                System.out.printf("未能猜测到您当前的局域网IP，将使用：%s 作为启动二维码地址！具体可将实际IP填入文本框后启动!%n", prefer_ip);
            }
        }

        try {
            System.out.println("\n正在初始化...");
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.err.println("\n======>>>>>> 好的，准备就绪！现在，请点击“启动”按钮！<<<<<<======");
        return prefer_ip;
    }

    /**
     * 启动程序时弹窗 设置服务端口
     */
    static void setPortOnStartup() {
        GetPort g = new GetPort();
        Thread th = new Thread(g);
        th.start();
        try {
            // 超时10秒
            th.join(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
            System.exit(1);
        } finally {
            th.interrupt();
        }
        serverListenPort = g.getPort();
        System.err.println("设置服务端口：" + serverListenPort);
    }

    /**
     * 使用默认浏览器打开
     *
     * @param url 要打开的网址
     */
    private static void browserOpenUrl(String url) {
        System.out.println("Log: 尝试用浏览器显示二维码...");
        Desktop desktop = Desktop.getDesktop();
        if (Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.BROWSE)) {
            URI uri;
            try {
                uri = new URI(url);
                desktop.browse(uri);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * main方法
     */
    public static void main(String[] args) throws SocketException {
        // 弹窗设置监听端口
        setPortOnStartup();
        // 猜测局域网IP
        preferIpAddr = getIP() + ":" + serverListenPort;
        // lambda 可被替换为方法引用 javax.swing.SwingUtilities.invokeLater(() -> textSendServer());
        javax.swing.SwingUtilities.invokeLater(TextSendMain::textSendServer);
    }
}

/**
 * 设置端口的对话框
 *
 * @author jessie
 */
class GetPort implements Runnable {
    private String port;

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    @Override
    public void run() {
        String selectPort;
        boolean showing = true;
        while (showing) {
            try {
                selectPort = JOptionPane.showInputDialog("请选择服务端口，默认54300端口(未确认将视为退出程序)");
                if (selectPort == null) {
                    // 点击取消
                    setPort("54300");
                    throw new IOException("用户取消");
                } else if (selectPort.equals("")) {
                    // 直接回车
                    setPort("54300");
                    showing = false;
                } else {
                    try {
                        Integer.valueOf(selectPort);
                    } catch (NumberFormatException e) {
                        JOptionPane.showMessageDialog(null, "请输入数字!", "错误", JOptionPane.INFORMATION_MESSAGE);
                        continue;
                    }
                    setPort(selectPort);
                    showing = false;
                }
            } catch (Exception e) {
                System.err.println("退出程序");
                System.exit(0);
            }
        }
    }
}
