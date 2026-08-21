package application;

import ScheduleTask.ScheduleTask;
import protocol.FileIoCallback;
import protocol.FileNames;
import protocol.PairingMaterial;
import protocol.PasteUtil;
import protocol.Protocol;
import protocol.TsPeer;
import protocol.TsServer;
import protocol.TsUri;
import utils.QR_Util;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * TextSend Desktop — 协议 v1
 * 置顶 = 紧凑小窗；非置顶 = 大 UI（单击复制连接串，双击复制 PIN/密钥）。
 */
public class TextSendMain {
    public static final String VERSION = "5.0.42";

    @Deprecated public static final String SERVER_ID = "-200";
    @Deprecated public static final String FB_MSG = "cn.rmshadows.TextSend.ServerStatusFeedback";
    @Deprecated public static final int MSG_LEN = 1000;
    @Deprecated public static final String AES_TOKEN = "cn.rmshadows.TS_TOKEN";
    @Deprecated public static final byte[] endMarker = "▓⒣".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** 简洁浅色主题：深色字 + 浅底 */
    private static final Color BG = new Color(0xF0F0F0);
    private static final Color CARD = Color.WHITE;
    private static final Color TEXT = new Color(0x1A1A1A);
    private static final Color MUTED = new Color(0x444444);
    private static final Color BORDER = new Color(0xB0B0B0);

    private static String serverListenPort = String.valueOf(Protocol.DEFAULT_PORT);
    private static String preferIpAddr;
    private static boolean serverRunning = false;
    public static int maxConnection = 1;
    private static PairingMaterial pairingMaterial;
    private static String lastConnectUri = "";

    private static Socket clientSocket;
    private static volatile TsPeer clientPeer;
    public static boolean isClientConnected = false;
    private static boolean clientHandshaking = false;
    private static volatile boolean clientConnectAbort = false;
    private static volatile boolean sending = false;
    private static volatile boolean fileBusy = false;
    private static volatile boolean transferUi = false;
    private static final AtomicBoolean cancelFileSend = new AtomicBoolean(false);
    private static final Object sendLock = new Object();
    /** 正在发送队列（可在发送中追加）。 */
    private static final List<File> sendRemaining = new ArrayList<>();
    private static volatile File currentSendingFile;
    /** 本轮已成功发出的个数，按文件夹名（扁文件用空串）分组，用来显示 3/40 */
    private static final Map<String, Integer> sendDoneByTree = new HashMap<>();
    private static final Object progressLock = new Object();
    private static boolean progressPosted;
    private static boolean progressIncoming;
    private static String progressName = "";
    private static long progressDone;
    private static long progressTotal;
    private static int progressSeq;
    private static int progressOf;
    private static String speedKey = "";
    private static long speedMarkNs;
    private static long speedMarkBytes;
    private static double speedBps;
    private static final FileIoCallback FILE_IO = new FileIoCallback() {
        @Override
        public void onProgress(boolean incoming, String name, long done, long total, int seq, int of) {
            long now = System.nanoTime();
            int showSeq = seq;
            int showOf = of;
            if (!incoming) {
                synchronized (sendLock) {
                    File cur = currentSendingFile;
                    if (cur != null) {
                        int[] so = sendSeqOfLocked(cur);
                        showSeq = so[0];
                        showOf = so[1];
                    }
                }
            }
            synchronized (progressLock) {
                String key = (incoming ? "in:" : "out:") + (name == null ? "" : name);
                if (!key.equals(speedKey) || done < speedMarkBytes) {
                    speedKey = key;
                    speedMarkNs = now;
                    speedMarkBytes = done;
                    speedBps = 0;
                } else if (now - speedMarkNs >= 400_000_000L) {
                    long dt = now - speedMarkNs;
                    speedBps = (done - speedMarkBytes) * 1_000_000_000.0 / dt;
                    speedMarkNs = now;
                    speedMarkBytes = done;
                }
                progressIncoming = incoming;
                progressName = name == null ? "" : name;
                progressDone = done;
                progressTotal = total;
                progressSeq = showSeq;
                progressOf = showOf;
                if (progressPosted) {
                    return;
                }
                progressPosted = true;
            }
            SwingUtilities.invokeLater(TextSendMain::flushProgressUi);
        }

        @Override
        public void onReceived(String name, String where) {
            SwingUtilities.invokeLater(() -> {
                endTransferUi();
                if ("剪贴板".equals(where)) {
                    flashStatus("已放入剪贴板：" + name);
                } else {
                    flashStatus("已保存 " + name + " → 下载/" + FileNames.FOLDER);
                }
            });
        }

        @Override
        public void onReceiveFailed(String name, String err) {
            SwingUtilities.invokeLater(() -> {
                endTransferUi();
                flashStatus("接收失败 " + name + "：" + err);
            });
        }
    };
    public static AtomicBoolean scheduleControl = new AtomicBoolean(false);

    private static boolean isServerMode = true;
    private static boolean miniMode = false;
    private static LinkedList<String> netIps = new LinkedList<>();
    private static int connectedClients = 0;
    private static boolean inputShowingCount = false;
    /** 小窗共用区正显示 PIN（点复制；有连接后清空改输入） */
    private static boolean miniShowingPin = false;

    private static JFrame frame;
    private static JPanel root;
    private static JComboBox<String> comboIps;
    private static JTextField fieldPort;
    private static JToggleButton toggleMini;
    private static JButton buttonStart;
    private static JButton buttonRole;
    private static JButton buttonSend;
    private static JButton buttonFile;
    private static JButton buttonQr;
    private static JButton buttonHelp;
    private static JLabel labelStatus;
    private static JPanel statusBar;
    private static JProgressBar progressBar;
    private static JButton buttonCancel;
    private static JLabel labelScale;
    /** 小窗唯一输入区：未启动=IP / 启动=PIN / 有连接或客户端=消息·连接串（对齐你原版 textArea） */
    private static JTextArea miniTextArea;
    private static JScrollPane miniScroll;
    private static JPanel miniPanelText;
    private static JPanel miniPanelBtns;
    private static JTextArea areaMessage;
    private static JScrollPane scrollMessage;
    private static UndoManager undoMessage;
    private static UndoManager undoMini;
    private static final List<File> fileQueue = new ArrayList<>();
    /** fileKey → 文件夹名（隐藏发树时） */
    private static final Map<String, String> queueTree = new HashMap<>();
    /** fileKey → 树内相对路径 */
    private static final Map<String, String> queueRel = new HashMap<>();
    private static File pastePreviewFile;
    private static BufferedImage pastePreviewImage;
    private static JPanel pastePreview;
    private static JLabel pastePreviewThumb;
    private static JLabel pastePreviewText;
    private static JDialog pastePreviewDialog;
    private static JCheckBox checkFollowLinks;
    private static final DefaultTableModel fileModel = new DefaultTableModel(
            new Object[]{"文件名", "所在路径", "大小"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private static JTable fileTable;
    private static JScrollPane scrollFiles;
    private static JPanel filePanel;
    private static JLabel labelFileQueue;
    private static JButton buttonBrowse;
    private static JButton buttonRemoveFiles;
    private static File lastFileChooserDir;
    /** 待发列表默认收起，点标题或把文件拖进输入框再展开 */
    private static boolean fileListExpanded = false;

    private static JPanel cardPin;
    private static JPanel cardKey;
    private static JLabel valuePin;
    private static JLabel valueKey;
    private static JLabel hintPin;
    private static JLabel hintKey;

    private static Point lastFrameLocation;
    private static Timer startLongPressTimer;
    private static boolean startLongPressTriggered;
    private static Timer scaleRebuildTimer;
    private static Timer statusFlashTimer;
    private static JDialog qrDialog;

    public static int getServerListenPort() {
        return Integer.parseInt(serverListenPort);
    }

    public static boolean isServerRunning() {
        return serverRunning;
    }

    public static void setServerRunning(boolean v) {
        serverRunning = v;
    }

    public static void main(String[] args) throws SocketException {
        UserConfig.load();
        serverListenPort = String.valueOf(UserConfig.getListenPort());
        miniMode = UserConfig.isPreferMini();
        preferIpAddr = getIP() + ":" + serverListenPort;
        SwingUtilities.invokeLater(() -> {
            applyLookAndFeelForMode();
            buildUi(true);
        });
    }

    /**
     * 小窗 = Metal 细标题栏（蓝框）；setUndecorated 只去掉窗口管理器那条系统标题栏。
     * 大窗 = 系统 L&F。切模式必须先换 L&F 再重建组件。
     */
    private static void applyLookAndFeelForMode() {
        try {
            if (miniMode) {
                MetalLookAndFeel.setCurrentTheme(new DefaultMetalTheme());
                UIManager.setLookAndFeel(new MetalLookAndFeel());
                JFrame.setDefaultLookAndFeelDecorated(true);
            } else {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                JFrame.setDefaultLookAndFeelDecorated(false);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void buildUi(boolean server) {
        isServerMode = server;
        Point loc = frame != null ? frame.getLocation() : null;
        closeQrDialog();
        if (frame != null) {
            frame.dispose();
            frame = null;
        }

        comboIps = new JComboBox<>();
        for (String ip : netIps) {
            comboIps.addItem(ip);
        }
        if (preferIpAddr != null) {
            comboIps.setSelectedItem(preferIpAddr.split(":")[0]);
        }
        fieldPort = new JTextField(serverListenPort, 5);
        styleField(fieldPort);

        toggleMini = new JToggleButton("小窗");
        toggleMini.setSelected(miniMode);
        toggleMini.setToolTipText("置顶小窗");
        toggleMini.addActionListener(e -> setMiniMode(toggleMini.isSelected()));

        labelScale = new JLabel(formatScale());
        labelScale.setForeground(TEXT);
        labelScale.setOpaque(true);
        labelScale.setBackground(CARD);
        labelScale.setBorder(new EmptyBorder(2, 8, 2, 8));
        labelScale.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        labelScale.setToolTipText("鼠标放在这里滚轮缩放；Ctrl+滚轮细调 0.1%");
        installScaleWheel(labelScale);

        buttonStart = new JButton(server ? "启动" : "连接");
        buttonRole = new JButton(server ? "客户端" : "服务端");
        buttonSend = new JButton("发送");
        buttonFile = new JButton("文件");
        buttonQr = new JButton("二维码");
        buttonHelp = new JButton("帮助");
        buttonHelp.addActionListener(e -> showHelp());

        labelStatus = new JLabel(" ");
        labelStatus.setForeground(MUTED);
        progressBar = new JProgressBar(0, 1000);
        progressBar.setVisible(false);
        progressBar.setStringPainted(false);
        buttonCancel = new JButton("取消");
        buttonCancel.setVisible(false);
        buttonCancel.setToolTipText("取消当前传输");
        buttonCancel.addActionListener(e -> cancelTransfer());
        statusBar = new JPanel(new BorderLayout(8, 0));
        statusBar.setOpaque(false);
        statusBar.add(progressBar, BorderLayout.WEST);
        statusBar.add(labelStatus, BorderLayout.CENTER);
        statusBar.add(buttonCancel, BorderLayout.EAST);

        miniTextArea = new JTextArea();
        miniTextArea.setLineWrap(true);
        undoMini = attachUndo(miniTextArea);
        miniScroll = new JScrollPane(miniTextArea);
        miniScroll.setBounds(0, 0, 122, 30);
        miniTextArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!miniShowingPin) {
                    return;
                }
                if (lastConnectUri != null && !lastConnectUri.isEmpty()) {
                    copyText(lastConnectUri);
                    flashStatus("已复制连接串");
                } else if (pairingMaterial != null) {
                    String ip = (String) comboIps.getSelectedItem();
                    copyText(pairingMaterial.toUri(ip, getServerListenPort()));
                    flashStatus("已复制连接串");
                }
            }
        });

        areaMessage = new JTextArea();
        areaMessage.setLineWrap(true);
        areaMessage.setWrapStyleWord(true);
        areaMessage.setFont(areaMessage.getFont().deriveFont((float) UserConfig.s(14)));
        areaMessage.setBorder(new EmptyBorder(UserConfig.s(8), UserConfig.s(8), UserConfig.s(8), UserConfig.s(8)));
        undoMessage = attachUndo(areaMessage);
        scrollMessage = new JScrollPane(areaMessage);
        scrollMessage.setBorder(BorderFactory.createLineBorder(BORDER));

        valuePin = new JLabel("—");
        valueKey = new JLabel("—");
        hintPin = new JLabel("单击复制 PIN 连接串 · 双击复制 PIN");
        hintKey = new JLabel("单击复制密钥连接串 · 双击复制密钥");
        cardPin = copyCard("PIN", valuePin, hintPin,
                () -> pairingMaterial == null ? null : pairingMaterial.toPinUri(
                        (String) comboIps.getSelectedItem(), getServerListenPort()),
                () -> pairingMaterial == null ? null : pairingMaterial.pin);
        cardKey = copyCard("长密钥", valueKey, hintKey,
                () -> pairingMaterial == null ? null : pairingMaterial.toPskUri(
                        (String) comboIps.getSelectedItem(), getServerListenPort()),
                () -> pairingMaterial == null ? null : pairingMaterial.pskB64);

        buttonRole.addActionListener(e -> switchRole());
        buttonSend.addActionListener(e -> {
            if (miniMode) {
                onMiniSecondaryClick();
            } else {
                sendMessage();
            }
        });
        buttonFile.addActionListener(e -> pickAndAddFiles());
        buttonQr.addActionListener(e -> showQr());

        if (server) {
            wireServerStartButton();
        } else {
            buttonStart.addActionListener(e -> {
                if (isClientConnected || clientHandshaking) {
                    stopClient();
                } else {
                    startClient();
                }
            });
        }

        FocusAdapter clearCount = new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                clearCountPlaceholder();
            }
        };
        areaMessage.addFocusListener(clearCount);
        miniTextArea.addFocusListener(clearCount);
        DocumentListener touch = new DocumentListener() {
            private void t() {
                if (inputShowingCount) {
                    inputShowingCount = false;
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                t();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                t();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                t();
            }
        };
        areaMessage.getDocument().addDocumentListener(touch);
        miniTextArea.getDocument().addDocumentListener(touch);

        rebuildFrameShell(loc);
        if (!frame.isVisible()) {
            frame.setVisible(true);
        }
    }

    /** 小窗副按钮：空闲=切换角色，运行中=发送（旧版复用） */
    private static void onMiniSecondaryClick() {
        boolean active = isServerMode ? isServerRunning() : (isClientConnected || clientHandshaking);
        if (active) {
            sendMessage();
        } else {
            switchRole();
        }
    }

    private static void bindEscapeExitMini(JComponent c) {
        c.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "exitMini");
        c.getActionMap().put("exitMini", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (miniMode) {
                    setMiniMode(false);
                }
            }
        });
    }

    private static void bindAppShortcuts(JFrame f) {
        JRootPane rp = f.getRootPane();
        InputMap im = rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = rp.getActionMap();
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        int[] masks = {menu, InputEvent.CTRL_DOWN_MASK, InputEvent.META_DOWN_MASK};

        am.put("ts-quit", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.exit(0);
            }
        });
        am.put("ts-send", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendMessage();
            }
        });
        am.put("ts-file", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                pickAndAddFiles();
            }
        });
        am.put("ts-help", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showHelp();
            }
        });

        for (int mask : masks) {
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, mask | InputEvent.SHIFT_DOWN_MASK), "ts-quit");
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Q, mask), "ts-quit");
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, mask), "ts-send");
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_O, mask), "ts-file");
        }
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), "ts-help");
    }

    private static void switchRole() {
        if (isServerRunning() || isClientConnected) {
            JOptionPane.showMessageDialog(frame, "请先停止服务或断开连接，再切换角色", "提示",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        // 小窗内切换保持小窗；固定尺寸布局，避免变形
        boolean keepMini = miniMode;
        buildUi(!isServerMode);
        if (keepMini) {
            miniMode = true;
            UserConfig.setPreferMini(true);
            if (toggleMini != null) {
                toggleMini.setSelected(true);
            }
        }
    }

    private static JPanel copyCard(String title, JLabel value, JLabel hint,
                                  Supplier<String> linkSupplier, Supplier<String> secretSupplier) {
        JPanel card = new JPanel(new BorderLayout(4, 2));
        card.setBackground(CARD);
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        String hintIdle = "PIN".equals(title)
                ? "单击复制 PIN 连接串 · 双击复制 PIN"
                : "单击复制密钥连接串 · 双击复制密钥";
        card.setToolTipText(hintIdle);

        JLabel t = new JLabel(title);
        t.setForeground(MUTED);
        t.setFont(t.getFont().deriveFont(Font.PLAIN, (float) UserConfig.s(11)));
        value.setForeground(TEXT);
        value.setVerticalAlignment(SwingConstants.TOP);
        value.setFont(value.getFont().deriveFont(Font.PLAIN, (float) UserConfig.s(13)));
        hint.setForeground(MUTED);
        hint.setFont(hint.getFont().deriveFont((float) UserConfig.s(10)));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(UserConfig.s(6), UserConfig.s(8), UserConfig.s(6), UserConfig.s(8))));

        card.add(t, BorderLayout.NORTH);
        card.add(value, BorderLayout.CENTER);
        card.add(hint, BorderLayout.SOUTH);

        final Timer[] pendingSingle = new Timer[1];
        MouseAdapter click = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                if (e.getClickCount() >= 2) {
                    if (pendingSingle[0] != null) {
                        pendingSingle[0].stop();
                        pendingSingle[0] = null;
                    }
                    String secret = secretSupplier.get();
                    if (secret == null || secret.isBlank() || "—".equals(secret)) {
                        flashStatus("暂无内容可复制");
                        return;
                    }
                    copyText(secret);
                    flashStatus("已复制 " + title);
                    hint.setText("已复制 " + title);
                    Timer tmr = new Timer(1200, ev -> hint.setText(hintIdle));
                    tmr.setRepeats(false);
                    tmr.start();
                    return;
                }
                if (e.getClickCount() == 1) {
                    if (pendingSingle[0] != null) {
                        pendingSingle[0].stop();
                    }
                    pendingSingle[0] = new Timer(280, ev -> {
                        pendingSingle[0] = null;
                        String uri = linkSupplier.get();
                        if (uri == null || uri.isBlank()) {
                            flashStatus("请先启动服务");
                            return;
                        }
                        copyText(uri);
                        flashStatus("已复制连接串");
                        hint.setText("已复制连接串");
                        Timer tmr = new Timer(1200, x -> hint.setText(hintIdle));
                        tmr.setRepeats(false);
                        tmr.start();
                    });
                    pendingSingle[0].setRepeats(false);
                    pendingSingle[0].start();
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                card.setBackground(new Color(0xE8E8E8));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                card.setBackground(CARD);
            }
        };
        card.addMouseListener(click);
        value.addMouseListener(click);
        hint.addMouseListener(click);
        t.addMouseListener(click);
        return card;
    }

    private static void styleField(JTextField f) {
        f.setForeground(TEXT);
        f.setBackground(Color.WHITE);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(4, 8, 4, 8)));
    }

    private static void wireServerStartButton() {
        for (var l : buttonStart.getMouseListeners()) {
            buttonStart.removeMouseListener(l);
        }
        for (var l : buttonStart.getActionListeners()) {
            buttonStart.removeActionListener(l);
        }
        buttonStart.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (startLongPressTriggered) {
                    return;
                }
                maxConnection = e.getButton() == MouseEvent.BUTTON2 ? 7 : 1;
                if (isServerRunning()) {
                    stopServer();
                } else {
                    if (!miniMode) {
                        syncPortFromField();
                    }
                    startServer(e.getButton() == MouseEvent.BUTTON1);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                startLongPressTriggered = false;
                if (e.getButton() != MouseEvent.BUTTON1 || isServerRunning() || miniMode) {
                    return;
                }
                if (startLongPressTimer != null) {
                    startLongPressTimer.stop();
                }
                startLongPressTimer = new Timer(600, evt -> {
                    startLongPressTriggered = true;
                    fieldPort.requestFocusInWindow();
                    fieldPort.selectAll();
                    flashStatus("直接改端口号后启动即可");
                });
                startLongPressTimer.setRepeats(false);
                startLongPressTimer.start();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (startLongPressTimer != null) {
                    startLongPressTimer.stop();
                }
            }
        });
    }

    private static void setMiniMode(boolean mini) {
        if (miniMode == mini && frame != null && frame.isDisplayable()) {
            return;
        }
        Point loc = frame != null ? frame.getLocation() : null;
        miniMode = mini;
        UserConfig.setPreferMini(mini);
        // 必须先换 Metal/系统 L&F，再整窗重建，否则标题栏膨胀、按钮错位
        applyLookAndFeelForMode();
        buildUi(isServerMode);
        if (loc != null && frame != null) {
            frame.setLocation(loc);
        }
        if (toggleMini != null) {
            toggleMini.setSelected(miniMode);
        }
    }

    /** 清掉大 UI 强制尺寸；不要 setBorder(null)，会毁掉 Metal 边框导致按钮位移 */
    private static void resetPlainSwing(JComponent c) {
        c.setPreferredSize(null);
        c.setMinimumSize(null);
        c.setMaximumSize(null);
        c.updateUI();
    }

    /** 重建窗口壳（L&F 已在 setMiniMode/main 里设好） */
    private static void rebuildFrameShell(Point loc) {
        boolean wasVisible = frame != null && frame.isVisible();
        if (frame != null) {
            lastFrameLocation = frame.getLocation();
            frame.dispose();
        }
        frame = new JFrame();
        frame.setDefaultCloseOperation(miniMode ? JFrame.DO_NOTHING_ON_CLOSE : JFrame.EXIT_ON_CLOSE);
        // 小窗：不要窗口管理器的系统标题栏，只留 Metal 自己画的细标题栏
        frame.setUndecorated(miniMode);
        if (miniMode) {
            frame.getRootPane().setWindowDecorationStyle(JRootPane.FRAME);
        }
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (miniMode) {
                    setMiniMode(false);
                } else {
                    System.exit(0);
                }
            }
        });
        if (miniMode) {
            root = null;
            frame.setLayout(null);
        } else {
            root = new JPanel(new BorderLayout(8, 8));
            frame.setContentPane(root);
        }
        applyLayout();
        bindAppShortcuts(frame);
        Point p = loc != null ? loc : lastFrameLocation;
        if (p != null) {
            frame.setLocation(p);
        } else {
            frame.setLocationRelativeTo(null);
        }
        if (wasVisible || lastFrameLocation != null || loc != null) {
            frame.setVisible(true);
        }
        refreshRunningChrome();
    }
    /** 原版服务端小窗：130×90，上 combo/text，下 启动|切换 */
    private static void applyOriginalServerMini() {
        frame.getContentPane().removeAll();
        frame.setLayout(null);
        frame.setAlwaysOnTop(true);
        frame.setResizable(false);
        frame.setTitle("Server");
        frame.setSize(130, 90);

        resetPlainSwing(buttonStart);
        resetPlainSwing(buttonSend);
        resetPlainSwing(comboIps);
        resetPlainSwing(miniTextArea);
        resetPlainSwing(miniScroll);
        bindUndoKeys(miniTextArea, undoMini);

        miniPanelText = new JPanel(null);
        miniPanelBtns = new JPanel(null);
        miniPanelText.setLayout(null);
        miniPanelBtns.setLayout(null);
        miniPanelText.setBounds(0, 0, 120, 30);
        miniPanelBtns.setBounds(0, 30, 120, 30);

        comboIps.setBounds(0, 0, 122, 30);
        miniTextArea.setLineWrap(true);
        miniTextArea.setBounds(0, 0, 122, 30);
        miniScroll.setViewportView(miniTextArea);
        miniScroll.setBounds(0, 0, 122, 30);
        miniScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        miniScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);

        buttonStart.setBounds(0, 0, 60, 30);
        buttonSend.setBounds(60, 0, 60, 30);
        miniPanelBtns.add(buttonStart);
        miniPanelBtns.add(buttonSend);

        frame.add(miniPanelText);
        frame.add(miniPanelBtns);
        bindEscapeExitMini((JComponent) frame.getContentPane());
        installFileDrop(miniTextArea);
        installFileDrop((JComponent) frame.getContentPane());
        refreshMiniShared();
    }

    /** 原版客户端窗：400×300，大文本区 + 底栏连接/切换 */
    private static void applyOriginalClientMini() {
        frame.getContentPane().removeAll();
        frame.setLayout(null);
        frame.setAlwaysOnTop(true);
        frame.setResizable(false);
        frame.setTitle("Text Send PC Client - " + VERSION);
        frame.setSize(400, 300);

        resetPlainSwing(buttonStart);
        resetPlainSwing(buttonSend);
        resetPlainSwing(miniTextArea);
        resetPlainSwing(miniScroll);
        bindUndoKeys(miniTextArea, undoMini);

        miniPanelText = new JPanel(null);
        miniPanelBtns = new JPanel(null);
        miniPanelText.setLayout(null);
        miniPanelBtns.setLayout(null);
        miniPanelText.setBounds(0, 0, 390, 225);
        miniPanelBtns.setBounds(0, 225, 390, 48);

        miniTextArea.setFont(new Font(null, Font.PLAIN, 20));
        miniTextArea.setLineWrap(true);
        miniTextArea.setEditable(true);
        miniTextArea.setToolTipText(isClientConnected ? null : "粘贴 ts://… 后点连接");
        miniScroll.setViewportView(miniTextArea);
        miniScroll.setBounds(1, 1, 388, 223);
        miniScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        miniScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        miniPanelText.add(miniScroll);

        buttonStart.setBounds(50, 3, 90, 35);
        buttonSend.setBounds(250, 3, 90, 35);
        miniPanelBtns.add(buttonStart);
        miniPanelBtns.add(buttonSend);

        frame.add(miniPanelText);
        frame.add(miniPanelBtns);
        bindEscapeExitMini((JComponent) frame.getContentPane());
        installFileDrop(miniTextArea);
        miniShowingPin = false;
    }

    private static void applyLayout() {
        if (miniMode) {
            if (isServerMode) {
                applyOriginalServerMini();
            } else {
                applyOriginalClientMini();
            }
            return;
        }
        if (root == null) {
            root = new JPanel(new BorderLayout(8, 8));
            frame.setContentPane(root);
        }
        root.removeAll();
        frame.setAlwaysOnTop(false);
        frame.setResizable(true);

        {
            root.setLayout(new BorderLayout(8, 8));
            root.setBorder(new EmptyBorder(UserConfig.s(14), UserConfig.s(16), UserConfig.s(12), UserConfig.s(16)));
            root.setBackground(BG);
            frame.setTitle(isServerMode ? "TextSend 服务端  " + VERSION : "TextSend 客户端  " + VERSION);
            frame.setMinimumSize(new Dimension(UserConfig.s(640), UserConfig.s(560)));
            frame.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

            JPanel top = new JPanel(new BorderLayout(UserConfig.s(8), UserConfig.s(8)));
            top.setOpaque(false);
            JPanel titleBox = new JPanel(new FlowLayout(FlowLayout.LEFT, UserConfig.s(10), 0));
            titleBox.setOpaque(false);
            JLabel title = new JLabel(isServerMode ? "服务端" : "客户端");
            title.setFont(title.getFont().deriveFont(Font.BOLD, (float) UserConfig.s(20)));
            title.setForeground(TEXT);
            labelScale.setText(formatScale());
            labelScale.setFont(labelScale.getFont().deriveFont((float) UserConfig.s(12)));
            labelScale.setForeground(MUTED);
            titleBox.add(title);
            titleBox.add(labelScale);

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, UserConfig.s(6), 0));
            actions.setOpaque(false);
            toggleMini.setText("置顶小窗");
            fixToolbarButton(buttonHelp, 64);
            fixToolbarButton(toggleMini, 96);
            fixToolbarButton(buttonRole, 80);
            fixToolbarButton(buttonStart, 72);
            fixToolbarButton(buttonQr, 72);
            fixToolbarButton(buttonSend, 72);
            actions.add(buttonHelp);
            actions.add(toggleMini);
            actions.add(buttonRole);
            actions.add(buttonStart);
            actions.add(buttonQr);
            actions.add(buttonSend);
            top.add(titleBox, BorderLayout.WEST);
            top.add(actions, BorderLayout.EAST);

            JPanel body = new JPanel();
            body.setOpaque(false);
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

            // 仅服务端显示 IP/端口；客户端不需要
            if (isServerMode) {
                JPanel net = new JPanel(new BorderLayout(UserConfig.s(8), 0));
                net.setOpaque(false);
                net.setAlignmentX(Component.LEFT_ALIGNMENT);
                net.setMaximumSize(new Dimension(Integer.MAX_VALUE, UserConfig.s(34)));
                JPanel netLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, UserConfig.s(6), 0));
                netLeft.setOpaque(false);
                JLabel lip = new JLabel("监听");
                lip.setForeground(TEXT);
                lip.setFont(lip.getFont().deriveFont((float) UserConfig.s(13)));
                netLeft.add(lip);
                comboIps.setFont(comboIps.getFont().deriveFont((float) UserConfig.s(12)));
                fieldPort.setFont(fieldPort.getFont().deriveFont((float) UserConfig.s(13)));
                fieldPort.setForeground(TEXT);
                fieldPort.setPreferredSize(new Dimension(UserConfig.s(72), UserConfig.s(28)));
                fieldPort.setMinimumSize(new Dimension(UserConfig.s(72), UserConfig.s(28)));
                netLeft.add(comboIps);
                netLeft.add(new JLabel(":"));
                netLeft.add(fieldPort);
                net.add(netLeft, BorderLayout.CENTER);
                comboIps.setPreferredSize(new Dimension(UserConfig.s(420), UserConfig.s(28)));
                comboIps.setMinimumSize(new Dimension(UserConfig.s(280), UserConfig.s(28)));
                body.add(net);
                body.add(Box.createVerticalStrut(UserConfig.s(8)));

                JPanel cards = new JPanel(new GridBagLayout());
                cards.setOpaque(false);
                cards.setAlignmentX(Component.LEFT_ALIGNMENT);
                cards.setMaximumSize(new Dimension(Integer.MAX_VALUE, UserConfig.s(88)));
                cards.setPreferredSize(new Dimension(UserConfig.s(640), UserConfig.s(84)));
                GridBagConstraints gc = new GridBagConstraints();
                gc.fill = GridBagConstraints.BOTH;
                gc.insets = new Insets(0, 0, 0, UserConfig.s(8));
                gc.weightx = 0.28;
                gc.weighty = 1;
                gc.gridx = 0;
                gc.gridy = 0;
                cards.add(cardPin, gc);
                gc.insets = new Insets(0, 0, 0, 0);
                gc.weightx = 0.72;
                gc.gridx = 1;
                cards.add(cardKey, gc);
                body.add(cards);
                body.add(Box.createVerticalStrut(UserConfig.s(8)));
            } else {
                JLabel tip = new JLabel("<html>粘贴 <b>ts://IP/k...</b> 后点连接。端口可省略，默认 54300。</html>");
                tip.setForeground(MUTED);
                tip.setFont(tip.getFont().deriveFont((float) UserConfig.s(12)));
                tip.setAlignmentX(Component.LEFT_ALIGNMENT);
                tip.setBorder(new EmptyBorder(UserConfig.s(4), 0, UserConfig.s(4), 0));
                body.add(tip);
                body.add(Box.createVerticalStrut(UserConfig.s(6)));
            }

            JLabel msgTitle = new JLabel(isServerMode ? "发送内容" : "连接串 / 发送内容");
            msgTitle.setForeground(TEXT);
            msgTitle.setFont(msgTitle.getFont().deriveFont((float) UserConfig.s(12)));
            msgTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(msgTitle);
            body.add(Box.createVerticalStrut(UserConfig.s(4)));
            areaMessage.setForeground(TEXT);
            areaMessage.setBackground(Color.WHITE);
            scrollMessage.setAlignmentX(Component.LEFT_ALIGNMENT);
            scrollMessage.setPreferredSize(new Dimension(UserConfig.s(640), UserConfig.s(220)));
            body.add(scrollMessage);
            ensurePastePreview();
            pastePreview.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(pastePreview);
            body.add(Box.createVerticalStrut(UserConfig.s(6)));
            ensureFileTable();
            labelFileQueue.setFont(labelFileQueue.getFont().deriveFont((float) UserConfig.s(12)));
            labelFileQueue.setForeground(TEXT);
            fileTable.setRowHeight(Math.max(22, UserConfig.s(22)));
            fileTable.setFont(fileTable.getFont().deriveFont((float) UserConfig.s(12)));
            fileTable.getTableHeader().setFont(fileTable.getTableHeader().getFont().deriveFont((float) UserConfig.s(11)));
            if (buttonBrowse != null) {
                fixToolbarButton(buttonBrowse, 72);
            }
            fixToolbarButton(buttonRemoveFiles, 72);
            scrollFiles.setAlignmentX(Component.LEFT_ALIGNMENT);
            filePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(filePanel);
            installFileDrop(areaMessage);
            installSmartPaste(areaMessage);
            installSmartPaste(miniTextArea);
            installFileDrop(fileTable);
            installFileDrop(scrollFiles);
            installFileDrop(root);
            applyFileListLayout();
            refreshFileQueueLabel();

            labelStatus.setFont(labelStatus.getFont().deriveFont((float) UserConfig.s(12)));
            labelStatus.setForeground(MUTED);
            progressBar.setPreferredSize(new Dimension(UserConfig.s(140), UserConfig.s(10)));
            Dimension cancelSz = new Dimension(UserConfig.s(64), UserConfig.s(24));
            buttonCancel.setPreferredSize(cancelSz);
            buttonCancel.setMinimumSize(cancelSz);
            buttonCancel.setMaximumSize(cancelSz);
            buttonCancel.setMargin(new Insets(0, 6, 0, 6));
            buttonCancel.setFont(buttonCancel.getFont().deriveFont((float) UserConfig.s(12)));
            root.add(top, BorderLayout.NORTH);
            root.add(body, BorderLayout.CENTER);
            root.add(statusBar, BorderLayout.SOUTH);
            // 固定大窗尺寸，切角色不变形
            frame.setSize(UserConfig.s(760), UserConfig.s(560));
        }
        root.revalidate();
        root.repaint();
    }

    private static String formatScale() {
        float pct = UserConfig.getUiScale() * 100f;
        if (Math.abs(pct - Math.round(pct)) < 0.05f) {
            return Math.round(pct) + "%";
        }
        return String.format(java.util.Locale.ROOT, "%.1f%%", pct);
    }

    /** 滚轮 ±：普通 ±1%，Ctrl ±0.1% */
    private static void installScaleWheel(Component c) {
        for (var l : c.getMouseWheelListeners()) {
            c.removeMouseWheelListener(l);
        }
        c.addMouseWheelListener(e -> {
            if (e.getWheelRotation() == 0) {
                return;
            }
            float step = e.isControlDown() ? 0.1f : 1.0f;
            float delta = -e.getWheelRotation() * step;
            adjustScaleByPercent(delta);
            e.consume();
        });
    }

    private static UndoManager attachUndo(JTextArea area) {
        UndoManager um = new UndoManager();
        um.setLimit(50);
        area.getDocument().addUndoableEditListener(um);
        bindUndoKeys(area, um);
        return um;
    }

    private static void bindUndoKeys(JTextArea area, UndoManager um) {
        if (area == null || um == null) {
            return;
        }
        AbstractAction undo = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (um.canUndo()) {
                    um.undo();
                }
            }
        };
        AbstractAction redo = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (um.canRedo()) {
                    um.redo();
                }
            }
        };
        ActionMap am = area.getActionMap();
        am.put("ts-undo", undo);
        am.put("undo", undo);
        am.put("ts-redo", redo);
        am.put("redo", redo);

        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        int[] masks = {menu, InputEvent.CTRL_DOWN_MASK, InputEvent.META_DOWN_MASK};
        InputMap im = area.getInputMap(JComponent.WHEN_FOCUSED);
        for (int mask : masks) {
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask), "ts-undo");
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, mask), "ts-redo");
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask | InputEvent.SHIFT_DOWN_MASK), "ts-redo");
        }
    }

    /** 程序改文本（清空 / 显示 PIN）不进撤销栈。 */
    private static void setTextResetUndo(JTextArea area, String text) {
        if (area == null) {
            return;
        }
        area.setText(text == null ? "" : text);
        UndoManager um = area == miniTextArea ? undoMini : area == areaMessage ? undoMessage : null;
        if (um != null) {
            um.discardAllEdits();
        }
    }

    private static void adjustScaleByPercent(float deltaPercent) {
        float pct = UserConfig.getUiScale() * 100f + deltaPercent;
        pct = Math.max(50f, Math.min(300f, pct));
        // 保留到 0.1%
        pct = Math.round(pct * 10f) / 10f;
        float next = pct / 100f;
        if (Math.abs(next - UserConfig.getUiScale()) < 0.0005f) {
            return;
        }
        UserConfig.setUiScale(next);
        if (labelScale != null) {
            labelScale.setText(formatScale());
        }
        flashStatus("缩放 " + formatScale() + "（已保存）");
        if (scaleRebuildTimer != null) {
            scaleRebuildTimer.stop();
        }
        scaleRebuildTimer = new Timer(120, ev -> {
            Point keepLoc = frame != null ? frame.getLocation() : null;
            buildUi(isServerMode);
            if (keepLoc != null && frame != null) {
                frame.setLocation(keepLoc);
            }
        });
        scaleRebuildTimer.setRepeats(false);
        scaleRebuildTimer.start();
    }

    private static void enableDrag(Component c) {
        final int[] offset = new int[2];
        c.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                offset[0] = e.getXOnScreen() - frame.getX();
                offset[1] = e.getYOnScreen() - frame.getY();
            }
        });
        c.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (frame == null) {
                    return;
                }
                frame.setLocation(e.getXOnScreen() - offset[0], e.getYOnScreen() - offset[1]);
            }
        });
    }

    private static void shrinkBtn(AbstractButton b) {
        b.setFont(b.getFont().deriveFont(11f));
        b.setMargin(new Insets(1, 4, 1, 4));
        b.setBorder(new EmptyBorder(2, 6, 2, 6));
    }

    /** 大窗工具栏按钮统一尺寸，切角色不跳动 */
    private static void fixToolbarButton(AbstractButton b, int width) {
        Dimension d = new Dimension(UserConfig.s(width), UserConfig.s(28));
        b.setPreferredSize(d);
        b.setMinimumSize(d);
        b.setMaximumSize(d);
        b.setMargin(new Insets(2, 4, 2, 4));
        b.setFont(b.getFont().deriveFont((float) UserConfig.s(12)));
    }

    private static String keyHtml(String key) {
        int w = UserConfig.s(320);
        return "<html><body style='width:" + w + "px;word-wrap:break-word;font-family:monospace;'>"
                + key + "</body></html>";
    }

    /** 小窗共用区：对齐原版 — 未启动放 IP combo；启动后换成 scroll+textArea（PIN / 输入） */
    private static void refreshMiniShared() {
        if (!miniMode || miniPanelText == null) {
            return;
        }
        miniPanelText.removeAll();
        if (isServerMode) {
            if (!isServerRunning()) {
                miniShowingPin = false;
                comboIps.setBounds(0, 0, 122, 30);
                comboIps.setEnabled(true);
                wireIpComboTooltips();
                miniPanelText.add(comboIps);
            } else {
                miniScroll.setBounds(0, 0, 122, 30);
                if (connectedClients == 0) {
                    miniShowingPin = true;
                    miniTextArea.setEditable(false);
                    setTextResetUndo(miniTextArea, pairingMaterial != null ? pairingMaterial.pin : "");
                    miniTextArea.setToolTipText("点击复制完整连接串 ts://…");
                    miniTextArea.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                } else {
                    if (miniShowingPin) {
                        setTextResetUndo(miniTextArea, "");
                        miniShowingPin = false;
                    }
                    miniTextArea.setEditable(true);
                    miniTextArea.setToolTipText(null);
                    miniTextArea.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
                }
                miniPanelText.add(miniScroll);
            }
        } else {
            // 客户端小窗是 400×300 大文本区，布局在 applyOriginalClientMini 已搭好
            miniShowingPin = false;
            miniTextArea.setEditable(true);
            miniTextArea.setToolTipText(isClientConnected ? null : "粘贴 ts://… 后点连接");
            if (miniPanelText.getComponentCount() == 0) {
                miniScroll.setBounds(1, 1, 388, 223);
                miniPanelText.add(miniScroll);
            }
        }
        miniPanelText.revalidate();
        miniPanelText.repaint();
    }

    /** IPv6 很长：悬停显示完整地址（下拉项 + 当前选中） */
    private static void wireIpComboTooltips() {
        if (comboIps == null) {
            return;
        }
        comboIps.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (c instanceof JComponent && value != null) {
                    ((JComponent) c).setToolTipText(value.toString());
                }
                return c;
            }
        });
        Object sel = comboIps.getSelectedItem();
        comboIps.setToolTipText(sel == null ? null : sel.toString());
        if (!Boolean.TRUE.equals(comboIps.getClientProperty("ts.ipTip"))) {
            comboIps.putClientProperty("ts.ipTip", Boolean.TRUE);
            comboIps.addActionListener(e -> {
                Object s = comboIps.getSelectedItem();
                comboIps.setToolTipText(s == null ? null : s.toString());
            });
        }
    }

    private static void refreshRunningChrome() {
        boolean clientBusy = isClientConnected || clientHandshaking;
        boolean active = isServerMode ? isServerRunning() : clientBusy;
        if (isServerMode) {
            buttonStart.setText(isServerRunning() ? "停止" : "启动");
            buttonRole.setText("客户端");
            if (pairingMaterial != null) {
                valuePin.setFont(valuePin.getFont().deriveFont(Font.BOLD, (float) UserConfig.s(16)));
                valuePin.setText(pairingMaterial.pin);
                String k = pairingMaterial.pskB64;
                valueKey.setFont(valueKey.getFont().deriveFont(Font.PLAIN, (float) UserConfig.s(12)));
                valueKey.setText(keyHtml(k));
                valueKey.setToolTipText("单击复制密钥连接串 · 双击复制密钥");
                valuePin.setToolTipText("单击复制 PIN 连接串 · 双击复制 PIN");
            } else {
                valuePin.setText("—");
                valueKey.setText("—");
                valuePin.setToolTipText(null);
                valueKey.setToolTipText(null);
            }
            buttonQr.setEnabled(true);
            if (!transferUi && labelStatus != null) {
                labelStatus.setText(runningStatusText());
            }
        } else {
            buttonStart.setText(clientBusy ? "断开" : "连接");
            buttonRole.setText("服务端");
            buttonQr.setEnabled(false);
            if (!transferUi && labelStatus != null) {
                labelStatus.setText(runningStatusText());
            }
        }
        if (miniMode) {
            // 原版：服务运行中右侧按钮就是连接数 (N)，点一下仍发送
            if (isServerMode && isServerRunning()) {
                buttonSend.setText("(" + connectedClients + ")");
                buttonSend.setToolTipText("已连接客户端数；点击发送");
            } else if (active) {
                buttonSend.setText("发送");
                buttonSend.setToolTipText("发送文字");
            } else {
                buttonSend.setText("切换");
                buttonSend.setToolTipText("切换 服务端/客户端");
            }
            buttonStart.setToolTipText(isServerMode ? "启动/停止服务" : "连接/断开");
            if (!transferUi) {
                if (isServerMode) {
                    frame.setTitle("Server");
                } else {
                    frame.setTitle("Text Send PC Client - " + VERSION);
                }
            }
            refreshMiniShared();
        } else {
            buttonSend.setText("发送");
            buttonSend.setToolTipText(null);
            buttonStart.setToolTipText(null);
            wireIpComboTooltips();
        }
        if (buttonBrowse != null) {
            buttonBrowse.setEnabled(true);
        }
        if (buttonRemoveFiles != null) {
            buttonRemoveFiles.setEnabled(fileTable != null && fileTable.getSelectedRowCount() > 0);
        }
        buttonRole.setEnabled(!active);
        buttonRole.setVisible(!miniMode);
        fieldPort.setEnabled(isServerMode && !isServerRunning());
        comboIps.setEnabled(isServerMode && !isServerRunning());
        toggleMini.setSelected(miniMode);
    }

    private static void syncPortFromField() {
        String input = fieldPort.getText().trim();
        if (input.isEmpty() || "0".equals(input)) {
            serverListenPort = String.valueOf(Protocol.DEFAULT_PORT);
        } else {
            try {
                int p = Integer.parseInt(input);
                if (p < 1 || p > 65535) {
                    throw new NumberFormatException();
                }
                serverListenPort = String.valueOf(p);
            } catch (NumberFormatException e) {
                flashStatus("端口无效");
                fieldPort.setText(serverListenPort);
                return;
            }
        }
        fieldPort.setText(serverListenPort);
        UserConfig.setListenPort(Integer.parseInt(serverListenPort));
    }

    private static void startServer(boolean showQrImg) {
        pairingMaterial = PairingMaterial.generate();
        connectedClients = 0;
        String selectedIp = (String) comboIps.getSelectedItem();
        lastConnectUri = pairingMaterial.toUri(selectedIp, getServerListenPort());
        System.out.println("Log: PIN=" + pairingMaterial.pin);
        System.out.println("Log: URI=" + lastConnectUri);

        setServerRunning(true);
        setTextResetUndo(areaMessage, "");
        miniShowingPin = miniMode;
        inputShowingCount = false;
        copyText(lastConnectUri);
        flashStatus("已复制完整连接串 ts://…");

        if (showQrImg) {
            showQr();
        }

        TsServer server = new TsServer(getServerListenPort(), maxConnection, pairingMaterial,
                count -> SwingUtilities.invokeLater(() -> onClientCount(count)), FILE_IO);
        new Thread(server, "ts-server").start();
        refreshRunningChrome();
    }

    private static void onClientCount(int count) {
        int prev = connectedClients;
        connectedClients = count;
        // 首次有客户端连上：清空 PIN，共用区改输入
        if (miniMode && prev == 0 && count > 0) {
            miniShowingPin = true; // 让 refreshMiniShared 走清空分支
        }
        refreshRunningChrome();
    }

    private static void clearCountPlaceholder() {
        inputShowingCount = false;
    }

    private static void showCountInInputIfIdle() {
        // no-op：连接数显示在副按钮 (N)
    }

    /** 监听线程退出时回收界面（端口占用 / 绑定失败） */
    public static void onServerListenEnded() {
        SwingUtilities.invokeLater(() -> {
            if (serverRunning || pairingMaterial != null) {
                stopServer();
            }
        });
    }

    public static void stopServer() {
        setServerRunning(false);
        pairingMaterial = null;
        lastConnectUri = "";
        connectedClients = 0;
        inputShowingCount = false;
        miniShowingPin = false;
        closeQrDialog();
        fileBusy = false;
        resetSendQueue();
        endTransferUi();
        TsServer.cancelFilesCurrent();
        TsServer.stopCurrent();
        setTextResetUndo(areaMessage, "");
        setTextResetUndo(miniTextArea, "");
        refreshRunningChrome();
    }

    private static void startClient() {
        if (isClientConnected || clientHandshaking) {
            return;
        }
        String raw = miniMode ? miniTextArea.getText().trim() : areaMessage.getText().trim();
        final TsUri uri;
        try {
            uri = TsUri.parse(raw);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame,
                    "连接失败。格式：ts://IP/k长密钥 或 /k八位PIN（端口可省略，默认 54300）\n" + e.getMessage(),
                    "连接失败", JOptionPane.ERROR_MESSAGE);
            return;
        }
        clientConnectAbort = false;
        isClientConnected = false;
        clientHandshaking = true;
        refreshRunningChrome();
        flashStatus("正在连接…");
        new Thread(() -> {
            Socket sock = null;
            try {
                System.out.println("Log: 【连接】" + uri.host + ":" + uri.port
                        + (uri.pinMode ? "  mode=PIN" : "  mode=PSK"));
                sock = new Socket();
                clientSocket = sock;
                sock.connect(new InetSocketAddress(uri.host, uri.port), 5000);
                if (clientConnectAbort) {
                    sock.close();
                    return;
                }
                TsPeer peer = new TsPeer(sock, uri, msg -> {
                }, () -> SwingUtilities.invokeLater(() -> {
                    clientHandshaking = false;
                    isClientConnected = true;
                    setTextResetUndo(areaMessage, "");
                    setTextResetUndo(miniTextArea, "");
                    inputShowingCount = false;
                    refreshRunningChrome();
                    flashStatus("握手完成");
                    scheduleControl.set(true);
                    new Thread(() -> {
                        Runnable check = () -> {
                            if (clientSocket != null && clientSocket.isClosed()) {
                                SwingUtilities.invokeLater(TextSendMain::stopClient);
                            }
                        };
                        new ScheduleTask(check, 1, 1, scheduleControl, SECONDS).startTask();
                    }).start();
                }), () -> SwingUtilities.invokeLater(() -> {
                    boolean handshakeFailed = clientHandshaking && !clientConnectAbort;
                    isClientConnected = false;
                    clientHandshaking = false;
                    clientPeer = null;
                    scheduleControl.set(false);
                    refreshRunningChrome();
                    if (handshakeFailed) {
                        flashStatus("握手失败，连接串仍保留");
                    }
                }));
                peer.setFileIo(FILE_IO);
                clientPeer = peer;
                if (clientConnectAbort) {
                    peer.close();
                    return;
                }
                peer.run();
            } catch (Exception e) {
                e.printStackTrace();
                final boolean aborted = clientConnectAbort;
                SwingUtilities.invokeLater(() -> {
                    stopClient();
                    if (!aborted) {
                        JOptionPane.showMessageDialog(frame,
                                "连接失败。格式：ts://IP/k长密钥 或 /k八位PIN（端口可省略，默认 54300）\n" + e.getMessage(),
                                "连接失败", JOptionPane.ERROR_MESSAGE);
                    }
                });
            }
        }, "ts-client").start();
    }

    private static void stopClient() {
        clientConnectAbort = true;
        isClientConnected = false;
        clientHandshaking = false;
        sending = false;
        fileBusy = false;
        resetSendQueue();
        endTransferUi();
        cancelTransfer();
        scheduleControl.set(false);
        if (clientPeer != null) {
            clientPeer.close();
            clientPeer = null;
        }
        if (clientSocket != null) {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            clientSocket = null;
        }
        refreshRunningChrome();
    }

    private static void sendMessage() {
        if (miniMode && miniShowingPin) {
            return;
        }
        if (sending) {
            return;
        }
        if (pastePreviewFile != null && pastePreviewFile.isFile()) {
            sendQueuedFiles(List.of(pastePreviewFile));
            return;
        }
        String text = miniMode ? miniTextArea.getText() : areaMessage.getText();
        boolean preferText = text != null && !text.isBlank() && !inputShowingCount
                && (miniMode || (areaMessage != null && areaMessage.isFocusOwner()));
        if (!preferText) {
            List<File> selected = selectedQueuedFiles();
            if (!selected.isEmpty()) {
                if (fileBusy) {
                    int n = appendToSendQueue(selected);
                    flashStatus(n > 0 ? "已追加 " + n + " 个，将依次发送" : "已在发送队列中");
                    return;
                }
                sendQueuedFiles(selected);
                return;
            }
        }
        if (fileBusy) {
            return;
        }
        if (text == null || text.isBlank() || inputShowingCount) {
            if (!fileQueue.isEmpty()) {
                flashStatus("请选择要发送的文件（Ctrl+A 全选，Shift 连选）");
            }
            return;
        }
        if (isServerMode) {
            if (!isServerRunning()) {
                flashStatus("服务未运行");
                return;
            }
        } else if (!isClientConnected || clientPeer == null || !clientPeer.isAlive()) {
            flashStatus("尚未握手或已断开");
            return;
        }
        sending = true;
        new Thread(() -> {
            try {
                if (isServerMode) {
                    int n = TsServer.sendToAllCurrent(text);
                    if (n < 0) {
                        onSendFinished(false, text, "服务未运行");
                        return;
                    }
                    if (n == 0) {
                        onSendFinished(false, text, "没有已连接的客户端");
                        return;
                    }
                } else {
                    TsPeer peer = clientPeer;
                    if (!isClientConnected || peer == null || !peer.isAlive()) {
                        onSendFinished(false, text, "尚未握手或已断开");
                        return;
                    }
                    peer.sendText(text);
                }
                onSendFinished(true, text, null);
            } catch (Exception e) {
                e.printStackTrace();
                onSendFinished(false, text, "发送失败: " + e.getMessage());
            }
        }, "ts-send").start();
    }

    private static void onSendFinished(boolean ok, String sent, String err) {
        SwingUtilities.invokeLater(() -> {
            sending = false;
            if (ok) {
                String now = miniMode ? miniTextArea.getText() : areaMessage.getText();
                if (sent.equals(now)) {
                    cleanTextArea();
                }
            } else if (err != null) {
                flashStatus(err);
            }
        });
    }

    public static void cleanTextArea() {
        setTextResetUndo(areaMessage, "");
        setTextResetUndo(miniTextArea, "");
        inputShowingCount = false;
    }

    public static void setClientCount(int count) {
        onClientCount(count);
    }

    private static String runningStatusText() {
        if (isServerMode) {
            if (isServerRunning()) {
                return "运行中  ·  端口 " + serverListenPort + "  ·  客户端 " + connectedClients;
            }
            return "就绪  ·  端口 " + serverListenPort;
        }
        if (isClientConnected) {
            return "已连接";
        }
        if (clientHandshaking) {
            return "连接中 — 正在密钥交换";
        }
        return "未连接 — 粘贴 ts:// 后点连接";
    }

    private static void flushProgressUi() {
        boolean incoming;
        String name;
        long done;
        long total;
        double bps;
        int seq;
        int of;
        synchronized (progressLock) {
            progressPosted = false;
            incoming = progressIncoming;
            name = progressName;
            done = progressDone;
            total = progressTotal;
            bps = speedBps;
            seq = progressSeq;
            of = progressOf;
        }
        transferUi = true;
        int pct = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
        String speed = bps > 0 ? fmtSpeed(bps) : "—";
        String verb = incoming ? "接收" : "发送";
        StringBuilder sb = new StringBuilder(verb);
        if (seq > 0 && of > 1) {
            sb.append(' ').append(seq).append('/').append(of);
        }
        if (!miniMode && name != null && !name.isEmpty()) {
            sb.append(' ').append(shortFileName(name));
        }
        sb.append("  ").append(pct).append("%  ").append(speed);
        String text = sb.toString();
        if (miniMode && frame != null) {
            frame.setTitle(text);
            return;
        }
        if (labelStatus != null) {
            labelStatus.setText(text);
            String tip = total > 0 ? fmtBytes(done) + " / " + fmtBytes(total) : null;
            if (seq > 0 && of > 1) {
                String batch = "第 " + seq + "/" + of + " 个";
                tip = tip == null ? batch : batch + " · " + tip;
            }
            labelStatus.setToolTipText(tip);
        }
        if (progressBar != null) {
            if (total <= 0) {
                progressBar.setIndeterminate(true);
            } else {
                progressBar.setIndeterminate(false);
                progressBar.setValue((int) Math.min(1000, done * 1000 / total));
            }
            progressBar.setVisible(true);
        }
        if (buttonCancel != null) {
            buttonCancel.setVisible(true);
            buttonCancel.setToolTipText(incoming ? "取消接收" : "取消发送");
        }
    }

    private static String shortFileName(String name) {
        if (name.length() <= 28) {
            return name;
        }
        return name.substring(0, 14) + "…" + name.substring(name.length() - 10);
    }

    private static void endTransferUi() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(TextSendMain::endTransferUi);
            return;
        }
        transferUi = false;
        synchronized (progressLock) {
            progressPosted = false;
            speedKey = "";
            speedBps = 0;
            speedMarkBytes = 0;
        }
        if (progressBar != null) {
            progressBar.setIndeterminate(false);
            progressBar.setValue(0);
            progressBar.setVisible(false);
        }
        if (buttonCancel != null) {
            buttonCancel.setVisible(false);
        }
        if (labelStatus != null) {
            labelStatus.setToolTipText(null);
            if (!miniMode) {
                labelStatus.setText(runningStatusText());
            }
        }
        if (miniMode && frame != null) {
            frame.setTitle(isServerMode ? "Server" : "Text Send PC Client - " + VERSION);
        }
    }

    private static String fmtSpeed(double bps) {
        if (bps < 1024) {
            return String.format(java.util.Locale.ROOT, "%.0f B/s", bps);
        }
        if (bps < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB/s", bps / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB/s", bps / (1024.0 * 1024.0));
    }

    private static void flashStatus(String s) {
        if (labelStatus == null) {
            return;
        }
        labelStatus.setText(s);
        if (statusFlashTimer != null) {
            statusFlashTimer.stop();
        }
        statusFlashTimer = new Timer(1600, e -> {
            if (labelStatus != null && !miniMode && !transferUi) {
                labelStatus.setText(runningStatusText());
            }
        });
        statusFlashTimer.setRepeats(false);
        statusFlashTimer.start();
    }

    private static void showHelp() {
        String text = """
                TextSend PC  ·  协议 v1  ·  %s

                —— 服务端 ——
                · 选择网卡 IP 和端口，点「启动」。
                · 左键启动：最多 1 个客户端，并打开二维码。
                · 中键启动：最多 7 个客户端。
                · 右键启动：最多 1 个客户端，不弹二维码。
                · PIN 卡片：单击复制 ts://…/kPIN 连接串；双击复制 8 位 PIN。
                · 长密钥卡片：单击复制 PSK 连接串；双击复制长密钥。

                —— 客户端 ——
                · 粘贴连接串 ts://IP/k... 后点「连接」。端口可省略，默认 54300。
                · 不要只填 IP。PIN 路径用 8 位短码，扫码用长密钥。
                · 握手成功后才清空输入框；失败或点断开则保留连接串。

                —— 小窗 ——
                · 点「置顶小窗」进入紧凑窗；Esc 回到大窗。

                —— 输入 ——
                · Ctrl+Z 撤销；Ctrl+Y 重做（也可用 Ctrl+Shift+Z）。Mac 用 Cmd。
                · 文件：「浏览」左键 Java 对话框（可把文件拖进对话框，打开该文件所在文件夹）；右键用系统文件选择框。
                · 文件按列表一个个发。发送中仍可浏览/拖入追加，队列里的会接着发。
                · 列表里可点选、Ctrl 多选、Shift 连选、Ctrl+A 全选、Delete 移除。
                · 选中后点「发送」。发送中浏览/拖入会追加并接着发；没选中的不会自动捎上，可再选中点发送。
                · 对面落到「下载/TextSend」（重名自动加 (1)），不弹保存框。
                · 图片 ≤20MB：PC 写入剪贴板可直接粘贴；更大的当文件保存。
                · 本机 Ctrl+V 图片会先出现在输入区预览，点缩略图或「查看大图」可看原图，点「发送」才传；Backspace 清除预览。
                · Shift+「浏览」选 1 个文件夹，按相对路径在对面重建（软链默认不跟随，列表旁可开）。进度显示 3/40。
                · 传输中断后可再发同一文件续传（认文件头哈希，不只是同名同大小）。暂无手动选断点。
                · 多个客户端时会提示将发给几台。

                —— 快捷键 ——
                · Ctrl+Shift+W / Ctrl+Q：退出程序
                · Ctrl+Enter：发送（列表有选中则发文件；输入框有焦点则发文字）
                · Ctrl+O：浏览添加文件（Java 对话框）
                · Esc：小窗回到大窗
                · F1：帮助

                —— 缩放 ——
                · 鼠标放在标题旁的百分比上，滚轮 ±1%%；按住 Ctrl 时 ±0.1%%。
                · 范围 50%%–300%%，立刻写入配置文件。

                —— 二维码 ——
                · 启动服务后可点「二维码」，在窗口内显示，无需浏览器。

                —— 配置文件 ——
                · 优先：程序同一目录的 textsend.properties（jar / 绿色版）。
                · 程序目录不能写时（系统安装包）：主目录下仅一份 .textsend.properties。
                · 不建 ~/.config。保存项：uiScale、listenPort、preferMini、followSymlinks。
                """.formatted(VERSION);
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setCaretPosition(0);
        area.setBackground(CARD);
        area.setForeground(TEXT);
        area.setFont(area.getFont().deriveFont((float) UserConfig.s(13)));
        area.setBorder(new EmptyBorder(UserConfig.s(8), UserConfig.s(10), UserConfig.s(8), UserConfig.s(10)));
        JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(new Dimension(UserConfig.s(520), UserConfig.s(420)));
        JOptionPane.showMessageDialog(frame, sp, "帮助 · TextSend " + VERSION, JOptionPane.PLAIN_MESSAGE);
    }

    private static void copyText(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    @SuppressWarnings("unchecked")
    private static void installFileDrop(JComponent c) {
        if (c == null) {
            return;
        }
        c.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.imageFlavor)
                        || support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                        || support.isDataFlavorSupported(DataFlavor.stringFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                try {
                    if (support.isDataFlavorSupported(DataFlavor.imageFlavor)
                            && support.getComponent() instanceof JTextComponent) {
                        Object data = support.getTransferable().getTransferData(DataFlavor.imageFlavor);
                        BufferedImage img = data instanceof BufferedImage bi ? bi
                                : PasteUtil.toBuffered(data instanceof Image im ? im : null);
                        if (offerPasteImage(img)) {
                            return true;
                        }
                    }
                    if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        List<File> files = (List<File>) support.getTransferable()
                                .getTransferData(DataFlavor.javaFileListFlavor);
                        enqueueFiles(new ArrayList<>(files));
                        return true;
                    }
                    if (support.isDataFlavorSupported(DataFlavor.stringFlavor)
                            && support.getComponent() instanceof JTextComponent tc) {
                        String str = (String) support.getTransferable()
                                .getTransferData(DataFlavor.stringFlavor);
                        tc.replaceSelection(str);
                        return true;
                    }
                } catch (Exception e) {
                    flashStatus("拖入失败: " + e.getMessage());
                }
                return false;
            }
        });
    }

    private static void ensureFileTable() {
        if (fileTable != null) {
            fileTable.updateUI();
            if (fileTable.getTableHeader() != null) {
                fileTable.getTableHeader().updateUI();
            }
            if (buttonBrowse != null) {
                buttonBrowse.updateUI();
            }
            if (buttonRemoveFiles != null) {
                buttonRemoveFiles.updateUI();
            }
            return;
        }
        fileTable = new JTable(fileModel);
        fileTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        fileTable.setRowSelectionAllowed(true);
        fileTable.setColumnSelectionAllowed(false);
        fileTable.getTableHeader().setReorderingAllowed(false);
        fileTable.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        fileTable.setFillsViewportHeight(true);
        fileTable.setShowHorizontalLines(true);
        fileTable.setShowVerticalLines(false);
        fileTable.setBackground(Color.WHITE);
        fileTable.setForeground(TEXT);
        fileTable.setGridColor(new Color(0xE0E0E0));
        fileTable.setToolTipText("拖入文件显示路径。点选 / Ctrl 多选 / Shift 连选 / Ctrl+A 全选 / Delete 移除");
        fileTable.getColumnModel().getColumn(0).setPreferredWidth(160);
        fileTable.getColumnModel().getColumn(1).setPreferredWidth(360);
        fileTable.getColumnModel().getColumn(2).setPreferredWidth(80);
        fileTable.getColumnModel().getColumn(2).setMaxWidth(100);
        DefaultTableCellRenderer pathTip = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (row >= 0 && row < fileQueue.size()) {
                    setToolTipText(fileQueue.get(row).getAbsolutePath());
                } else {
                    setToolTipText(value == null ? null : value.toString());
                }
                return c;
            }
        };
        fileTable.setDefaultRenderer(Object.class, pathTip);
        fileTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && buttonRemoveFiles != null) {
                buttonRemoveFiles.setEnabled(fileTable.getSelectedRowCount() > 0);
            }
        });
        InputMap im = fileTable.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = fileTable.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "ts-remove-files");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "ts-remove-files");
        am.put("ts-remove-files", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                removeSelectedQueuedFiles();
            }
        });
        JPopupMenu popup = new JPopupMenu();
        popup.add(new AbstractAction("全选") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (fileTable.getRowCount() > 0) {
                    fileTable.selectAll();
                }
            }
        });
        popup.add(new AbstractAction("移除所选") {
            @Override
            public void actionPerformed(ActionEvent e) {
                removeSelectedQueuedFiles();
            }
        });
        popup.addSeparator();
        popup.add(new AbstractAction("浏览添加…") {
            @Override
            public void actionPerformed(ActionEvent e) {
                pickAndAddFiles();
            }
        });
        popup.add(new AbstractAction("系统选择…") {
            @Override
            public void actionPerformed(ActionEvent e) {
                pickWithSystemDialog();
            }
        });
        fileTable.setComponentPopupMenu(popup);

        scrollFiles = new JScrollPane(fileTable);
        scrollFiles.setBorder(BorderFactory.createLineBorder(BORDER));
        scrollFiles.getViewport().setBackground(Color.WHITE);

        labelFileQueue = new JLabel();
        labelFileQueue.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        labelFileQueue.setToolTipText("点击展开或收起待发列表");
        labelFileQueue.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    setFileListExpanded(!fileListExpanded);
                }
            }
        });
        buttonBrowse = new JButton("浏览");
        buttonBrowse.setToolTipText("左键选文件；Shift+左键选 1 个文件夹（按相对路径重建）。右键：系统文件选择框");
        buttonBrowse.addActionListener(e -> {
            if ((e.getModifiers() & ActionEvent.SHIFT_MASK) != 0) {
                pickFolderTree();
            } else {
                pickAndAddFiles();
            }
        });
        buttonBrowse.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) && buttonBrowse.isEnabled()) {
                    pickWithSystemDialog();
                }
            }
        });
        buttonRemoveFiles = new JButton("移除");
        buttonRemoveFiles.setToolTipText("从列表去掉所选（不删磁盘上的文件）");
        buttonRemoveFiles.addActionListener(e -> removeSelectedQueuedFiles());
        checkFollowLinks = new JCheckBox("软链");
        checkFollowLinks.setOpaque(false);
        checkFollowLinks.setToolTipText("发送文件夹时跟随符号链接（默认关）");
        checkFollowLinks.setSelected(UserConfig.isFollowSymlinks());
        checkFollowLinks.addActionListener(e -> UserConfig.setFollowSymlinks(checkFollowLinks.isSelected()));

        JPanel fileHead = new JPanel(new BorderLayout(8, 0));
        fileHead.setOpaque(false);
        fileHead.add(labelFileQueue, BorderLayout.WEST);
        JPanel fileBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        fileBtns.setOpaque(false);
        fileBtns.add(checkFollowLinks);
        fileBtns.add(buttonBrowse);
        fileBtns.add(buttonRemoveFiles);
        fileHead.add(fileBtns, BorderLayout.EAST);

        filePanel = new JPanel(new BorderLayout(0, 4));
        filePanel.setOpaque(false);
        filePanel.add(fileHead, BorderLayout.NORTH);
        filePanel.add(scrollFiles, BorderLayout.CENTER);
        refreshFileQueueLabel();
    }

    private static void setFileListExpanded(boolean expanded) {
        if (fileListExpanded == expanded) {
            applyFileListLayout();
            refreshFileQueueLabel();
            return;
        }
        fileListExpanded = expanded;
        applyFileListLayout();
        refreshFileQueueLabel();
    }

    private static void applyFileListLayout() {
        if (miniMode || scrollMessage == null || filePanel == null || scrollFiles == null) {
            return;
        }
        boolean on = fileListExpanded;
        scrollFiles.setVisible(on);
        if (on) {
            if (scrollFiles.getParent() != filePanel) {
                filePanel.add(scrollFiles, BorderLayout.CENTER);
            }
            scrollFiles.setPreferredSize(new Dimension(UserConfig.s(640), UserConfig.s(160)));
            filePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            filePanel.setPreferredSize(null);
            scrollMessage.setPreferredSize(new Dimension(UserConfig.s(640), UserConfig.s(120)));
        } else {
            filePanel.remove(scrollFiles);
            filePanel.setPreferredSize(new Dimension(UserConfig.s(640), UserConfig.s(28)));
            filePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, UserConfig.s(32)));
            scrollMessage.setPreferredSize(new Dimension(UserConfig.s(640), UserConfig.s(220)));
        }
        if (root != null) {
            root.revalidate();
            root.repaint();
        }
    }

    private static void pickAndAddFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("添加文件到待发列表（最多 " + Protocol.FILE_BATCH_MAX + " 个，不支持文件夹）");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        File start = lastFileChooserDir;
        if (start == null || !start.isDirectory()) {
            start = FileNames.downloads().toFile();
        }
        if (start.isDirectory()) {
            chooser.setCurrentDirectory(start);
        }
        chooser.setPreferredSize(new Dimension(UserConfig.s(800), UserConfig.s(560)));
        installChooserFolderDrop(chooser);
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        lastFileChooserDir = chooser.getCurrentDirectory();
        File[] picked = chooser.getSelectedFiles();
        List<File> list = new ArrayList<>();
        if (picked != null) {
            for (File f : picked) {
                list.add(f);
            }
        }
        enqueueFiles(list);
    }

    private static void pickFolderTree() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("发送 1 个文件夹（对面按相对路径重建）");
        chooser.setMultiSelectionEnabled(false);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        File start = lastFileChooserDir;
        if (start == null || !start.isDirectory()) {
            start = FileNames.downloads().toFile();
        }
        if (start.isDirectory()) {
            chooser.setCurrentDirectory(start);
        }
        chooser.setPreferredSize(new Dimension(UserConfig.s(800), UserConfig.s(560)));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File root = chooser.getSelectedFile();
        if (root != null && root.getParentFile() != null) {
            lastFileChooserDir = root.getParentFile();
        }
        enqueueFolder(root);
    }

    private static void enqueueFolder(File root) {
        if (root == null || !root.isDirectory()) {
            flashStatus("请选择文件夹");
            return;
        }
        Path rootPath = root.toPath();
        String treeName = FileNames.sanitize(root.getName());
        EnumSet<FileVisitOption> opts = UserConfig.isFollowSymlinks()
                ? EnumSet.of(FileVisitOption.FOLLOW_LINKS)
                : EnumSet.noneOf(FileVisitOption.class);
        List<File> files = new ArrayList<>();
        List<String> rels = new ArrayList<>();
        try {
            Files.walkFileTree(rootPath, opts, Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(rootPath) && name.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!UserConfig.isFollowSymlinks() && Files.isSymbolicLink(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!UserConfig.isFollowSymlinks() && Files.isSymbolicLink(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = file.getFileName().toString();
                    if (name.startsWith(".")) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!Files.isRegularFile(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String rel = FileNames.sanitizeRelPath(rootPath.relativize(file).toString());
                    if (rel == null) {
                        return FileVisitResult.CONTINUE;
                    }
                    files.add(file.toFile());
                    rels.add(rel);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            flashStatus("读取文件夹失败: " + e.getMessage());
            return;
        }
        if (files.isEmpty()) {
            flashStatus("文件夹是空的（已跳过隐藏文件）");
            return;
        }
        ensureFileTable();
        int added = 0;
        int dup = 0;
        Set<String> have = new HashSet<>();
        for (File q : fileQueue) {
            have.add(fileKey(q));
        }
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            String key = fileKey(f);
            if (have.contains(key)) {
                dup++;
                continue;
            }
            have.add(key);
            fileQueue.add(f);
            queueTree.put(key, treeName);
            queueRel.put(key, rels.get(i));
            File parent = f.getParentFile();
            fileModel.addRow(new Object[]{
                    f.getName(),
                    parent == null ? f.getAbsolutePath() : parent.getAbsolutePath(),
                    fmtBytes(f.length())
            });
            added++;
        }
        if (added > 0) {
            setFileListExpanded(true);
            if (fileBusy) {
                appendToSendQueue(files);
            }
        }
        refreshFileQueueLabel();
        String msg = "已加入文件夹 " + treeName + "（" + added + " 个文件）";
        if (dup > 0) {
            msg += "，跳过重复 " + dup;
        }
        flashStatus(msg);
    }

    /** 把文件拖进 Java 对话框：只切换到该文件所在文件夹，显示里面的内容。 */
    private static void installChooserFolderDrop(JFileChooser chooser) {
        TransferHandler th = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                try {
                    List<File> files = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    if (files == null || files.isEmpty() || files.get(0) == null) {
                        return false;
                    }
                    File f = files.get(0);
                    File dir = f.isDirectory() ? f : f.getParentFile();
                    if (dir == null || !dir.isDirectory()) {
                        return false;
                    }
                    chooser.setCurrentDirectory(dir);
                    lastFileChooserDir = dir;
                    List<File> select = new ArrayList<>();
                    for (File x : files) {
                        if (x != null && x.isFile() && dir.equals(x.getParentFile())) {
                            select.add(x);
                        }
                    }
                    if (!select.isEmpty()) {
                        chooser.setSelectedFiles(select.toArray(File[]::new));
                    }
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        };
        chooser.addPropertyChangeListener(JFileChooser.DIRECTORY_CHANGED_PROPERTY, evt ->
                SwingUtilities.invokeLater(() -> applyTransferHandlerDeep(chooser, th)));
        chooser.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && chooser.isShowing()) {
                applyTransferHandlerDeep(chooser, th);
                Window w = SwingUtilities.getWindowAncestor(chooser);
                if (w != null) {
                    int ww = Math.max(w.getWidth(), UserConfig.s(800));
                    int hh = Math.max(w.getHeight(), UserConfig.s(560));
                    w.setSize(ww, hh);
                    w.setMinimumSize(new Dimension(UserConfig.s(640), UserConfig.s(480)));
                    w.setLocationRelativeTo(frame);
                }
                if (w instanceof RootPaneContainer rpc) {
                    applyTransferHandlerDeep(rpc.getContentPane(), th);
                }
            }
        });
        applyTransferHandlerDeep(chooser, th);
    }

    private static void applyTransferHandlerDeep(Component c, TransferHandler th) {
        if (c instanceof JComponent jc) {
            jc.setTransferHandler(th);
        }
        if (c instanceof Container box) {
            for (Component child : box.getComponents()) {
                applyTransferHandlerDeep(child, th);
            }
        }
    }

    /** 可选：系统原生文件选择框（选文件，不是只打开文件夹）。 */
    private static void pickWithSystemDialog() {
        FileDialog fd = new FileDialog(frame, "选择文件（系统）", FileDialog.LOAD);
        fd.setMultipleMode(true);
        File start = lastFileChooserDir;
        if (start == null || !start.isDirectory()) {
            start = FileNames.downloads().toFile();
        }
        if (start.isDirectory()) {
            fd.setDirectory(start.getAbsolutePath());
        }
        fd.setVisible(true);
        File[] files = fd.getFiles();
        if (files == null || files.length == 0) {
            return;
        }
        if (files[0].getParentFile() != null) {
            lastFileChooserDir = files[0].getParentFile();
        }
        enqueueFiles(List.of(files));
    }

    private static void enqueueFiles(List<File> dropped) {
        if (dropped == null || dropped.isEmpty()) {
            return;
        }
        ensureFileTable();
        boolean skippedFolder = false;
        int dup = 0;
        int added = 0;
        List<Integer> newRows = new ArrayList<>();
        List<File> justAdded = new ArrayList<>();
        Set<String> have = new HashSet<>();
        for (File q : fileQueue) {
            have.add(fileKey(q));
        }
        for (File f : dropped) {
            if (f == null) {
                continue;
            }
            if (f.isDirectory()) {
                skippedFolder = true;
                continue;
            }
            if (!f.isFile()) {
                continue;
            }
            if (fileQueue.size() >= Protocol.FILE_BATCH_MAX) {
                flashStatus("待发列表最多 " + Protocol.FILE_BATCH_MAX + " 个");
                break;
            }
            String key = fileKey(f);
            if (have.contains(key)) {
                dup++;
                continue;
            }
            have.add(key);
            fileQueue.add(f);
            queueTree.remove(key);
            queueRel.remove(key);
            File parent = f.getParentFile();
            fileModel.addRow(new Object[]{
                    f.getName(),
                    parent == null ? f.getAbsolutePath() : parent.getAbsolutePath(),
                    fmtBytes(f.length())
            });
            newRows.add(fileQueue.size() - 1);
            justAdded.add(f);
            added++;
        }
        if (added > 0 && fileTable != null) {
            fileTable.clearSelection();
            for (int row : newRows) {
                fileTable.addRowSelectionInterval(row, row);
            }
            fileTable.scrollRectToVisible(fileTable.getCellRect(newRows.get(0), 0, true));
            setFileListExpanded(true);
            if (!miniMode) {
                fileTable.requestFocusInWindow();
            }
        }
        refreshFileQueueLabel();
        if (added == 0) {
            if (skippedFolder && dup == 0) {
                flashStatus("不支持文件夹，请选择文件");
            } else if (dup > 0) {
                flashStatus("已在列表中");
            }
            return;
        }
        int queued = appendToSendQueue(justAdded);
        String msg = queued > 0 ? "已追加 " + queued + " 个，将依次发送" : "已加入 " + added + " 个文件";
        if (skippedFolder) {
            msg += "（已跳过文件夹）";
        }
        if (dup > 0) {
            msg += "，跳过重复 " + dup;
        }
        if (miniMode && !fileBusy) {
            msg += " · 回大窗可选后发送";
        }
        flashStatus(msg);
    }

    private static String fileKey(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }

    /** 同一轮发送里，文件夹名相同算一批；扁文件共用空串。须持有 sendLock。 */
    private static String sendGroupKey(File f) {
        String t = queueTree.get(fileKey(f));
        return t == null || t.isBlank() ? "" : t;
    }

    /** 须持有 sendLock。返回 [当前序号(从1), 这一批总数]。 */
    private static int[] sendSeqOfLocked(File current) {
        String g = sendGroupKey(current);
        int finished = sendDoneByTree.getOrDefault(g, 0);
        int remain = 0;
        for (File r : sendRemaining) {
            if (g.equals(sendGroupKey(r))) {
                remain++;
            }
        }
        int seq = finished + 1;
        return new int[]{seq, seq + remain};
    }

    private static void resetSendQueue() {
        synchronized (sendLock) {
            sendRemaining.clear();
            currentSendingFile = null;
            sendDoneByTree.clear();
        }
    }

    /** 把文件接到正在发送的队列末尾（已在传或已排队的跳过）。返回新追加个数。 */
    private static int appendToSendQueue(List<File> files) {
        if (files == null || files.isEmpty()) {
            return 0;
        }
        int n = 0;
        synchronized (sendLock) {
            if (!fileBusy) {
                return 0;
            }
            Set<String> have = new HashSet<>();
            if (currentSendingFile != null) {
                have.add(fileKey(currentSendingFile));
            }
            for (File q : sendRemaining) {
                have.add(fileKey(q));
            }
            for (File f : files) {
                if (f == null) {
                    continue;
                }
                String k = fileKey(f);
                if (have.contains(k)) {
                    continue;
                }
                sendRemaining.add(f);
                have.add(k);
                n++;
            }
        }
        return n;
    }

    private static void dropFromSendQueue(File f) {
        if (f == null) {
            return;
        }
        String k = fileKey(f);
        synchronized (sendLock) {
            sendRemaining.removeIf(x -> k.equals(fileKey(x)));
        }
    }

    private static List<File> selectedQueuedFiles() {
        List<File> out = new ArrayList<>();
        if (fileTable == null || fileQueue.isEmpty()) {
            return out;
        }
        int[] rows = fileTable.getSelectedRows();
        for (int r : rows) {
            int i = fileTable.convertRowIndexToModel(r);
            if (i >= 0 && i < fileQueue.size()) {
                out.add(fileQueue.get(i));
            }
        }
        return out;
    }

    private static void removeSelectedQueuedFiles() {
        if (fileTable == null) {
            return;
        }
        int[] rows = fileTable.getSelectedRows();
        if (rows.length == 0) {
            return;
        }
        List<Integer> modelRows = new ArrayList<>();
        for (int r : rows) {
            modelRows.add(fileTable.convertRowIndexToModel(r));
        }
        modelRows.sort((a, b) -> b - a);
        File sending = currentSendingFile;
        String sendingKey = sending == null ? null : fileKey(sending);
        boolean skippedSending = false;
        for (int i : modelRows) {
            if (i < 0 || i >= fileQueue.size()) {
                continue;
            }
            File f = fileQueue.get(i);
            if (sendingKey != null && sendingKey.equals(fileKey(f))) {
                skippedSending = true;
                continue;
            }
            dropFromSendQueue(f);
            queueTree.remove(fileKey(f));
            queueRel.remove(fileKey(f));
            fileQueue.remove(i);
            fileModel.removeRow(i);
        }
        refreshFileQueueLabel();
        if (fileQueue.isEmpty()) {
            setFileListExpanded(false);
        }
        refreshRunningChrome();
        if (skippedSending) {
            flashStatus("正在发送的文件不能移除，可点取消");
        }
    }

    private static void removeQueued(List<File> sent) {
        if (sent == null || sent.isEmpty()) {
            return;
        }
        Set<String> keys = new HashSet<>();
        for (File f : sent) {
            keys.add(fileKey(f));
        }
        for (int i = fileQueue.size() - 1; i >= 0; i--) {
            if (keys.contains(fileKey(fileQueue.get(i)))) {
                File gone = fileQueue.remove(i);
                queueTree.remove(fileKey(gone));
                queueRel.remove(fileKey(gone));
                fileModel.removeRow(i);
            }
        }
        refreshFileQueueLabel();
        if (fileQueue.isEmpty()) {
            setFileListExpanded(false);
        }
    }

    private static void refreshFileQueueLabel() {
        if (labelFileQueue == null) {
            return;
        }
        String arrow = fileListExpanded ? "▼ " : "▶ ";
        labelFileQueue.setText(arrow + "待发文件（" + fileQueue.size()
                + (queueTree.isEmpty() ? "/" + Protocol.FILE_BATCH_MAX : "") + "）");
    }

    private static void sendQueuedFiles(List<File> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        if (fileBusy) {
            int n = appendToSendQueue(files);
            flashStatus(n > 0 ? "已追加 " + n + " 个，将依次发送" : "已在发送队列中");
            return;
        }
        if (sending) {
            flashStatus("正在发送");
            return;
        }
        if (!readyToSend()) {
            return;
        }
        boolean treeBatch = false;
        for (File f : files) {
            if (queueTree.containsKey(fileKey(f))) {
                treeBatch = true;
                break;
            }
        }
        if (!treeBatch && files.size() > Protocol.FILE_BATCH_MAX) {
            files = new ArrayList<>(files.subList(0, Protocol.FILE_BATCH_MAX));
            flashStatus("一次最多 " + Protocol.FILE_BATCH_MAX + " 个，已截取前 " + Protocol.FILE_BATCH_MAX + " 个");
        }
        int peers = 1;
        if (isServerMode) {
            peers = TsServer.aliveCountCurrent();
            if (peers < 0) {
                flashStatus("服务未运行");
                return;
            }
            if (peers == 0) {
                flashStatus("没有已连接的客户端");
                return;
            }
            if (peers > 1) {
                StringBuilder sb = new StringBuilder();
                sb.append("将发给 ").append(peers).append(" 台已连接设备。\n\n");
                int show = Math.min(files.size(), 8);
                for (int i = 0; i < show; i++) {
                    sb.append("· ").append(files.get(i).getName()).append('\n');
                }
                if (files.size() > 8) {
                    sb.append("… 共 ").append(files.size()).append(" 个文件\n");
                }
                sb.append("\n对面保存到「下载/").append(FileNames.FOLDER)
                        .append("」；较小的图片会进剪贴板。");
                int r = JOptionPane.showConfirmDialog(frame, sb.toString(), "发送文件",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (r != JOptionPane.OK_OPTION) {
                    return;
                }
            }
        }
        cancelFileSend.set(false);
        synchronized (sendLock) {
            sendRemaining.clear();
            sendRemaining.addAll(files);
            currentSendingFile = null;
            sendDoneByTree.clear();
            fileBusy = true;
        }
        refreshRunningChrome();
        new Thread(() -> {
            int ok = 0;
            String err = null;
            List<File> done = new ArrayList<>();
            try {
                sendMore:
                for (;;) {
                    for (;;) {
                        File f;
                        synchronized (sendLock) {
                            if (cancelFileSend.get()) {
                                sendRemaining.clear();
                                currentSendingFile = null;
                                break sendMore;
                            }
                            if (sendRemaining.isEmpty()) {
                                currentSendingFile = null;
                                break;
                            }
                            f = sendRemaining.remove(0);
                            currentSendingFile = f;
                        }
                        Path path = f.toPath();
                        String key = fileKey(f);
                        String tree = queueTree.get(key);
                        String rel = queueRel.get(key);
                        int seq;
                        int of;
                        synchronized (sendLock) {
                            int[] so = sendSeqOfLocked(f);
                            seq = so[0];
                            of = so[1];
                        }
                        if (isServerMode) {
                            int n = TsServer.sendFileToAllCurrent(path, cancelFileSend::get, tree, rel, seq, of);
                            if (n < 0) {
                                err = "服务未运行";
                                break sendMore;
                            }
                            if (n == 0) {
                                err = "没有已连接的客户端";
                                break sendMore;
                            }
                        } else {
                            TsPeer peer = clientPeer;
                            if (peer == null || !peer.isAlive()) {
                                err = "尚未握手或已断开";
                                break sendMore;
                            }
                            peer.sendFile(path, cancelFileSend::get, tree, rel, seq, of);
                        }
                        synchronized (sendLock) {
                            sendDoneByTree.merge(sendGroupKey(f), 1, Integer::sum);
                        }
                        done.add(f);
                        ok++;
                        final File sentFile = f;
                        SwingUtilities.invokeLater(() -> removeQueued(List.of(sentFile)));
                    }
                    synchronized (sendLock) {
                        if (cancelFileSend.get()) {
                            sendRemaining.clear();
                            currentSendingFile = null;
                            break sendMore;
                        }
                        if (!sendRemaining.isEmpty()) {
                            continue sendMore;
                        }
                        currentSendingFile = null;
                        fileBusy = false;
                        break sendMore;
                    }
                }
                if (cancelFileSend.get() && err == null) {
                    err = "已取消";
                }
            } catch (Exception e) {
                e.printStackTrace();
                String m = e.getMessage();
                if (m != null && m.contains("cancelled")) {
                    err = "已取消";
                } else {
                    err = "发送失败: " + m;
                }
            } finally {
                if (err != null || cancelFileSend.get()) {
                    synchronized (sendLock) {
                        currentSendingFile = null;
                        sendRemaining.clear();
                        fileBusy = false;
                    }
                }
            }
            final int sent = ok;
            final String fail = err;
            final List<File> sentFiles = done;
            SwingUtilities.invokeLater(() -> {
                endTransferUi();
                removeQueued(sentFiles);
                if (pastePreviewFile != null) {
                    String pk = fileKey(pastePreviewFile);
                    for (File sf : sentFiles) {
                        if (pk.equals(fileKey(sf))) {
                            clearPastePreview();
                            break;
                        }
                    }
                }
                refreshRunningChrome();
                if (fail != null && sent == 0) {
                    flashStatus(fail);
                } else if (fail != null) {
                    flashStatus("已发送 " + sent + " 个，" + fail);
                } else {
                    flashStatus("已发送 " + sent + " 个文件");
                }
            });
        }, "ts-send-file").start();
    }

    private static boolean readyToSend() {
        if (isServerMode) {
            if (!isServerRunning()) {
                flashStatus("服务未运行");
                return false;
            }
        } else if (!isClientConnected || clientPeer == null || !clientPeer.isAlive()) {
            flashStatus("尚未握手或已断开");
            return false;
        }
        return true;
    }

    private static void ensurePastePreview() {
        if (pastePreview != null) {
            return;
        }
        pastePreviewThumb = new JLabel();
        pastePreviewThumb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        pastePreviewThumb.setToolTipText("点击查看大图");
        pastePreviewThumb.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    showPastePreviewLarge();
                }
            }
        });
        pastePreviewText = new JLabel(" ");
        pastePreviewText.setForeground(MUTED);
        JButton view = new JButton("查看大图");
        view.setMargin(new Insets(0, 6, 0, 6));
        view.addActionListener(e -> showPastePreviewLarge());
        JButton clear = new JButton("清除");
        clear.setMargin(new Insets(0, 6, 0, 6));
        clear.addActionListener(e -> clearPastePreview());
        JPanel east = new JPanel();
        east.setOpaque(false);
        east.setLayout(new BoxLayout(east, BoxLayout.X_AXIS));
        east.add(view);
        east.add(Box.createHorizontalStrut(4));
        east.add(clear);
        pastePreview = new JPanel(new BorderLayout(8, 0));
        pastePreview.setOpaque(false);
        pastePreview.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(4, 6, 4, 6)));
        pastePreview.add(pastePreviewThumb, BorderLayout.WEST);
        pastePreview.add(pastePreviewText, BorderLayout.CENTER);
        pastePreview.add(east, BorderLayout.EAST);
        pastePreview.setVisible(false);
        pastePreview.setFocusable(true);
        pastePreview.setMaximumSize(new Dimension(Integer.MAX_VALUE, UserConfig.s(72)));
        pastePreview.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                pastePreview.requestFocusInWindow();
            }
        });
        InputMap pim = pastePreview.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap pam = pastePreview.getActionMap();
        pim.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "ts-clear-paste");
        pam.put("ts-clear-paste", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                clearPastePreview();
            }
        });
    }

    private static void installSmartPaste(JTextComponent area) {
        if (area == null) {
            return;
        }
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        InputMap im = area.getInputMap();
        ActionMap am = area.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, menu), "ts-paste-smart");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), "ts-paste-smart");
        am.put("ts-paste-smart", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (offerPasteImage(PasteUtil.getClipboardImage())) {
                    return;
                }
                area.paste();
            }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "ts-backspace");
        am.put("ts-backspace", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (pastePreview != null && pastePreview.isVisible() && pastePreviewFile != null) {
                    clearPastePreview();
                    return;
                }
                Action def = am.get(DefaultEditorKit.deletePrevCharAction);
                if (def != null) {
                    def.actionPerformed(e);
                }
            }
        });
    }

    private static boolean offerPasteImage(BufferedImage img) {
        if (img == null) {
            return false;
        }
        try {
            File tmp = File.createTempFile("textsend-paste-", ".png");
            tmp.deleteOnExit();
            ImageIO.write(img, "png", tmp);
            pastePreviewFile = tmp;
            pastePreviewImage = img;
            if (pastePreviewDialog != null) {
                pastePreviewDialog.dispose();
                pastePreviewDialog = null;
            }
            ensurePastePreview();
            int w = img.getWidth();
            int h = img.getHeight();
            int nh = UserConfig.s(48);
            int nw = Math.max(1, w * nh / Math.max(1, h));
            pastePreviewThumb.setIcon(new ImageIcon(img.getScaledInstance(nw, nh, Image.SCALE_SMOOTH)));
            pastePreviewText.setText("图片预览 " + w + "×" + h + "  ·  点发送才传，可查看大图");
            pastePreview.setVisible(true);
            if (root != null) {
                root.revalidate();
                root.repaint();
            }
            flashStatus("图片已放入输入区，点发送才传");
            return true;
        } catch (Exception e) {
            flashStatus("无法预览剪贴板图片: " + e.getMessage());
            return false;
        }
    }

    private static void showPastePreviewLarge() {
        BufferedImage img = pastePreviewImage;
        if (img == null && pastePreviewFile != null && pastePreviewFile.isFile()) {
            try {
                img = ImageIO.read(pastePreviewFile);
                pastePreviewImage = img;
            } catch (IOException e) {
                flashStatus("无法打开大图: " + e.getMessage());
                return;
            }
        }
        if (img == null) {
            return;
        }
        if (pastePreviewDialog != null && pastePreviewDialog.isDisplayable()) {
            pastePreviewDialog.toFront();
            return;
        }
        JLabel pic = new JLabel(new ImageIcon(img));
        pic.setHorizontalAlignment(SwingConstants.CENTER);
        JScrollPane sp = new JScrollPane(pic);
        sp.getVerticalScrollBar().setUnitIncrement(24);
        sp.getHorizontalScrollBar().setUnitIncrement(24);
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int pad = 48;
        int w = Math.min(img.getWidth() + pad, (int) (screen.width * 0.9));
        int h = Math.min(img.getHeight() + pad, (int) (screen.height * 0.85));
        sp.setPreferredSize(new Dimension(Math.max(w, 320), Math.max(h, 240)));
        pastePreviewDialog = new JDialog(frame, "查看大图  " + img.getWidth() + "×" + img.getHeight(), false);
        pastePreviewDialog.setContentPane(sp);
        pastePreviewDialog.pack();
        pastePreviewDialog.setLocationRelativeTo(frame);
        pastePreviewDialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JRootPane rp = pastePreviewDialog.getRootPane();
        rp.registerKeyboardAction(e -> pastePreviewDialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        pastePreviewDialog.setVisible(true);
    }

    private static void clearPastePreview() {
        pastePreviewFile = null;
        pastePreviewImage = null;
        if (pastePreviewDialog != null) {
            pastePreviewDialog.dispose();
            pastePreviewDialog = null;
        }
        if (pastePreview != null) {
            pastePreview.setVisible(false);
            if (pastePreviewThumb != null) {
                pastePreviewThumb.setIcon(null);
            }
        }
        if (root != null) {
            root.revalidate();
            root.repaint();
        }
    }

    private static void cancelTransfer() {
        cancelFileSend.set(true);
        if (isServerMode) {
            TsServer.cancelFilesCurrent();
        } else if (clientPeer != null) {
            clientPeer.cancelFile();
        }
    }

    private static String fmtBytes(long n) {
        if (n < 1024) {
            return n + " B";
        }
        if (n < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", n / 1024.0);
        }
        if (n < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", n / (1024.0 * 1024));
        }
        return String.format(java.util.Locale.ROOT, "%.2f GB", n / (1024.0 * 1024 * 1024));
    }

    private static void closeQrDialog() {
        if (qrDialog != null) {
            qrDialog.dispose();
            qrDialog = null;
        }
    }

    private static void showQr() {
        if (lastConnectUri == null || lastConnectUri.isEmpty()) {
            flashStatus("请先启动服务");
            return;
        }
        try {
            BufferedImage img = QR_Util.createQRImage(lastConnectUri, 360);
            closeQrDialog();
            qrDialog = new JDialog(frame, "扫码连接", false);
            qrDialog.setAlwaysOnTop(true);
            qrDialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            qrDialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    if (qrDialog != null && e.getWindow() == qrDialog) {
                        qrDialog = null;
                    }
                }
            });

            JPanel panel = new JPanel(new BorderLayout(0, UserConfig.s(8)));
            panel.setBackground(CARD);
            panel.setBorder(new EmptyBorder(UserConfig.s(12), UserConfig.s(12), UserConfig.s(12), UserConfig.s(12)));
            JLabel pic = new JLabel(new ImageIcon(img));
            pic.setHorizontalAlignment(SwingConstants.CENTER);
            JTextArea uriArea = new JTextArea(lastConnectUri);
            uriArea.setEditable(false);
            uriArea.setLineWrap(true);
            uriArea.setWrapStyleWord(true);
            uriArea.setBackground(BG);
            uriArea.setForeground(TEXT);
            uriArea.setFont(uriArea.getFont().deriveFont((float) UserConfig.s(12)));
            uriArea.setBorder(new EmptyBorder(UserConfig.s(6), UserConfig.s(6), UserConfig.s(6), UserConfig.s(6)));
            uriArea.setToolTipText("单击复制连接串");
            uriArea.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    copyText(lastConnectUri);
                    flashStatus("已复制连接串");
                }
            });
            panel.add(pic, BorderLayout.CENTER);
            panel.add(uriArea, BorderLayout.SOUTH);
            qrDialog.setContentPane(panel);
            qrDialog.pack();
            qrDialog.setResizable(false);
            qrDialog.setLocationRelativeTo(frame);
            qrDialog.setVisible(true);
        } catch (Exception e) {
            e.printStackTrace();
            flashStatus("二维码生成失败: " + e.getMessage());
        }
    }

    public static String getIP() throws SocketException {
        LinkedList<String> ipAddr = new LinkedList<>();
        Enumeration<NetworkInterface> interfs = NetworkInterface.getNetworkInterfaces();
        System.out.println("正在获取电脑本地 IP....");
        int n = 1;
        boolean getStatus = false;
        while (interfs.hasMoreElements()) {
            NetworkInterface interf = interfs.nextElement();
            Enumeration<InetAddress> addres = interf.getInetAddresses();
            if (n == 1 || getStatus) {
                System.out.println("<------第" + n + "组网卡------>");
                getStatus = false;
            }
            while (addres.hasMoreElements()) {
                InetAddress in = addres.nextElement();
                if (in instanceof Inet4Address) {
                    System.out.println(" - IPv4地址:" + in.getHostAddress());
                    ipAddr.add(in.getHostAddress());
                    getStatus = true;
                } else if (in instanceof Inet6Address) {
                    String v6 = in.getHostAddress().split("%")[0];
                    System.out.println(" - IPv6地址:" + v6);
                    ipAddr.add(v6);
                    getStatus = true;
                }
            }
            if (getStatus) {
                n += 1;
            }
        }
        netIps = ipAddr;
        String prefer = null;
        boolean find172 = false;
        for (String ip : ipAddr) {
            if (ip.startsWith("192.168")) {
                prefer = ip;
                break;
            } else if (ip.startsWith("172.")) {
                prefer = ip;
                find172 = true;
            } else if (ip.startsWith("10.") && !find172) {
                prefer = ip;
            }
        }
        if (prefer != null) {
            System.out.printf("猜测局域网 IP：%s%n", prefer);
        }
        System.err.println("TextSend " + VERSION + " 就绪，端口 " + serverListenPort);
        return prefer;
    }
}
