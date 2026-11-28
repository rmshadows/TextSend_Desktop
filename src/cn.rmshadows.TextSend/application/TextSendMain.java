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
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.DocumentFilter;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
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
import java.net.URI;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
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
    public static final String VERSION = "5.0.64";

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
    /** 探测呼吸灯用（对齐 Android colorPrimary 偏蓝） */
    private static final Color ACCENT = new Color(0x3D7EFF);

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
    private static JButton buttonClients;
    private static JButton buttonSend;
    private static JButton buttonFile;
    private static JButton buttonQr;
    private static JButton buttonWeb;
    private static JDialog webDialog;
    private static JDialog helpDialog;
    private static JDialog settingsDialog;
    /** 设置失焦关闭时正在问是否保存，避免确认框再把设置关掉。 */
    private static boolean settingsPrompt;
    private static boolean settingsHandled;
    private static java.util.function.BooleanSupplier settingsDirty;
    private static java.util.function.BooleanSupplier settingsApply;
    private static Runnable settingsAfterClose;
    /** 兼容模式窗口当前在看哪一档。只有 B 真的在跑；切到 A/C 不关 B。 */
    private static char webWayShown = 'B';
    private static JButton webWayB;
    private static JButton webWayA;
    private static JButton webWayC;
    private static JLabel webHintLabel;
    private static JLabel webStateLabel;
    private static JTextField webPortField;
    private static JPanel webQrPanel;
    private static BufferedImage webQrImage;
    private static String webQrPlaceholder = "启动后显示二维码";
    private static JTextArea webUrlArea;
    private static JButton webPowerBtn;
    private static JButton webRefreshBtn;
    private static JButton webCaBtn;
    private static Timer webDialogTimer;
    private static String webQrShown;
    /** paintWebDialog 在这段时间内保留「已复制链接」，不被 1 秒刷新盖掉。 */
    private static long webCopiedUntil;
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
    private static JCheckBox checkAutoStart;
    private static JCheckBox checkCustomPin;
    private static JComboBox<String> comboSendTarget;
    private static JTextField fieldCustomPin;
    private static JCheckBox checkRememberClient;
    private static JButton buttonSettings;
    private static JPanel lanPeerBox;
    private static JPanel connectedBox;
    private static JPanel authPendingBox;
    private static JButton connectedEntryBtn;
    private static JButton authPendingEntryBtn;
    private static JPopupMenu connectedPopup;
    private static JPopupMenu authPendingPopup;
    private static JPopupMenu sessionPopup;
    private static final List<SessionTarget> sessionTargets = new ArrayList<>();
    private static int sessionCursor;
    private static KeyEventDispatcher sessionKeys;
    /** 关掉列表后，紧接着的那下字母不要进输入框。 */
    private static char sessionSwallowTyped;

    /** 「在线」列表里的一台。快捷键作用在蓝框标出的那台。webWay 为 0 表示 App。 */
    private static final class SessionTarget {
        final boolean pending;
        final String host;
        final JPanel row;
        final char webWay;
        final int webIndex;

        SessionTarget(boolean pending, String host, JPanel row) {
            this(pending, host, row, (char) 0, -1);
        }

        SessionTarget(boolean pending, String host, JPanel row, char webWay, int webIndex) {
            this.pending = pending;
            this.host = host;
            this.row = row;
            this.webWay = webWay;
            this.webIndex = webIndex;
        }
    }
    private static String lanPeerSig = "";
    /** 客户端等待允许时，设备列表上常驻显示的核对码（按 host）。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> authSasByHost =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 正在等授权的地址。再点同一台取消，含请求还没发出去的那一下。 */
    private static volatile String authWaitHost;
    private static volatile boolean authCancelEarly;
    private static String connectedSig = "";
    private static String authPendingSig = "";
    private static Timer lanPeerTimer;
    /** 探测中标题前的呼吸灯（对齐 Android） */
    private static JLabel lanProbeLight;
    private static Timer lanProbeBreathTimer;
    private static float lanProbeBreathPhase;
    /** 待授权：同 IP 只保留最新一条，不弹窗。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, PendingAuth> authPendingByHost =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedDeque<Long>> authHitsByHost =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 拒绝时刻。剩余时间 = 这个时刻 + 当前冷却设置，改短马上生效。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> authCooldownUntil =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int AUTH_PENDING_MAX = 16;

    private static final class PendingAuth {
        final LanAuth.Request req;
        final long at;
        final java.security.KeyPair serverKp;
        final byte[] serverPub;
        final String sas;

        PendingAuth(LanAuth.Request req, long at, java.security.KeyPair serverKp, byte[] serverPub, String sas) {
            this.req = req;
            this.at = at;
            this.serverKp = serverKp;
            this.serverPub = serverPub;
            this.sas = sas == null ? "" : sas;
        }
    }
    /** 仅程序首次启动时尝试自动开服，切换角色/小窗重建不再触发 */
    private static boolean autoStartPending = true;
    private static final DefaultTableModel fileModel = new DefaultTableModel(
            new Object[]{"序号", "文件名", "所在路径", "大小"}, 0) {
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
    private static JButton buttonInbox;
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
    /** 点「二维码」手动打开后，有客户端连上也不自动关。启动时弹出的二维码会在连上后关掉。 */
    private static boolean qrKeepOpen;
    private static boolean windowInputHooks;

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
        WebBridgeInbox.setWatch(new WebBridgeInbox.Watch() {
            @Override
            public void progress(String name, long done, long total) {
                FILE_IO.onProgress(true, name, done, total, 1, 1);
            }

            @Override
            public void finished() {
                SwingUtilities.invokeLater(TextSendMain::endTransferUi);
            }

            @Override
            public void aborted(String name) {
                SwingUtilities.invokeLater(() -> {
                    endTransferUi();
                    flashStatus(name == null || name.isBlank() ? "已停止网页传输" : "已停止网页传输 " + name);
                });
            }

            @Override
            public void paused(String name) {
                SwingUtilities.invokeLater(() -> {
                    endTransferUi();
                    flashStatus(name == null || name.isBlank() ? "手机已停止传输" : "手机已停止 " + name);
                });
            }
        });
        LinuxWmClass.initIfNeeded();
        UserConfig.load();
        LanDiscover.boot();
        serverListenPort = String.valueOf(UserConfig.getListenPort());
        miniMode = UserConfig.isPreferMini();
        String heuristicIp = getIP();
        String listenIp = resolveListenIp(heuristicIp);
        preferIpAddr = listenIp + ":" + serverListenPort;
        SwingUtilities.invokeLater(() -> {
            AppIcons.applyToTaskbar();
            applyLookAndFeelForMode();
            buildUi(true);
            installWindowInputHooks();
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
        selectListenIpInCombo(preferIpAddr != null ? preferIpAddr.split(":")[0] : null);
        fieldPort = new JTextField(serverListenPort, 7);
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
        labelScale.setToolTipText("鼠标放在这里滚轮缩放；Ctrl+滚轮细调 0.1%；双击恢复 100%");
        installScaleWheel(labelScale);
        labelScale.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 2) {
                    applyUiScale(1f);
                }
            }
        });

        buttonStart = new JButton(server ? "启动" : "连接");
        buttonRole = new JButton(server ? "客户端" : "服务端");
        buttonClients = new JButton("在线 0");
        buttonClients.setToolTipText("待授权与已连接。点开可接受、拒绝、拉黑、踢下线");
        buttonClients.addActionListener(e -> showSessionPopup());
        buttonSend = new JButton("发送");
        buttonFile = new JButton("文件");
        buttonQr = new JButton("二维码");
        buttonWeb = new JButton(webBridgeAnyRunning() ? "兼容中" : "兼容模式");
        buttonWeb.setToolTipText("给没装 TextSend 的手机用，比如 iPhone 浏览器。A 明文、B 加密、C 证书同一端口，同时只开一档。安卓和电脑客户端仍走原来的二维码。");
        buttonWeb.addActionListener(e -> showWebBridge());
        buttonHelp = new JButton("帮助");
        buttonHelp.setToolTipText("查看操作说明与快捷键");
        buttonHelp.addActionListener(e -> showHelp());
        buttonSettings = new JButton("设置");
        buttonSettings.setToolTipText("局域网探测、授权限流/冷却/黑名单等");
        buttonSettings.addActionListener(e -> showSettings());
        buttonRole.setToolTipText("在服务端 / 客户端之间切换（已连接时不可用）");
        buttonSend.setToolTipText("发送文字；若列表有选中文件则发文件。按「发到」规则投送（只发 App 绝不进网页）。");
        buttonFile.setToolTipText("选择文件加入待发列表");
        buttonQr.setToolTipText("显示连接二维码（服务端）；手动打开后连上也不自动关");
        toggleMini.setToolTipText("置顶小窗 / 恢复大窗");
        ToolTipManager.sharedInstance().setInitialDelay(350);
        ToolTipManager.sharedInstance().setDismissDelay(12_000);

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
        buttonQr.addActionListener(e -> {
            qrKeepOpen = true;
            showQr();
        });

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
        maybeAutoStartServer();
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

    /** 按数字宽度定框。Windows L&F 下 setPreferredSize 像素值常被列宽盖掉，必须同时 setColumns。 */
    private static void sizeDigitField(JTextField f, int digits) {
        f.setColumns(digits);
        FontMetrics fm = f.getFontMetrics(f.getFont());
        Insets in = f.getInsets();
        int w = fm.charWidth('0') * digits + in.left + in.right + fm.charWidth('0');
        int h = Math.max(UserConfig.s(28), fm.getHeight() + in.top + in.bottom);
        Dimension d = new Dimension(w, h);
        f.setPreferredSize(d);
        f.setMinimumSize(d);
        f.setMaximumSize(d);
    }

    private static void wireServerStartButton() {
        for (var l : buttonStart.getMouseListeners()) {
            buttonStart.removeMouseListener(l);
        }
        for (var l : buttonStart.getActionListeners()) {
            buttonStart.removeActionListener(l);
        }
        buttonStart.setRolloverEnabled(true);
        buttonStart.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                buttonStart.getModel().setRollover(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                buttonStart.getModel().setRollover(false);
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
                // 不用 mouseClicked：Windows 上按下后鼠标稍动/按钮跳 1px 就不会触发，像卡住。
                if (startLongPressTriggered) {
                    return;
                }
                int b = e.getButton();
                if (b != MouseEvent.BUTTON1 && b != MouseEvent.BUTTON2 && b != MouseEvent.BUTTON3) {
                    return;
                }
                if (!buttonStart.isEnabled() || !buttonStart.contains(e.getPoint())) {
                    return;
                }
                maxConnection = b == MouseEvent.BUTTON2 ? UserConfig.getMaxClients() : 1;
                if (isServerRunning()) {
                    stopServer();
                } else {
                    if (!miniMode) {
                        syncPortFromField();
                    }
                    startServer(b == MouseEvent.BUTTON1);
                }
            }
        });
        applyActionTooltips();
    }

    /** 多按键/多手势按钮的悬停说明。 */
    private static void applyActionTooltips() {
        if (buttonStart != null) {
            if (isServerMode) {
                if (isServerRunning()) {
                    buttonStart.setToolTipText("停止服务（已连接客户端会断开）");
                } else {
                    buttonStart.setToolTipText("<html>左键：启动（最多 1 人）并打开二维码<br>"
                            + "中键：最多 " + UserConfig.getMaxClients() + " 人（设置里可改）<br>"
                            + "右键：启动但不弹二维码<br>"
                            + "长按左键：改端口</html>");
                }
            } else {
                boolean busy = isClientConnected || clientHandshaking;
                buttonStart.setToolTipText(busy ? "断开连接" : "连接（粘贴 ts://… 后点，或点探测到的设备请求授权）");
            }
        }
        if (buttonBrowse != null) {
            buttonBrowse.setToolTipText("<html>左键：选文件<br>Shift+左键：打勾选多个文件夹<br>右键：系统文件选择框</html>");
        }
        if (buttonSend != null && !miniMode) {
            buttonSend.setToolTipText("发送文字；列表有选中则发文件（Ctrl+Enter 同效）。按「发到」规则投送（只发 App 绝不进网页）。");
        }
        if (buttonQr != null) {
            buttonQr.setToolTipText(isServerMode
                    ? (UserConfig.isPopupsCloseOnFocusLost()
                    ? "显示二维码；失焦或 Esc 关闭；手动打开后连上也不因连上而关"
                    : "显示二维码；Esc 关闭；手动打开后连上也不自动关")
                    : "客户端模式无二维码");
        }
        if (buttonRole != null) {
            buttonRole.setToolTipText("切换服务端 / 客户端（传输中不可用）");
        }
        if (buttonSettings != null) {
            buttonSettings.setToolTipText("局域网探测、授权限流/冷却/黑名单等");
        }
        if (buttonHelp != null) {
            buttonHelp.setToolTipText("操作说明与快捷键");
        }
        if (toggleMini != null) {
            toggleMini.setToolTipText(miniMode ? "恢复大窗" : "置顶小窗");
        }
        if (buttonFile != null) {
            buttonFile.setToolTipText("选择文件加入待发");
        }
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
        AppIcons.applyTo(frame);
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
        prefillClientConnection();
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
            fixToolbarButton(buttonSettings, 64);
            fixToolbarButton(toggleMini, 96);
            fixToolbarButton(buttonRole, 80);
            fixToolbarButton(buttonStart, 72);
            fixToolbarButton(buttonQr, 72);
            fixToolbarButton(buttonSend, 72);
            actions.add(buttonHelp);
            actions.add(buttonSettings);
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
                sizeDigitField(fieldPort, 7);
                netLeft.add(comboIps);
                netLeft.add(new JLabel(":"));
                netLeft.add(fieldPort);
                checkAutoStart = new JCheckBox("启动即开服");
                checkAutoStart.setOpaque(false);
                checkAutoStart.setToolTipText("打开程序后自动启动服务端（默认关）；不自动弹出二维码");
                checkAutoStart.setSelected(UserConfig.isAutoStartServer());
                checkAutoStart.addActionListener(e -> UserConfig.setAutoStartServer(checkAutoStart.isSelected()));
                netLeft.add(checkAutoStart);
                net.add(netLeft, BorderLayout.CENTER);
                if (buttonWeb != null) {
                    fixToolbarButton(buttonWeb, 96);
                    net.add(buttonWeb, BorderLayout.EAST);
                }
                comboIps.setPreferredSize(new Dimension(UserConfig.s(420), UserConfig.s(28)));
                comboIps.setMinimumSize(new Dimension(UserConfig.s(280), UserConfig.s(28)));
                body.add(net);

                JPanel pinRow = new JPanel(new BorderLayout(UserConfig.s(8), 0));
                pinRow.setOpaque(false);
                pinRow.setAlignmentX(Component.LEFT_ALIGNMENT);
                pinRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, UserConfig.s(28)));
                JPanel pinLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, UserConfig.s(6), 0));
                pinLeft.setOpaque(false);
                checkCustomPin = new JCheckBox("固定 PIN");
                checkCustomPin.setOpaque(false);
                checkCustomPin.setToolTipText("开启后每次启动服务端使用同一 8 位 PIN（默认关，随机生成）");
                fieldCustomPin = new JTextField(UserConfig.getCustomPin(), 10);
                fieldCustomPin.setFont(fieldCustomPin.getFont().deriveFont((float) UserConfig.s(13)));
                fieldCustomPin.setToolTipText("8 位数字");
                sizeDigitField(fieldCustomPin, 10);
                checkCustomPin.setSelected(UserConfig.isCustomPinEnabled());
                ((AbstractDocument) fieldCustomPin.getDocument()).setDocumentFilter(new DocumentFilter() {
                    @Override
                    public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                            throws javax.swing.text.BadLocationException {
                        if (text != null) {
                            StringBuilder digits = new StringBuilder();
                            for (int i = 0; i < text.length(); i++) {
                                char c = text.charAt(i);
                                if (c >= '0' && c <= '9') {
                                    digits.append(c);
                                }
                            }
                            text = digits.toString();
                        }
                        String cur = fb.getDocument().getText(0, fb.getDocument().getLength());
                        String next = cur.substring(0, offset) + (text == null ? "" : text) + cur.substring(offset + length);
                        if (next.length() <= Protocol.PIN_LEN) {
                            super.replace(fb, offset, length, text, attrs);
                        }
                    }
                });
                checkCustomPin.addActionListener(e -> {
                    boolean on = checkCustomPin.isSelected();
                    if (on && !UserConfig.isValidPin(fieldCustomPin.getText().trim())) {
                        checkCustomPin.setSelected(false);
                        flashStatus("请先输入 8 位数字，再勾选固定 PIN");
                        return;
                    }
                    UserConfig.setCustomPinEnabled(on);
                    if (on) {
                        persistCustomPin();
                    }
                });
                fieldCustomPin.addActionListener(e -> persistCustomPin());
                fieldCustomPin.addFocusListener(new FocusAdapter() {
                    @Override
                    public void focusLost(FocusEvent e) {
                        persistCustomPin();
                    }
                });
                pinLeft.add(checkCustomPin);
                pinLeft.add(fieldCustomPin);
                comboSendTarget = new JComboBox<>(new String[]{"只发 App", "只发兼容", "发给所有"});
                comboSendTarget.setSelectedIndex(UserConfig.getSendTargetIndex());
                comboSendTarget.setFont(comboSendTarget.getFont().deriveFont((float) UserConfig.s(12)));
                comboSendTarget.setToolTipText("<html>默认发给所有：App 和网页都收到<br>"
                        + "只发 App：只给已连接的 App，绝不发网页（无客户端则提示，不回落）<br>"
                        + "只发兼容：只给网页<br>"
                        + "发给所有时，App 服务没开仍可发网页。状态栏显示兼容端口和页面数。</html>");
                comboSendTarget.addActionListener(e -> {
                    if (comboSendTarget != null) {
                        UserConfig.setSendTargetIndex(comboSendTarget.getSelectedIndex());
                    }
                });
                JLabel sendTo = new JLabel("发到");
                sendTo.setForeground(TEXT);
                sendTo.setFont(sendTo.getFont().deriveFont((float) UserConfig.s(13)));
                pinLeft.add(sendTo);
                comboSendTarget.setPrototypeDisplayValue("发给所有");
                Dimension sendBox = new Dimension(UserConfig.s(156), UserConfig.s(28));
                comboSendTarget.setPreferredSize(sendBox);
                comboSendTarget.setMinimumSize(sendBox);
                comboSendTarget.setMaximumSize(sendBox);
                JPanel sendHold = new JPanel(new BorderLayout());
                sendHold.setOpaque(false);
                sendHold.setPreferredSize(sendBox);
                sendHold.setMinimumSize(sendBox);
                sendHold.setMaximumSize(sendBox);
                sendHold.add(comboSendTarget, BorderLayout.CENTER);
                pinLeft.add(sendHold);
                pinRow.add(pinLeft, BorderLayout.CENTER);
                fixToolbarButton(buttonClients, 148);
                updateClientsButton();
                JPanel clientsHold = new JPanel(new GridBagLayout());
                clientsHold.setOpaque(false);
                clientsHold.add(buttonClients);
                pinRow.add(clientsHold, BorderLayout.EAST);
                body.add(pinRow);
                lanPeerBox = null;
                authPendingBox = new JPanel();
                authPendingSig = "";
                authPendingEntryBtn = null;
                authPendingBox.setOpaque(false);
                authPendingBox.setLayout(new BoxLayout(authPendingBox, BoxLayout.Y_AXIS));
                authPendingBox.setAlignmentX(Component.LEFT_ALIGNMENT);
                authPendingBox.setVisible(false);
                body.add(authPendingBox);
                connectedBox = new JPanel();
                connectedSig = "";
                connectedEntryBtn = null;
                connectedBox.setOpaque(false);
                connectedBox.setLayout(new BoxLayout(connectedBox, BoxLayout.Y_AXIS));
                connectedBox.setAlignmentX(Component.LEFT_ALIGNMENT);
                connectedBox.setVisible(false);
                body.add(connectedBox);
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
                ensureLanPeerTimer();
                refreshAuthPending();
                refreshConnectedClients();
            } else {
                JLabel tip = new JLabel("<html>粘贴 <b>ts://IP/k...</b> 后点连接。开了局域网探测时，点设备会请求服务端授权（免输 PIN）。</html>");
                tip.setForeground(MUTED);
                tip.setFont(tip.getFont().deriveFont((float) UserConfig.s(12)));
                tip.setAlignmentX(Component.LEFT_ALIGNMENT);
                tip.setBorder(new EmptyBorder(UserConfig.s(4), 0, UserConfig.s(4), 0));
                body.add(tip);
                JPanel clientOpts = new JPanel(new FlowLayout(FlowLayout.LEFT, UserConfig.s(6), 0));
                clientOpts.setOpaque(false);
                clientOpts.setAlignmentX(Component.LEFT_ALIGNMENT);
                checkRememberClient = new JCheckBox("记住上次连接");
                checkRememberClient.setOpaque(false);
                checkRememberClient.setToolTipText("下次进入客户端模式自动填入上次成功的 IP、端口和 PIN（默认关，不记长密钥）");
                checkRememberClient.setSelected(UserConfig.isRememberLastClient());
                checkRememberClient.addActionListener(e -> {
                    boolean on = checkRememberClient.isSelected();
                    UserConfig.setRememberLastClient(on);
                    if (!on) {
                        UserConfig.clearClientConnection();
                    }
                });
                clientOpts.add(checkRememberClient);
                body.add(clientOpts);
                connectedBox = null;
                authPendingBox = null;
                hideAuthPendingPopup();
                clearAuthPending(false);
                lanPeerBox = new JPanel();
                lanPeerSig = "";
                lanPeerBox.setOpaque(false);
                lanPeerBox.setLayout(new BoxLayout(lanPeerBox, BoxLayout.Y_AXIS));
                lanPeerBox.setAlignmentX(Component.LEFT_ALIGNMENT);
                lanPeerBox.setVisible(UserConfig.isLanDiscover());
                body.add(lanPeerBox);
                body.add(Box.createVerticalStrut(UserConfig.s(6)));
                ensureLanPeerTimer();
                refreshLanPeers();
                prefillClientConnection();
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
            if (buttonInbox != null) {
                fixToolbarButton(buttonInbox, 96);
            }
            fixToolbarButton(buttonRemoveFiles, 72);
            scrollFiles.setAlignmentX(Component.LEFT_ALIGNMENT);
            filePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(filePanel);
            installFileDrop(areaMessage);
            installSmartPaste(areaMessage);
            installSmartPaste(miniTextArea);
            fileTable.setDropMode(DropMode.ON);
            installFileDrop(fileTable);
            installFileDrop(fileTable.getTableHeader());
            installFileDrop(scrollFiles);
            installFileDrop(scrollFiles.getViewport());
            installFileDrop(filePanel);
            installFileDrop(labelFileQueue);
            installFileDrop(body);
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
        applyUiScale(pct / 100f);
    }

    private static void applyUiScale(float next) {
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
            boolean reopenQr = qrDialog != null && qrDialog.isVisible();
            buildUi(isServerMode);
            if (keepLoc != null && frame != null) {
                frame.setLocation(keepLoc);
            }
            if (reopenQr && isServerRunning()) {
                showQr();
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

    /** 小窗共用区：未启动选 IP；启动后显示 PIN（点复制）；有客户端后换成可输入文字 */
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
                    miniTextArea.setToolTipText("输入文字后点发送");
                    miniTextArea.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
                    SwingUtilities.invokeLater(() -> {
                        if (miniMode && miniTextArea != null && miniTextArea.isShowing()) {
                            miniTextArea.requestFocusInWindow();
                        }
                    });
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
                persistListenIp();
                onAdvertiseIpChanged();
            });
        }
    }

    /** 服务已启动时换网卡 IP：连接串和已打开的二维码跟着变（监听仍是 0.0.0.0）。 */
    private static void onAdvertiseIpChanged() {
        if (!isServerMode || !isServerRunning() || pairingMaterial == null || comboIps == null) {
            return;
        }
        String ip = (String) comboIps.getSelectedItem();
        if (ip == null || ip.isBlank()) {
            return;
        }
        String uri = pairingMaterial.toUri(ip, getServerListenPort());
        if (uri.equals(lastConnectUri)) {
            return;
        }
        lastConnectUri = uri;
        System.out.println("Log: URI=" + lastConnectUri);
        if (qrDialog != null && qrDialog.isVisible()) {
            showQr();
        }
        flashStatus("二维码已换成 " + ip);
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
            if (buttonWeb != null) {
                buttonWeb.setText(webBridgeAnyRunning() ? "兼容中" : "兼容模式");
            }
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
                buttonSend.setToolTipText("已连接客户端数；点击仍可发送");
            } else if (active) {
                buttonSend.setText("发送");
                buttonSend.setToolTipText("发送文字");
            } else {
                buttonSend.setText("切换");
                buttonSend.setToolTipText("切换 服务端/客户端");
            }
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
            wireIpComboTooltips();
        }
        applyActionTooltips();
        if (buttonBrowse != null) {
            buttonBrowse.setEnabled(true);
        }
        if (buttonRemoveFiles != null) {
            buttonRemoveFiles.setEnabled(fileTable != null && fileTable.getSelectedRowCount() > 0);
        }
        buttonRole.setEnabled(!active);
        buttonRole.setVisible(!miniMode);
        fieldPort.setEnabled(isServerMode && !isServerRunning());
        comboIps.setEnabled(isServerMode);
        toggleMini.setSelected(miniMode);
        updateClientsButton();
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
        if (UserConfig.isWebAppExclusive()) {
            stopOtherWebBridge((char) 0);
        }
        pairingMaterial = PairingMaterial.forSession(
                UserConfig.isCustomPinEnabled(), UserConfig.getCustomPin());
        connectedClients = 0;
        String selectedIp = (String) comboIps.getSelectedItem();
        persistListenIp();
        lastConnectUri = pairingMaterial.toUri(selectedIp, getServerListenPort());
        System.out.println("Log: PIN=" + pairingMaterial.pin);
        System.out.println("Log: URI=" + lastConnectUri);

        setServerRunning(true);
        setTextResetUndo(areaMessage, "");
        miniShowingPin = miniMode;
        inputShowingCount = false;
        copyText(lastConnectUri);
        flashStatus("已复制完整连接串 ts://…");

        qrKeepOpen = false;
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
        if (prev == 0 && count > 0 && !qrKeepOpen) {
            closeQrDialog();
        }
        refreshRunningChrome();
    }

    private static void clearCountPlaceholder() {
        inputShowingCount = false;
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
        hideAuthPendingPopup();
        clearAuthPending(true);
        closeQrDialog();
        fileBusy = false;
        resetSendQueue();
        endTransferUi();
        TsServer.cancelFilesCurrent();
        TsServer.stopCurrent();
        setTextResetUndo(areaMessage, "");
        setTextResetUndo(miniTextArea, "");
        refreshAuthPending();
        refreshConnectedClients();
        refreshRunningChrome();
    }

    private static void prefillClientConnection() {
        if (isServerMode || !UserConfig.isRememberLastClient()) {
            return;
        }
        String host = UserConfig.getClientHost();
        String pin = UserConfig.getClientPin();
        if (host.isBlank() || !UserConfig.isValidPin(pin)) {
            return;
        }
        String prefill = TsUri.format(host, UserConfig.getClientPort(), pin);
        setTextResetUndo(areaMessage, prefill);
        setTextResetUndo(miniTextArea, prefill);
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
                    if (UserConfig.isRememberLastClient() && uri.pinMode) {
                        UserConfig.setClientConnection(uri.host, uri.port, uri.pin());
                    }
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
            boolean bridgeUp = webBridgeReady();
            if (!isServerRunning() && !bridgeUp) {
                flashStatus(webBridgeAnyRunning() ? "兼容模式会话已过期" : "服务未运行");
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
                    SendRoute route = routeSend();
                    if (route.block != null) {
                        onSendFinished(false, text, route.block);
                        return;
                    }
                    int delivered = 0;
                    String fail = null;
                    if (route.app) {
                        int n = TsServer.sendToAllCurrent(text);
                        if (n > 0) {
                            delivered += n;
                        } else if (!route.web) {
                            fail = n < 0 ? "服务未运行" : "没有已连接的客户端";
                        }
                    }
                    if (route.web) {
                        int n = pushTextToWebBridge(text);
                        if (n > 0) {
                            delivered++;
                        } else if (delivered == 0) {
                            fail = n < 0 ? "文字超过 64KB" : (webBridgeAnyRunning() ? "兼容模式会话已过期" : "兼容模式未开");
                        }
                    }
                    onSendFinished(delivered > 0, text, delivered > 0 ? null : fail);
                    return;
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
        String web = webBridgeStatusBrief();
        if (isServerMode) {
            String base;
            if (isServerRunning()) {
                base = "运行中  ·  端口 " + serverListenPort + "  ·  客户端 " + connectedClients;
            } else {
                base = "就绪  ·  端口 " + serverListenPort;
            }
            return web.isEmpty() ? base : base + "  ·  " + web;
        }
        if (isClientConnected) {
            return web.isEmpty() ? "已连接" : "已连接  ·  " + web;
        }
        if (clientHandshaking) {
            return "连接中 — 正在密钥交换";
        }
        return web.isEmpty() ? "未连接 — 粘贴 ts:// 后点连接" : web;
    }

    private static boolean webBridgeAnyRunning() {
        return WebBridge.isRunning() || WebBridgeA.isRunning();
    }

    private static boolean webBridgeReady() {
        return (WebBridge.isRunning() && WebBridge.hasSession())
                || (WebBridgeA.isRunning() && WebBridgeA.hasSession());
    }

    /** 当前实际在跑的档；互斥，不会同时开。 */
    private static char webBridgeActiveWay() {
        if (WebBridgeA.isRunning()) {
            return 'A';
        }
        if (WebBridge.isRunning()) {
            return WebBridge.isTls() ? 'C' : 'B';
        }
        return 0;
    }

    private static void stopOtherWebBridge(char keep) {
        if (WebBridge.isRunning()) {
            char way = WebBridge.isTls() ? 'C' : 'B';
            if (keep != way) {
                WebBridge.stop();
            }
        }
        if (keep != 'A' && WebBridgeA.isRunning()) {
            WebBridgeA.stop();
        }
    }

    /** 主界面发送：推到当前唯一在跑的网页桥。 */
    private static int pushTextToWebBridge(String text) throws Exception {
        if (WebBridge.isRunning() && WebBridge.hasSession()) {
            return WebBridge.pushText(text);
        }
        if (WebBridgeA.isRunning() && WebBridgeA.hasSession()) {
            return WebBridgeA.pushText(text);
        }
        return 0;
    }

    /** 没有 v1 客户端时，把文件交给当前网页桥。返回值同 {@link WebBridge#pushFile}。 */
    private static int pushFileToWebBridge(Path path, String tree, String rel,
                                           BooleanSupplier cancelled, int seq, int of) throws Exception {
        WebBridgeOutbox.Progress progress = (name, done, total) ->
                FILE_IO.onProgress(false, name, done, total, seq, of);
        if (WebBridge.isRunning() && WebBridge.hasSession()) {
            return WebBridge.pushFile(path, tree, rel, cancelled, progress);
        }
        if (WebBridgeA.isRunning() && WebBridgeA.hasSession()) {
            return WebBridgeA.pushFile(path, tree, rel, cancelled, progress);
        }
        return 0;
    }

    /** 这次发送走 App、网页，或两边都走。block 非空就不要发。 */
    private static final class SendRoute {
        final boolean app;
        final boolean web;
        final String block;

        SendRoute(boolean app, boolean web, String block) {
            this.app = app;
            this.web = web;
            this.block = block;
        }
    }

    private static SendRoute routeSend() {
        if (!isServerMode) {
            return new SendRoute(false, false, null);
        }
        boolean appUp = isServerRunning();
        int alive = appUp ? TsServer.aliveCountCurrent() : -1;
        boolean appClients = alive > 0;
        boolean web = webBridgeReady();
        boolean webProc = webBridgeAnyRunning();
        String target = UserConfig.getSendTarget();
        // 只发兼容：绝不走 App
        if ("web".equals(target)) {
            if (web) {
                return new SendRoute(false, true, null);
            }
            return new SendRoute(false, false, webProc ? "兼容模式会话已过期" : "兼容模式未开");
        }
        // 只发 App：绝不走网页，避免误发到浏览器
        if ("app".equals(target)) {
            if (appClients) {
                return new SendRoute(true, false, null);
            }
            if (!appUp) {
                return new SendRoute(false, false, "服务未运行");
            }
            return new SendRoute(false, false, "没有已连接的客户端");
        }
        // 发给所有：App 服务未开时仍可只发网页
        if (!appUp) {
            if (web) {
                return new SendRoute(false, true, null);
            }
            return new SendRoute(false, false, webProc ? "兼容模式会话已过期" : "服务未运行");
        }
        if (!appClients && !web) {
            return new SendRoute(false, false, webProc ? "兼容模式会话已过期" : "没有已连接的客户端");
        }
        return new SendRoute(appClients, web, null);
    }

    private static String webBridgeStatusBrief() {
        if (WebBridge.isRunning()) {
            char way = WebBridge.isTls() ? 'C' : 'B';
            return "Web " + way + " :" + WebBridge.port()
                    + (WebBridge.hasSession() ? (" ×" + WebBridge.listenerCount()) : " 过期");
        }
        if (WebBridgeA.isRunning()) {
            return "Web A :" + WebBridgeA.port()
                    + (WebBridgeA.hasSession() ? (" ×" + WebBridgeA.listenerCount()) : " 过期");
        }
        return "";
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

    /**
     * 授权黑名单：固定约 5 行高度的列表 + 通栏条目（左 IP、右 ×）+ 回车添加。
     */
    private static final class AuthBlacklistEditor {
        final JPanel root = new JPanel();
        private final StretchWidthPanel rows = new StretchWidthPanel();
        private final JTextField field = new JTextField();
        private final java.util.LinkedHashSet<String> items = new java.util.LinkedHashSet<>();
        private String editing;
        private final int rowH = UserConfig.s(28);
        private final Color line = new Color(0x9A9A9A);
        private final Color rowLine = new Color(0xD0D0D0);

        AuthBlacklistEditor(java.util.Collection<String> initial) {
            this(initial, 5);
        }

        AuthBlacklistEditor(java.util.Collection<String> initial, int visibleRows) {
            root.setLayout(new BorderLayout(0, UserConfig.s(6)));
            root.setOpaque(false);
            root.setAlignmentX(Component.LEFT_ALIGNMENT);
            int listH = rowH * Math.max(2, visibleRows) + UserConfig.s(2);
            Dimension listSize = new Dimension(UserConfig.s(240), listH);

            rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
            rows.setOpaque(true);
            rows.setBackground(Color.WHITE);
            if (initial != null) {
                for (String h : initial) {
                    if (h != null && !h.isBlank()) {
                        items.add(h.trim().toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }

            JScrollPane scroll = new JScrollPane(rows);
            scroll.setBorder(BorderFactory.createLineBorder(line));
            scroll.getViewport().setBackground(Color.WHITE);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.getVerticalScrollBar().setUnitIncrement(rowH);
            scroll.setPreferredSize(listSize);
            scroll.setMinimumSize(new Dimension(UserConfig.s(120), listH));
            scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, listH));

            field.setPreferredSize(new Dimension(UserConfig.s(240), rowH));
            field.setMinimumSize(new Dimension(UserConfig.s(120), rowH));
            field.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowH));
            field.setBackground(Color.WHITE);
            field.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(line),
                    new EmptyBorder(UserConfig.s(3), UserConfig.s(8), UserConfig.s(3), UserConfig.s(8))));
            field.setToolTipText("输入 IPv4/IPv6 后回车添加；点上面条目可改，再回车确认。");
            field.putClientProperty("JTextField.placeholderText", "输入 IP，回车添加");
            field.addActionListener(e -> commitField());
            field.addKeyListener(new java.awt.event.KeyAdapter() {
                @Override
                public void keyPressed(java.awt.event.KeyEvent e) {
                    if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE) {
                        cancelEdit();
                    }
                }
            });

            JLabel tip = new JLabel("点条目编辑，右侧 × 整条删除");
            tip.setForeground(MUTED);
            tip.setFont(tip.getFont().deriveFont((float) UserConfig.s(11)));

            JPanel south = new JPanel();
            south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
            south.setOpaque(false);
            field.setAlignmentX(Component.LEFT_ALIGNMENT);
            tip.setAlignmentX(Component.LEFT_ALIGNMENT);
            south.add(field);
            south.add(Box.createVerticalStrut(UserConfig.s(3)));
            south.add(tip);

            root.add(scroll, BorderLayout.CENTER);
            root.add(south, BorderLayout.SOUTH);
            int blockH = listH + rowH + UserConfig.s(28);
            root.setPreferredSize(new Dimension(UserConfig.s(240), blockH));
            root.setMinimumSize(new Dimension(UserConfig.s(120), blockH));
            root.setMaximumSize(new Dimension(Integer.MAX_VALUE, blockH + UserConfig.s(8)));
            rebuild();
        }

        void clear() {
            items.clear();
            editing = null;
            field.setText("");
            rebuild();
        }

        String text() {
            return String.join("\n", items);
        }

        /** 输入框里还没回车的内容。用来判断设置有没有改动。 */
        String pending() {
            return field.getText() == null ? "" : field.getText();
        }

        void commitField() {
            String raw = field.getText() == null ? "" : field.getText().trim();
            if (raw.isEmpty()) {
                cancelEdit();
                return;
            }
            String next = raw;
            if (next.startsWith("[") && next.endsWith("]") && next.length() > 2) {
                next = next.substring(1, next.length() - 1);
            }
            if (next.length() > 7 && next.regionMatches(true, 0, "::ffff:", 0, 7)) {
                next = next.substring(7);
            }
            next = next.toLowerCase(java.util.Locale.ROOT);
            if (editing != null) {
                items.remove(editing);
                editing = null;
            }
            items.add(next);
            field.setText("");
            rebuild();
        }

        private void cancelEdit() {
            editing = null;
            field.setText("");
            rebuild();
        }

        private void startEdit(String host) {
            editing = host;
            field.setText(host);
            field.requestFocusInWindow();
            field.selectAll();
            rebuild();
        }

        private void remove(String host) {
            items.remove(host);
            if (host.equals(editing)) {
                editing = null;
                field.setText("");
            }
            rebuild();
        }

        private void rebuild() {
            rows.removeAll();
            boolean any = false;
            for (String host : items) {
                if (host.equals(editing)) {
                    continue;
                }
                any = true;
                rows.add(makeRow(host));
            }
            if (!any) {
                JLabel empty = new JLabel("  （空）");
                empty.setForeground(MUTED);
                empty.setPreferredSize(new Dimension(UserConfig.s(360), rowH));
                empty.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowH));
                empty.setAlignmentX(Component.LEFT_ALIGNMENT);
                rows.add(empty);
            }
            rows.revalidate();
            rows.repaint();
            root.revalidate();
            root.repaint();
        }

        private JPanel makeRow(String host) {
            JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(true);
            row.setBackground(Color.WHITE);
            row.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, rowLine));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowH));
            row.setPreferredSize(new Dimension(UserConfig.s(360), rowH));
            row.setMinimumSize(new Dimension(UserConfig.s(120), rowH));

            JLabel ipLab = new JLabel(host);
            ipLab.setBorder(new EmptyBorder(0, UserConfig.s(8), 0, UserConfig.s(4)));
            ipLab.setFont(ipLab.getFont().deriveFont(Font.PLAIN, (float) UserConfig.s(12)));
            ipLab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            ipLab.setToolTipText("点击编辑");
            ipLab.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    startEdit(host);
                }
            });

            JLabel del = new JLabel("×", SwingConstants.CENTER);
            del.setPreferredSize(new Dimension(UserConfig.s(36), rowH));
            del.setFont(del.getFont().deriveFont(Font.PLAIN, (float) UserConfig.s(16)));
            del.setForeground(new Color(0x555555));
            del.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            del.setToolTipText("删除这条");
            del.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    remove(host);
                }
            });

            row.add(ipLab, BorderLayout.CENTER);
            row.add(del, BorderLayout.EAST);
            return row;
        }
    }

    /** JScrollPane 内列表拉满视口宽度。 */
    private static final class StretchWidthPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return UserConfig.s(28);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(UserConfig.s(56), visible.height - UserConfig.s(16));
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static void showSettings() {
        StretchWidthPanel panel = new StretchWidthPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(UserConfig.s(10), UserConfig.s(12), UserConfig.s(10), UserConfig.s(12)));

        JCheckBox discover = new JCheckBox("局域网探测");
        discover.setAlignmentX(Component.LEFT_ALIGNMENT);
        discover.setSelected(UserConfig.isLanDiscover());
        discover.setToolTipText("默认关。打开后可被发现，并允许客户端请求临时授权接入（须本机点允许）。");

        JCheckBox announce = new JCheckBox("主动广播在线");
        announce.setAlignmentX(Component.LEFT_ALIGNMENT);
        announce.setSelected(UserConfig.isLanDiscoverAnnounce());
        announce.setEnabled(UserConfig.isLanDiscover());
        announce.setToolTipText("默认开，大约每 1 秒广播一次。关掉后服务端不主动喊，只在客户端发探测时回复。");

        JCheckBox pause = new JCheckBox("有连接时关闭探测");
        pause.setAlignmentX(Component.LEFT_ALIGNMENT);
        pause.setSelected(UserConfig.isLanDiscoverPause());
        pause.setEnabled(UserConfig.isLanDiscover());
        pause.setToolTipText("默认开。已有连接时暂停广播和探测。");

        JCheckBox followPort = new JCheckBox("探测端口跟随连接端口");
        followPort.setAlignmentX(Component.LEFT_ALIGNMENT);
        followPort.setSelected(UserConfig.isLanDiscoverPortFollowListen());
        followPort.setEnabled(UserConfig.isLanDiscover());
        followPort.setToolTipText("默认开。开着时探测 UDP 口等于主界面连接端口，输入框禁用；关掉后才能单独填探测端口。");

        JPanel portRow = new JPanel(new FlowLayout(FlowLayout.LEFT, UserConfig.s(6), 0));
        portRow.setOpaque(false);
        portRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        portRow.add(new JLabel("探测端口"));
        JTextField fieldDiscoPort = new JTextField(String.valueOf(UserConfig.getLanDiscoverPort()), 6);
        fieldDiscoPort.setEnabled(UserConfig.isLanDiscover() && !UserConfig.isLanDiscoverPortFollowListen());
        sizeDigitField(fieldDiscoPort, 6);
        portRow.add(fieldDiscoPort);

        JPanel maxRow = new JPanel(new FlowLayout(FlowLayout.LEFT, UserConfig.s(6), 0));
        maxRow.setOpaque(false);
        maxRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        maxRow.add(new JLabel("中键启动最多客户端"));
        JTextField fieldMaxClients = new JTextField(String.valueOf(UserConfig.getMaxClients()), 4);
        fieldMaxClients.setToolTipText("1–64。左键启动仍为 1 人；中键启动用此上限。下次启动生效。");
        sizeDigitField(fieldMaxClients, 4);
        maxRow.add(fieldMaxClients);

        JCheckBox qrFocusClose = new JCheckBox("弹出窗口失焦即关");
        qrFocusClose.setAlignmentX(Component.LEFT_ALIGNMENT);
        qrFocusClose.setSelected(UserConfig.isPopupsCloseOnFocusLost());
        qrFocusClose.setToolTipText("默认开。点到别处时，二维码、兼容模式、大图、帮助、设置这些窗口会关掉。主窗口不关。设置有改动时会先问是否保存。Esc 仍可关。");

        JCheckBox exclusive = new JCheckBox("兼容模式与 App 互斥");
        exclusive.setAlignmentX(Component.LEFT_ALIGNMENT);
        exclusive.setSelected(UserConfig.isWebAppExclusive());
        exclusive.setToolTipText("默认关，两个可以同时开。打开后，开网页会停 App 服务，开 App 服务会停网页。");

        JPanel rateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, UserConfig.s(6), 0));
        rateRow.setOpaque(false);
        rateRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        rateRow.add(new JLabel("授权限流（次/分·IP）"));
        JTextField fieldAuthRate = new JTextField(String.valueOf(UserConfig.getAuthRatePerMin()), 4);
        fieldAuthRate.setToolTipText("同一 IP 每分钟最多几条授权请求；超出静默拒绝。1–60，默认 3。");
        sizeDigitField(fieldAuthRate, 4);
        rateRow.add(fieldAuthRate);

        JPanel coolRow = new JPanel(new FlowLayout(FlowLayout.LEFT, UserConfig.s(6), 0));
        coolRow.setOpaque(false);
        coolRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        coolRow.add(new JLabel("拒绝后冷却（秒）"));
        JTextField fieldAuthCool = new JTextField(String.valueOf(UserConfig.getAuthCooldownSec()), 5);
        fieldAuthCool.setToolTipText("点「拒绝」后该 IP 冷却期内静默拒绝。0=不冷却，最大 86400，默认 10。改短对已经在冷却的地址立刻生效。");
        sizeDigitField(fieldAuthCool, 5);
        coolRow.add(fieldAuthCool);

        JLabel blTitle = new JLabel("App 黑名单");
        blTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        blTitle.setToolTipText("只拒绝这个地址的 App 授权和连接。兼容模式不受影响。");
        AuthBlacklistEditor blacklistEditor = new AuthBlacklistEditor(UserConfig.getAuthBlacklist(), 3);
        blacklistEditor.root.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel webBlTitle = new JLabel("兼容模式黑名单");
        webBlTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        webBlTitle.setToolTipText("只拒绝这个地址打开网页。App 不受影响。");
        AuthBlacklistEditor webBlacklistEditor = new AuthBlacklistEditor(UserConfig.getWebBlacklist(), 3);
        webBlacklistEditor.root.setAlignmentX(Component.LEFT_ALIGNMENT);

        JTextArea hint = new JTextArea("平时请扫码。打开探测后：客户端点设备会进「待授权」列表，"
                + "点开后两边显示同一串核对码，对得上再允许。限流、冷却和两份黑名单防骚扰。"
                + "App 黑名单只拦 App，兼容模式黑名单只拦网页。"
                + "默认「探测端口跟随连接端口」，两边都是主界面那个口（连接 TCP，探测 UDP）。"
                + "防火墙若只放行 TCP，还要放行同号 UDP。");
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setEditable(false);
        hint.setOpaque(false);
        hint.setFocusable(false);
        hint.setForeground(MUTED);
        hint.setFont(hint.getFont().deriveFont((float) UserConfig.s(12)));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(null);
        hint.setMinimumSize(new Dimension(UserConfig.s(120), UserConfig.s(48)));
        panel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int w = panel.getWidth() - panel.getInsets().left - panel.getInsets().right;
                if (w < 80) {
                    return;
                }
                if (hint.isPreferredSizeSet() && hint.getPreferredSize().width == w) {
                    return;
                }
                hint.setPreferredSize(null);
                hint.setSize(w, Short.MAX_VALUE);
                int h = Math.max(UserConfig.s(48), hint.getPreferredSize().height);
                hint.setPreferredSize(new Dimension(w, h));
                hint.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
                panel.revalidate();
            }
        });

        Runnable syncDiscoPortUi = () -> {
            boolean on = discover.isSelected();
            boolean follow = followPort.isSelected();
            pause.setEnabled(on);
            announce.setEnabled(on);
            followPort.setEnabled(on);
            fieldDiscoPort.setEnabled(on && !follow);
            if (follow) {
                fieldDiscoPort.setText(String.valueOf(UserConfig.getListenPort()));
            }
        };
        discover.addActionListener(e -> syncDiscoPortUi.run());
        followPort.addActionListener(e -> syncDiscoPortUi.run());

        panel.add(discover);
        panel.add(Box.createVerticalStrut(UserConfig.s(6)));
        panel.add(announce);
        panel.add(Box.createVerticalStrut(UserConfig.s(6)));
        panel.add(pause);
        panel.add(Box.createVerticalStrut(UserConfig.s(6)));
        panel.add(followPort);
        panel.add(Box.createVerticalStrut(UserConfig.s(4)));
        panel.add(portRow);
        panel.add(Box.createVerticalStrut(UserConfig.s(10)));
        panel.add(maxRow);
        panel.add(Box.createVerticalStrut(UserConfig.s(6)));
        panel.add(qrFocusClose);
        panel.add(Box.createVerticalStrut(UserConfig.s(6)));
        panel.add(exclusive);
        panel.add(Box.createVerticalStrut(UserConfig.s(10)));
        panel.add(rateRow);
        panel.add(Box.createVerticalStrut(UserConfig.s(4)));
        panel.add(coolRow);
        panel.add(Box.createVerticalStrut(UserConfig.s(6)));
        panel.add(blTitle);
        panel.add(Box.createVerticalStrut(UserConfig.s(4)));
        panel.add(blacklistEditor.root);
        panel.add(Box.createVerticalStrut(UserConfig.s(8)));
        panel.add(webBlTitle);
        panel.add(Box.createVerticalStrut(UserConfig.s(4)));
        panel.add(webBlacklistEditor.root);
        panel.add(Box.createVerticalStrut(UserConfig.s(8)));
        panel.add(hint);
        panel.add(Box.createVerticalStrut(UserConfig.s(10)));
        JButton resetDefaults = new JButton("恢复默认设置");
        resetDefaults.setAlignmentX(Component.LEFT_ALIGNMENT);
        resetDefaults.setToolTipText("缩放、小窗、监听、固定 PIN、探测和授权都回到默认。正在运行的服务不中断。");
        final boolean[] restored = {false};
        final boolean[] wasMini = {false};
        final float[] oldScale = {1f};
        resetDefaults.addActionListener(e -> {
            int yes = JOptionPane.showConfirmDialog(panel,
                    "界面缩放、小窗、监听端口、启动即开服、固定 PIN、记住的连接、探测和授权都会回到默认。\n"
                            + "正在运行的服务不中断，端口下次启动再生效。",
                    "恢复默认设置", JOptionPane.YES_NO_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (yes != JOptionPane.YES_OPTION) {
                return;
            }
            wasMini[0] = miniMode;
            oldScale[0] = UserConfig.getUiScale();
            UserConfig.resetToDefaults();
            discover.setSelected(false);
            pause.setSelected(true);
            pause.setEnabled(false);
            announce.setSelected(true);
            announce.setEnabled(false);
            followPort.setSelected(true);
            followPort.setEnabled(false);
            fieldDiscoPort.setText("54300");
            fieldDiscoPort.setEnabled(false);
            fieldMaxClients.setText("7");
            qrFocusClose.setSelected(true);
            exclusive.setSelected(false);
            fieldAuthRate.setText("3");
            fieldAuthCool.setText("10");
            blacklistEditor.clear();
            webBlacklistEditor.clear();
            restored[0] = true;
        });
        panel.add(resetDefaults);

        JScrollPane settingsScroll = new JScrollPane(panel);
        settingsScroll.setBorder(BorderFactory.createEmptyBorder());
        settingsScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        settingsScroll.setPreferredSize(new Dimension(UserConfig.s(460), UserConfig.s(520)));
        settingsScroll.getVerticalScrollBar().setUnitIncrement(UserConfig.s(16));
        String opened = settingsSignature(discover, announce, pause, followPort, fieldDiscoPort,
                fieldMaxClients, qrFocusClose, exclusive, fieldAuthRate, fieldAuthCool,
                blacklistEditor, webBlacklistEditor);
        settingsDirty = () -> !opened.equals(settingsSignature(discover, announce, pause, followPort,
                fieldDiscoPort, fieldMaxClients, qrFocusClose, exclusive, fieldAuthRate, fieldAuthCool,
                blacklistEditor, webBlacklistEditor));
        final boolean[] closed = {false};
        settingsAfterClose = () -> {
            if (closed[0]) {
                return;
            }
            closed[0] = true;
            settingsDirty = null;
            settingsApply = null;
            settingsAfterClose = null;
            if (restored[0]) {
                finishRestoreUi(wasMini[0], oldScale[0]);
            }
        };
        JOptionPane pane = new JOptionPane(settingsScroll, JOptionPane.PLAIN_MESSAGE,
                JOptionPane.OK_CANCEL_OPTION);
        if (settingsDialog != null) {
            settingsDialog.dispose();
        }
        settingsDialog = pane.createDialog(frame, "设置 · TextSend");
        settingsDialog.setModalityType(Dialog.ModalityType.MODELESS);
        settingsDialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        settingsDialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeSettingsFromOutside();
            }
        });
        settingsDialog.setResizable(true);
        settingsDialog.setMinimumSize(new Dimension(UserConfig.s(380), UserConfig.s(360)));
        settingsDialog.setSize(new Dimension(UserConfig.s(480), UserConfig.s(640)));
        settingsDialog.setLocationRelativeTo(frame);
        settingsHandled = false;
        settingsApply = () -> saveSettingsFromDialog(discover, announce, pause, followPort, fieldDiscoPort,
                fieldMaxClients, qrFocusClose, exclusive, fieldAuthRate, fieldAuthCool,
                blacklistEditor, webBlacklistEditor, restored[0]);
        pane.addPropertyChangeListener(JOptionPane.VALUE_PROPERTY, ev -> {
            if (settingsPrompt || settingsHandled) {
                return;
            }
            Object v = ev.getNewValue();
            if (!(v instanceof Integer choice) || choice == JOptionPane.UNINITIALIZED_VALUE) {
                return;
            }
            if (choice == JOptionPane.OK_OPTION) {
                settingsHandled = true;
                if (!settingsApply.getAsBoolean()) {
                    settingsHandled = false;
                    SwingUtilities.invokeLater(() -> {
                        pane.setValue(JOptionPane.UNINITIALIZED_VALUE);
                        if (settingsDialog != null) {
                            settingsDialog.setVisible(true);
                            settingsDialog.toFront();
                        }
                    });
                    return;
                }
                if (settingsAfterClose != null) {
                    settingsAfterClose.run();
                }
                return;
            }
            if (choice == JOptionPane.CANCEL_OPTION) {
                settingsHandled = true;
                if (settingsAfterClose != null) {
                    settingsAfterClose.run();
                }
                return;
            }
            if (choice == JOptionPane.CLOSED_OPTION) {
                SwingUtilities.invokeLater(TextSendMain::closeSettingsFromOutside);
            }
        });
        settingsDialog.setVisible(true);
        return;
    }

    private static String settingsSignature(JCheckBox discover, JCheckBox announce, JCheckBox pause,
                                            JCheckBox followPort, JTextField fieldDiscoPort,
                                            JTextField fieldMaxClients, JCheckBox qrFocusClose,
                                            JCheckBox exclusive, JTextField fieldAuthRate,
                                            JTextField fieldAuthCool, AuthBlacklistEditor appList,
                                            AuthBlacklistEditor webList) {
        return discover.isSelected() + "\n" + announce.isSelected() + "\n" + pause.isSelected() + "\n"
                + followPort.isSelected() + "\n" + fieldDiscoPort.getText() + "\n"
                + fieldMaxClients.getText() + "\n" + qrFocusClose.isSelected() + "\n"
                + exclusive.isSelected() + "\n" + fieldAuthRate.getText() + "\n"
                + fieldAuthCool.getText() + "\n" + appList.text() + "\n" + appList.pending() + "\n"
                + webList.text() + "\n" + webList.pending();
    }

    /** 把设置窗口里的值写入配置。校验失败时返回 false，窗口留着。 */
    private static boolean saveSettingsFromDialog(JCheckBox discover, JCheckBox announce, JCheckBox pause,
                                                  JCheckBox followPort, JTextField fieldDiscoPort,
                                                  JTextField fieldMaxClients, JCheckBox qrFocusClose,
                                                  JCheckBox exclusive, JTextField fieldAuthRate,
                                                  JTextField fieldAuthCool, AuthBlacklistEditor blacklistEditor,
                                                  AuthBlacklistEditor webBlacklistEditor, boolean restored) {
        boolean follow = followPort.isSelected();
        UserConfig.setLanDiscoverPortFollowListen(follow);
        if (!follow) {
            int port;
            try {
                port = Integer.parseInt(fieldDiscoPort.getText().trim());
            } catch (Exception ex) {
                flashStatus("探测端口须为 1–65535");
                return false;
            }
            if (!UserConfig.setLanDiscoverPort(port) && discover.isSelected()) {
                flashStatus("探测端口须为 1–65535");
                return false;
            }
        }
        int maxN;
        try {
            maxN = Integer.parseInt(fieldMaxClients.getText().trim());
        } catch (Exception ex) {
            flashStatus("最大客户端数须为 1–64");
            return false;
        }
        if (!UserConfig.setMaxClients(maxN)) {
            flashStatus("最大客户端数须为 1–64");
            return false;
        }
        int rateN;
        try {
            rateN = Integer.parseInt(fieldAuthRate.getText().trim());
        } catch (Exception ex) {
            flashStatus("授权限流须为 1–60");
            return false;
        }
        if (!UserConfig.setAuthRatePerMin(rateN)) {
            flashStatus("授权限流须为 1–60");
            return false;
        }
        int coolN;
        try {
            coolN = Integer.parseInt(fieldAuthCool.getText().trim());
        } catch (Exception ex) {
            flashStatus("冷却秒数须为 0–86400");
            return false;
        }
        if (!UserConfig.setAuthCooldownSec(coolN)) {
            flashStatus("冷却秒数须为 0–86400");
            return false;
        }
        blacklistEditor.commitField();
        UserConfig.setAuthBlacklistText(blacklistEditor.text());
        webBlacklistEditor.commitField();
        UserConfig.setWebBlacklistText(webBlacklistEditor.text());
        WebBridge.dropBlacklisted();
        WebBridgeA.dropBlacklisted();
        UserConfig.setPopupsCloseOnFocusLost(qrFocusClose.isSelected());
        boolean turnExclusive = exclusive.isSelected();
        UserConfig.setWebAppExclusive(turnExclusive);
        if (turnExclusive && isServerRunning() && webBridgeAnyRunning()) {
            stopOtherWebBridge((char) 0);
            flashStatus("已互斥：关掉了兼容模式");
        }
        UserConfig.setLanDiscoverAnnounce(announce.isSelected());
        UserConfig.setLanDiscoverPause(pause.isSelected());
        UserConfig.setLanDiscover(discover.isSelected());
        dropBlacklistedPending();
        refreshLanPeers();
        refreshAuthPending();
        applyActionTooltips();
        flashStatus(restored ? "已恢复默认设置" : "设置已保存");
        return true;
    }

    /** 恢复默认已经写盘。缩放或小窗要等设置框关掉再重建窗口。 */
    private static void finishRestoreUi(boolean wasMini, float oldScale) {
        if (wasMini) {
            setMiniMode(false);
            return;
        }
        if (Math.abs(oldScale - 1f) > 0.0005f) {
            Point keepLoc = frame != null ? frame.getLocation() : null;
            buildUi(isServerMode);
            if (keepLoc != null && frame != null) {
                frame.setLocation(keepLoc);
            }
            return;
        }
        serverListenPort = String.valueOf(UserConfig.getListenPort());
        if (fieldPort != null) {
            fieldPort.setText(serverListenPort);
        }
        if (checkAutoStart != null) {
            checkAutoStart.setSelected(UserConfig.isAutoStartServer());
        }
        if (fieldCustomPin != null) {
            fieldCustomPin.setText(UserConfig.getCustomPin());
        }
        if (checkCustomPin != null) {
            checkCustomPin.setSelected(UserConfig.isCustomPinEnabled());
        }
        if (checkRememberClient != null) {
            checkRememberClient.setSelected(UserConfig.isRememberLastClient());
        }
        if (checkFollowLinks != null) {
            checkFollowLinks.setSelected(UserConfig.isFollowSymlinks());
        }
        if (toggleMini != null) {
            toggleMini.setSelected(miniMode);
        }
        refreshLanPeers();
        refreshAuthPending();
        applyActionTooltips();
    }

    /** 探测线程收到授权请求时调用（任意线程）。新请求直接弹出「在线」面板。 */
    public static void onLanAuthRequest(LanAuth.Request req) {
        if (req == null) {
            return;
        }
        if (!UserConfig.isLanDiscover() || !isServerRunning()) {
            denyAuthSilent(req);
            SwingUtilities.invokeLater(() -> flashStatus(!isServerRunning()
                    ? "授权请求已到，但服务端没开"
                    : "授权请求已到，但局域网探测是关的"));
            return;
        }
        String host = authHostKey(req.host);
        PendingAuth already = authPendingByHost.get(host);
        if (already != null && already.req != null && req.nonce != null
                && java.util.Arrays.equals(already.req.nonce, req.nonce)) {
            if (req.tcpReply != null) {
                already.req.tcpReply = req.tcpReply;
            }
            return;
        }
        if (host.isEmpty() || UserConfig.isAuthBlacklisted(host)) {
            denyAuthSilent(req);
            SwingUtilities.invokeLater(() -> flashStatus("已忽略黑名单授权请求 " + host));
            return;
        }
        long now = System.currentTimeMillis();
        int left = authCooldownLeftSec(host, now);
        if (left > 0) {
            denyAuthSilent(req, left);
            SwingUtilities.invokeLater(() -> flashStatus("授权冷却中（约 " + left + " 秒），未弹出"));
            return;
        }
        if (!noteAuthHit(host, now)) {
            int again = authCooldownLeftSec(host, System.currentTimeMillis());
            denyAuthSilent(req, again);
            SwingUtilities.invokeLater(() -> flashStatus("授权请求过频，已冷却，未弹出"));
            return;
        }
        if (req.clientPub == null || req.clientPub.length != LanAuth.PUB_LEN) {
            denyAuthSilent(req, 0);
            SwingUtilities.invokeLater(() -> flashStatus("对方版本过旧，没有交换公钥"));
            return;
        }
        java.security.KeyPair serverKp;
        byte[] serverPub;
        String sas;
        try {
            serverKp = protocol.EcdhP256.generate();
            serverPub = protocol.EcdhP256.encodePublic(
                    (java.security.interfaces.ECPublicKey) serverKp.getPublic());
            sas = LanAuth.sasCode(req.clientPub, serverPub, req.nonce);
        } catch (Exception e) {
            denyAuthSilent(req, 0);
            SwingUtilities.invokeLater(() -> flashStatus("授权密钥协商失败"));
            return;
        }
        byte[] pendingPkt = LanAuth.encodePending(req.nonce, serverPub);
        boolean firstSlot = !authPendingByHost.containsKey(host) && authPendingByHost.isEmpty();
        PendingAuth prev = authPendingByHost.put(host, new PendingAuth(req, now, serverKp, serverPub, sas));
        if (prev != null && prev.req != null && !java.util.Arrays.equals(prev.req.nonce, req.nonce)) {
            denyAuthSilent(prev.req, 0);
        }
        trimAuthPendingOverflow();
        if (req.tcpReply != null) {
            req.earlyPending = pendingPkt;
        } else {
            LanDiscover.sendAuthPacket(req.host, req.replyPort, pendingPkt);
        }
        String sasShown = sas;
        SwingUtilities.invokeLater(() -> {
            refreshAuthPending();
            if (firstSlot || prev == null) {
                flashStatus(sasShown.isEmpty() ? "有授权请求" : "有授权请求，核对 " + sasShown);
                showSessionPopup();
            }
        });
    }

    /** 客户端取消申请。丢掉待授权，不拒绝、不计冷却。 */
    public static void onLanAuthCancel(LanAuth.Request req) {
        if (req == null) {
            return;
        }
        onLanAuthCancel(req.host, req.nonce);
    }

    public static void onLanAuthCancel(String host, byte[] nonce) {
        String key = authHostKey(host);
        PendingAuth pending = authPendingByHost.get(key);
        if (pending == null || pending.req == null || nonce == null
                || !java.util.Arrays.equals(pending.req.nonce, nonce)) {
            return;
        }
        if (authPendingByHost.remove(key, pending) && pending.req.tcpReply != null
                && !pending.req.tcpReply.isDone()) {
            pending.req.tcpReply.completeExceptionally(new LanAuth.AuthCancelledException());
        }
        SwingUtilities.invokeLater(TextSendMain::refreshAuthPending);
    }

    /** @return false 表示超限，应静默拒绝 */
    private static boolean noteAuthHit(String host, long now) {
        int limit = UserConfig.getAuthRatePerMin();
        long windowMs = 60_000L;
        java.util.concurrent.ConcurrentLinkedDeque<Long> hits =
                authHitsByHost.computeIfAbsent(host, h -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        while (true) {
            Long oldest = hits.peekFirst();
            if (oldest == null || now - oldest < windowMs) {
                break;
            }
            hits.pollFirst();
        }
        if (hits.size() >= limit) {
            applyAuthCooldown(host);
            return false;
        }
        hits.addLast(now);
        return true;
    }

    private static void trimAuthPendingOverflow() {
        while (authPendingByHost.size() > AUTH_PENDING_MAX) {
            String oldestHost = null;
            long oldestAt = Long.MAX_VALUE;
            for (var e : authPendingByHost.entrySet()) {
                if (e.getValue().at < oldestAt) {
                    oldestAt = e.getValue().at;
                    oldestHost = e.getKey();
                }
            }
            if (oldestHost == null) {
                break;
            }
            PendingAuth drop = authPendingByHost.remove(oldestHost);
            if (drop != null) {
                denyAuthSilent(drop.req);
            }
        }
    }

    private static void replyAuth(LanAuth.Request req, byte[] packet) {
        if (req == null || packet == null) {
            return;
        }
        if (req.tcpReply != null) {
            req.tcpReply.complete(packet);
            return;
        }
        LanDiscover.sendAuthPacket(req.host, req.replyPort, packet);
    }

    private static void denyAuthSilent(LanAuth.Request req) {
        denyAuthSilent(req, LanAuth.DENY_HARD);
    }

    private static void denyAuthSilent(LanAuth.Request req, int waitSec) {
        if (req == null) {
            return;
        }
        replyAuth(req, LanAuth.encodeDeny(req.nonce, waitSec));
    }

    /** 待授权、冷却、限流共用的地址键。 */
    private static String authHostKey(String host) {
        return TsServer.normalizeHost(host).toLowerCase(java.util.Locale.ROOT);
    }

    private static void applyAuthCooldown(String host) {
        String key = authHostKey(host);
        int cool = UserConfig.getAuthCooldownSec();
        if (cool <= 0 || key.isEmpty()) {
            return;
        }
        authCooldownUntil.put(key, System.currentTimeMillis());
        // 冷却代替滑动窗口。结束后可以再请求，不会和剩余次数叠成一直拒绝。
        authHitsByHost.remove(key);
    }

    /** 剩余秒。0 表示不在冷却。用拒绝时刻加当前设置，把 120 改成 10 会立刻缩短。 */
    private static int authCooldownLeftSec(String host, long now) {
        String key = authHostKey(host);
        Long started = authCooldownUntil.get(key);
        if (started == null) {
            return 0;
        }
        int cool = UserConfig.getAuthCooldownSec();
        if (cool <= 0) {
            authCooldownUntil.remove(key);
            return 0;
        }
        long until = started + cool * 1000L;
        if (until <= now) {
            authCooldownUntil.remove(key);
            return 0;
        }
        long left = (until - now + 999) / 1000;
        return (int) Math.min(LanAuth.DENY_HARD - 1, Math.max(1L, left));
    }

    private static void clearAuthPending(boolean denyAll) {
        if (denyAll) {
            for (PendingAuth p : authPendingByHost.values()) {
                denyAuthSilent(p.req);
            }
        }
        authPendingByHost.clear();
    }

    private static void dropBlacklistedPending() {
        for (String host : new java.util.ArrayList<>(authPendingByHost.keySet())) {
            if (UserConfig.isAuthBlacklisted(host)) {
                PendingAuth p = authPendingByHost.remove(host);
                if (p != null) {
                    denyAuthSilent(p.req);
                }
            }
        }
    }

    private static void ensureLanPeerTimer() {
        if (lanPeerTimer != null) {
            return;
        }
        lanPeerTimer = new Timer(1000, e -> {
            refreshLanPeers();
            refreshAuthPending();
            refreshConnectedClients();
        });
        lanPeerTimer.setRepeats(true);
        lanPeerTimer.start();
    }

    private static void refreshAuthPending() {
        JPanel box = authPendingBox;
        if (box == null) {
            return;
        }
        if (!isServerRunning() || !UserConfig.isLanDiscover()) {
            if (!authPendingByHost.isEmpty()) {
                clearAuthPending(true);
            }
            hideAuthPendingPopup();
            box.removeAll();
            authPendingEntryBtn = null;
            authPendingSig = "";
            box.setVisible(false);
            box.revalidate();
            box.repaint();
            updateClientsButton();
            return;
        }
        // 过期清理（客户端等 60s）
        long now = System.currentTimeMillis();
        for (String host : new java.util.ArrayList<>(authPendingByHost.keySet())) {
            PendingAuth p = authPendingByHost.get(host);
            if (p != null && now - p.at > LanAuth.REQUEST_WAIT_MS) {
                authPendingByHost.remove(host, p);
                denyAuthSilent(p.req);
            }
        }
        hideInlineSession(box);
        updateClientsButton();
    }

    private static void hideAuthPendingPopup() {
        if (authPendingPopup != null) {
            authPendingPopup.setVisible(false);
            authPendingPopup = null;
        }
    }

    private static void decideAuth(String host, boolean allow, boolean blacklist) {
        host = authHostKey(host);
        if (host.isEmpty()) {
            return;
        }
        PendingAuth pending = authPendingByHost.remove(host);
        if (blacklist) {
            UserConfig.addAuthBlacklist(host);
        }
        if (pending == null) {
            if (blacklist) {
                flashStatus("已拉黑 " + host);
            }
            refreshAuthPending();
            return;
        }
        LanAuth.Request req = pending.req;
        String name = req.name == null || req.name.isBlank() ? "未知设备" : req.name;
        if (allow && isServerRunning() && UserConfig.isLanDiscover() && !UserConfig.isAuthBlacklisted(host)) {
            if (req.clientPub == null || req.clientPub.length != LanAuth.PUB_LEN
                    || pending.serverKp == null || pending.serverPub == null) {
                denyAuthSilent(req, 0);
                flashStatus("对方版本过旧，没有交换公钥");
            } else {
                try {
                    byte[] psk = LanAuth.deriveGrantPsk(pending.serverKp, req.clientPub, req.nonce);
                    authCooldownUntil.remove(host);
                    long exp = System.currentTimeMillis() + LanAuth.GRANT_TTL_MS;
                    TsServer.putLanGrantCurrent(req.host, psk, LanAuth.GRANT_TTL_MS, name);
                    replyAuth(req, LanAuth.encodeGrant(req.nonce, pending.serverPub, getServerListenPort(), exp));
                    flashStatus(pending.sas.isEmpty() ? "已允许 " + name : "已允许 " + name + "（" + pending.sas + "）");
                } catch (Exception ex) {
                    denyAuthSilent(req, 0);
                    flashStatus("授权密钥协商失败");
                }
            }
        } else {
            TsServer.revokeLanGrantCurrent(req.host);
            if (blacklist) {
                denyAuthSilent(req, LanAuth.DENY_HARD);
                flashStatus("已拉黑 " + name);
            } else {
                applyAuthCooldown(host);
                int cool = Math.max(0, UserConfig.getAuthCooldownSec());
                denyAuthSilent(req, Math.min(LanAuth.DENY_HARD - 1, cool));
                flashStatus(cool > 0 ? "已拒绝 " + name + "，冷却 " + cool + " 秒" : "已拒绝 " + name);
            }
        }
        refreshAuthPending();
    }

    private static void refreshConnectedClients() {
        JPanel box = connectedBox;
        if (box == null) {
            updateClientsButton();
            return;
        }
        hideInlineSession(box);
        updateClientsButton();
    }

    /** 待授权 / 已连接平时不占主界面，只在点「在线」时弹出。 */
    private static void hideInlineSession(JPanel box) {
        if (box == null) {
            return;
        }
        if (box.getComponentCount() > 0) {
            box.removeAll();
        }
        box.setVisible(false);
    }

    private static void updateClientsButton() {
        if (buttonClients == null) {
            return;
        }
        if (!isServerMode || miniMode) {
            buttonClients.setVisible(false);
            return;
        }
        buttonClients.setVisible(true);
        int pending = isServerRunning() ? authPendingByHost.size() : 0;
        int alive = isServerRunning() ? TsServer.listAliveClientsCurrent().size() : 0;
        int online = alive + webListenerTotal();
        if (online == 0 && pending == 0 && !isServerRunning() && !webBridgeAnyRunning()) {
            buttonClients.setText("在线 —");
        } else if (pending > 0) {
            buttonClients.setText("待授权 " + pending + " · 在线 " + online);
        } else {
            buttonClients.setText("在线 " + online);
        }
    }

    private static int webListenerTotal() {
        return WebBridge.listenerHosts().size() + WebBridgeA.listenerHosts().size();
    }

    /** 工具栏「在线」：待授权、App 客户端和网页会话在同一张列表里。服务端没开也能看网页。 */
    private static void showSessionPopup() {
        if (buttonClients == null || frame == null || !buttonClients.isShowing()) {
            return;
        }
        java.util.List<PendingAuth> pending = isServerRunning()
                ? new java.util.ArrayList<>(authPendingByHost.values())
                : new java.util.ArrayList<>();
        pending.sort((a, b) -> Long.compare(a.at, b.at));
        List<TsServer.AliveClient> clients = isServerRunning()
                ? TsServer.listAliveClientsCurrent() : List.of();
        List<String> webB = WebBridge.listenerHosts();
        List<String> webA = WebBridgeA.listenerHosts();

        hideSessionPopup();
        sessionCursor = 0;
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(UserConfig.s(8), UserConfig.s(10), UserConfig.s(8), UserConfig.s(10)));

        JLabel h1 = new JLabel("待授权  " + pending.size());
        h1.setFont(h1.getFont().deriveFont(Font.BOLD, (float) UserConfig.s(13)));
        h1.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(h1);
        if (pending.isEmpty()) {
            JLabel empty = new JLabel("暂无授权请求");
            empty.setForeground(MUTED);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(empty);
        }
        for (PendingAuth item : pending) {
            LanAuth.Request req = item.req;
            String name = req.name == null || req.name.isBlank() ? "未知设备" : req.name;
            String label = name + "  ·  " + req.host
                    + (item.sas.isEmpty() ? "" : "  ·  核对 " + item.sas);
            JPanel row = sessionRow(label,
                    new String[]{"允许  Alt+Y", "拒绝  Alt+N", "拉黑  Alt+B"},
                    new String[]{
                            "核对码与对方一致后再允许（Alt+Y）。单独按 Y 不会同意",
                            "拒绝并开始冷却，不拉黑（Alt+N）",
                            "拉黑这个地址（Alt+B）"
                    },
                    new Runnable[]{
                            () -> decideAuth(req.host, true, false),
                            () -> decideAuth(req.host, false, false),
                            () -> decideAuth(req.host, false, true)
                    });
            sessionTargets.add(new SessionTarget(true, req.host, row));
            panel.add(row);
        }

        panel.add(Box.createVerticalStrut(UserConfig.s(8)));
        int online = clients.size() + webA.size() + webB.size();
        String cap = isServerRunning() && webA.isEmpty() && webB.isEmpty() && maxConnection > 1
                ? " / " + maxConnection : "";
        JLabel h2 = new JLabel("已连接  " + online + cap);
        h2.setFont(h2.getFont().deriveFont(Font.BOLD, (float) UserConfig.s(13)));
        h2.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(h2);
        if (online == 0) {
            JLabel empty = new JLabel("还没有客户端");
            empty.setForeground(MUTED);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(empty);
        }
        for (TsServer.AliveClient c : clients) {
            String label = c.displayLabel();
            if (c.name.isBlank() && TsServer.isSelfHost(c.host)) {
                label = LanDiscover.deviceName() + "  ·  " + c.host;
            }
            String host = c.host;
            JPanel row = sessionRow(label,
                    new String[]{"踢出  Alt+K", "拉黑  Alt+B"},
                    new String[]{
                            "断开这台，不拉黑（Alt+K）",
                            "拉黑并踢下线（Alt+B）"
                    },
                    new Runnable[]{
                            () -> {
                                TsServer.kickHostCurrent(host);
                                flashStatus("已踢下线 " + host);
                                refreshConnectedClients();
                            },
                            () -> {
                                UserConfig.addAuthBlacklist(host);
                                TsServer.kickHostCurrent(host);
                                flashStatus("已拉黑并踢下线 " + host);
                                refreshConnectedClients();
                            }
                    });
            sessionTargets.add(new SessionTarget(false, host, row));
            panel.add(row);
        }
        addWebSessionRows(panel, webB, WebBridge.isTls() ? 'C' : 'B');
        addWebSessionRows(panel, webA, 'A');

        panel.add(Box.createVerticalStrut(UserConfig.s(8)));
        JLabel keys = new JLabel("Alt+Y 允许 · Alt+N 拒绝 · Alt+B 拉黑 · Alt+K 踢下线 · ↑↓ 换一台");
        keys.setForeground(MUTED);
        keys.setFont(keys.getFont().deriveFont((float) UserConfig.s(11)));
        keys.setAlignmentX(Component.LEFT_ALIGNMENT);
        keys.setToolTipText("必须按住 Alt。待授权先对核对码再允许。单独按字母会照常进输入框。");
        panel.add(keys);
        paintSessionCursor();

        JPopupMenu pop = new JPopupMenu();
        sessionPopup = pop;
        pop.setBorder(BorderFactory.createLineBorder(BORDER));
        pop.add(panel);
        installSessionKeys();
        pop.show(buttonClients, 0, buttonClients.getHeight());
    }

    private static void installSessionKeys() {
        removeSessionKeys();
        sessionKeys = e -> {
            if (e.getID() == KeyEvent.KEY_TYPED && sessionSwallowTyped != 0
                    && Character.toLowerCase(e.getKeyChar()) == sessionSwallowTyped) {
                sessionSwallowTyped = 0;
                if (sessionPopup == null || !sessionPopup.isVisible()) {
                    removeSessionKeys();
                }
                return true;
            }
            if (sessionPopup == null || !sessionPopup.isVisible()) {
                return false;
            }
            Window popWin = SwingUtilities.getWindowAncestor(sessionPopup);
            boolean here = (popWin != null && popWin.isFocused())
                    || (frame != null && frame.isFocused());
            if (!here) {
                return false;
            }
            if (e.isControlDown() || e.isMetaDown()) {
                return false;
            }
            if (e.getID() == KeyEvent.KEY_TYPED) {
                return false;
            }
            if (e.getID() != KeyEvent.KEY_PRESSED) {
                return false;
            }
            int vk = e.getKeyCode();
            if (vk == KeyEvent.VK_UP || vk == KeyEvent.VK_DOWN) {
                moveSessionCursor(vk == KeyEvent.VK_UP ? -1 : 1);
                return true;
            }
            if (vk == KeyEvent.VK_ESCAPE) {
                hideSessionPopup();
                return true;
            }
            // 裸字母留给输入框。弹出列表时正在打字，按 Y 不能当成同意。
            if (!e.isAltDown()) {
                return false;
            }
            if (vk == KeyEvent.VK_Y) {
                sessionSwallowTyped = 'y';
                runSessionKey("allow");
                return true;
            }
            if (vk == KeyEvent.VK_N) {
                sessionSwallowTyped = 'n';
                runSessionKey("deny");
                return true;
            }
            if (vk == KeyEvent.VK_B) {
                sessionSwallowTyped = 'b';
                runSessionKey("block");
                return true;
            }
            if (vk == KeyEvent.VK_K) {
                sessionSwallowTyped = 'k';
                runSessionKey("kick");
                return true;
            }
            return false;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(sessionKeys);
    }

    private static void removeSessionKeys() {
        if (sessionKeys != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(sessionKeys);
            sessionKeys = null;
        }
    }

    private static void moveSessionCursor(int delta) {
        if (sessionTargets.isEmpty()) {
            return;
        }
        int n = sessionTargets.size();
        sessionCursor = (sessionCursor + delta + n) % n;
        paintSessionCursor();
    }

    private static void paintSessionCursor() {
        for (int i = 0; i < sessionTargets.size(); i++) {
            JPanel row = sessionTargets.get(i).row;
            boolean on = i == sessionCursor;
            row.setOpaque(true);
            row.setBackground(on ? new Color(0xE7EEFF) : CARD);
            row.setBorder(BorderFactory.createLineBorder(on ? ACCENT : BORDER));
        }
        if (sessionPopup != null) {
            sessionPopup.repaint();
        }
    }

    private static void runSessionKey(String action) {
        if (sessionTargets.isEmpty()) {
            flashStatus("没有可操作的设备");
            return;
        }
        int i = Math.max(0, Math.min(sessionCursor, sessionTargets.size() - 1));
        SessionTarget t = sessionTargets.get(i);
        String host = t.host;
        boolean pending = t.pending;
        switch (action) {
            case "allow", "deny" -> {
                if (!pending) {
                    flashStatus("先用上下键选中待授权的设备");
                    return;
                }
                hideSessionPopup();
                decideAuth(host, "allow".equals(action), false);
            }
            case "block" -> {
                hideSessionPopup();
                if (t.webWay != 0) {
                    blockWeb(host);
                } else if (pending) {
                    decideAuth(host, false, true);
                } else {
                    UserConfig.addAuthBlacklist(host);
                    TsServer.kickHostCurrent(host);
                    flashStatus("已拉黑并踢下线 " + host);
                    refreshConnectedClients();
                }
            }
            case "kick" -> {
                if (pending) {
                    flashStatus("待授权用 N 拒绝，K 是踢下线");
                    return;
                }
                hideSessionPopup();
                if (t.webWay == 'A') {
                    WebBridgeA.dropListener(t.webIndex, host);
                    flashStatus("已断开网页 " + host);
                } else if (t.webWay == 'B') {
                    WebBridge.dropListener(t.webIndex, host);
                    flashStatus("已断开网页 " + host);
                } else {
                    TsServer.kickHostCurrent(host);
                    flashStatus("已踢下线 " + host);
                }
                refreshConnectedClients();
            }
            default -> {
            }
        }
        updateClientsButton();
    }

    private static void addWebSessionRows(JPanel panel, List<String> hosts, char way) {
        for (int i = 0; i < hosts.size(); i++) {
            String host = hosts.get(i);
            int index = i;
            JPanel row = sessionRow("网页 " + way + "  ·  " + host,
                    new String[]{"断开  Alt+K", "拉黑  Alt+B"},
                    new String[]{
                            "关掉这个网页的长连接（Alt+K）",
                            "拉黑这个地址，并断开它打开的网页（Alt+B）"
                    },
                    new Runnable[]{
                            () -> {
                                if (way == 'A') {
                                    WebBridgeA.dropListener(index, host);
                                } else {
                                    WebBridge.dropListener(index, host);
                                }
                                flashStatus("已断开网页 " + host);
                                hideSessionPopup();
                                refreshConnectedClients();
                            },
                            () -> blockWeb(host)
                    });
            sessionTargets.add(new SessionTarget(false, host, row, way, index));
            panel.add(row);
        }
    }

    /** 拉黑网页地址：只写入兼容模式黑名单，并断开这个地址上的网页。 */
    private static void blockWeb(String host) {
        if (host == null || host.isBlank() || "未知".equals(host)) {
            flashStatus("这个网页没有地址，没法拉黑");
            return;
        }
        UserConfig.addWebBlacklist(host);
        WebBridge.dropBlacklisted();
        WebBridgeA.dropBlacklisted();
        flashStatus("已拉黑并断开网页 " + host);
        hideSessionPopup();
        refreshConnectedClients();
    }

    private static JPanel sessionRow(String label, String[] actions, String[] tips, Runnable[] runs) {
        JPanel row = new JPanel(new BorderLayout(UserConfig.s(8), 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(UserConfig.s(520), UserConfig.s(36)));
        JLabel lab = new JLabel(label);
        lab.setFont(lab.getFont().deriveFont((float) UserConfig.s(12)));
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, UserConfig.s(4), 0));
        btns.setOpaque(false);
        for (int i = 0; i < actions.length; i++) {
            JButton b = new JButton(actions[i]);
            b.setFont(b.getFont().deriveFont((float) UserConfig.s(11)));
            b.setToolTipText(tips[i]);
            b.setFocusable(false);
            int idx = i;
            b.addActionListener(e -> {
                hideSessionPopup();
                runs[idx].run();
                updateClientsButton();
            });
            btns.add(b);
        }
        row.add(lab, BorderLayout.CENTER);
        row.add(btns, BorderLayout.EAST);
        return row;
    }

    private static void hideSessionPopup() {
        sessionTargets.clear();
        if (sessionPopup != null) {
            sessionPopup.setVisible(false);
            sessionPopup = null;
        }
        if (sessionSwallowTyped == 0) {
            removeSessionKeys();
        } else {
            SwingUtilities.invokeLater(() -> {
                sessionSwallowTyped = 0;
                if (sessionPopup == null) {
                    removeSessionKeys();
                }
            });
        }
    }

    private static void hideConnectedPopup() {
        if (connectedPopup != null) {
            connectedPopup.setVisible(false);
            connectedPopup = null;
        }
    }

    private static void refreshLanPeers() {
        JPanel box = lanPeerBox;
        if (box == null) {
            return;
        }
        boolean on = UserConfig.isLanDiscover();
        boolean paused = on && LanDiscover.pausedNow();
        String bindError = on && !paused ? LanDiscover.lastBindError() : "";
        boolean probing = on && !paused && bindError.isBlank();
        List<LanDiscover.Peer> peers = probing ? LanDiscover.peers() : List.of();
        StringBuilder sig = new StringBuilder();
        sig.append(on).append('|').append(paused).append('|').append(bindError).append('|').append(probing);
        for (LanDiscover.Peer peer : peers) {
            String sas = authSasByHost.getOrDefault(authHostKey(peer.host), "");
            sig.append('|').append(peer.name).append('\u0000').append(peer.host).append(':')
                    .append(peer.tcpPort).append('\u0000').append(sas);
        }
        String next = sig.toString();
        if (next.equals(lanPeerSig) && box.isVisible() == on) {
            syncLanProbeBreath(probing);
            return;
        }
        if (!bindError.isBlank() && !lanPeerSig.contains(bindError)) {
            flashStatus(bindError);
        }
        lanPeerSig = next;
        box.removeAll();
        box.setVisible(on);
        if (on) {
            String titleText;
            if (paused) {
                titleText = "已连接，探测已暂停";
            } else if (!bindError.isBlank()) {
                titleText = bindError;
            } else if (peers.isEmpty()) {
                titleText = "正在探测…还没看到其他 TextSend";
            } else {
                titleText = "局域网设备（点一下请求授权）";
            }
            JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, UserConfig.s(6), 0));
            titleRow.setOpaque(false);
            titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            lanProbeLight = new JLabel("●");
            lanProbeLight.setFont(lanProbeLight.getFont().deriveFont((float) UserConfig.s(12)));
            lanProbeLight.setVisible(probing);
            titleRow.add(lanProbeLight);
            JLabel title = new JLabel(titleText);
            title.setForeground(MUTED);
            title.setFont(title.getFont().deriveFont((float) UserConfig.s(12)));
            titleRow.add(title);
            box.add(titleRow);
            java.util.Map<String, Integer> idCounts = new java.util.HashMap<>();
            for (LanDiscover.Peer peer : peers) {
                if (peer.idKey != null && !peer.idKey.isBlank()) {
                    idCounts.merge(peer.idKey, 1, Integer::sum);
                }
            }
            for (LanDiscover.Peer peer : peers) {
                String label = (peer.name == null || peer.name.isBlank())
                        ? peer.host + ":" + peer.tcpPort
                        : peer.name + "  " + peer.host + ":" + peer.tcpPort;
                if (peer.idKey != null && !peer.idKey.isBlank()
                        && idCounts.getOrDefault(peer.idKey, 0) > 1) {
                    label += "  （可能是同一设备）";
                }
                String sas = authSasByHost.getOrDefault(authHostKey(peer.host), "");
                JButton btn;
                if (!sas.isEmpty()) {
                    btn = new JButton("<html>" + htmlEsc(label)
                            + "<br><span style='font-size:" + Math.max(10, UserConfig.s(11))
                            + "pt;color:#555'>核对 " + htmlEsc(sas)
                            + " · 再点取消</span></html>");
                } else {
                    btn = new JButton(label);
                }
                btn.setAlignmentX(Component.LEFT_ALIGNMENT);
                btn.setFont(btn.getFont().deriveFont((float) UserConfig.s(12)));
                btn.setHorizontalAlignment(SwingConstants.LEFT);
                btn.setToolTipText(!sas.isEmpty()
                        ? "核对码 " + sas + "。再点这一台取消申请"
                        : (idCounts.getOrDefault(peer.idKey, 0) > 1
                        ? "实例号相同，多半是同一进程的多网卡。点一下请求授权"
                        : "点一下：向对方请求授权，对方允许后免 PIN 连接"));
                btn.addActionListener(e -> applyDiscoveredPeer(peer.host, peer.tcpPort));
                box.add(btn);
            }
        } else {
            lanProbeLight = null;
        }
        syncLanProbeBreath(probing);
        box.revalidate();
        box.repaint();
    }

    /** 探测进行中：标题前一颗灯慢慢亮灭（对齐 Android）。 */
    private static void syncLanProbeBreath(boolean active) {
        if (!active) {
            if (lanProbeBreathTimer != null) {
                lanProbeBreathTimer.stop();
                lanProbeBreathTimer = null;
            }
            if (lanProbeLight != null) {
                lanProbeLight.setVisible(false);
            }
            lanProbeLight = null;
            return;
        }
        if (lanProbeLight != null) {
            lanProbeLight.setVisible(true);
        }
        if (lanProbeBreathTimer != null && lanProbeBreathTimer.isRunning()) {
            return;
        }
        lanProbeBreathPhase = 0f;
        lanProbeBreathTimer = new Timer(40, e -> {
            JLabel light = lanProbeLight;
            if (light == null || !light.isDisplayable()) {
                return;
            }
            lanProbeBreathPhase += 0.075f;
            float wave = 0.5f + 0.5f * (float) Math.sin(lanProbeBreathPhase);
            int alpha = Math.round(255 * (0.15f + 0.85f * wave));
            light.setForeground(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), alpha));
        });
        lanProbeBreathTimer.setRepeats(true);
        lanProbeBreathTimer.start();
    }

    private static void applyDiscoveredPeer(String host, int tcpPort) {
        if (isServerMode) {
            return;
        }
        if (isClientConnected || clientHandshaking) {
            flashStatus("已连接，未改连接串");
            return;
        }
        if (!UserConfig.isLanDiscover()) {
            flashStatus("请先在设置里打开局域网探测");
            return;
        }
        String hostKey = authHostKey(host);
        if (LanAuth.isWaiting(host) || hostKey.equals(authWaitHost)) {
            authCancelEarly = true;
            LanAuth.cancelActive();
            return;
        }
        authWaitHost = hostKey;
        authCancelEarly = false;
        flashStatus("正在请求授权 " + host + "…");
        int udpPort = UserConfig.getLanDiscoverPort();
        java.util.function.Consumer<String> onSas = code -> SwingUtilities.invokeLater(() -> {
            if (code != null && !code.isBlank()) {
                authSasByHost.put(hostKey, code.trim());
                lanPeerSig = "";
                refreshLanPeers();
            }
        });
        Thread t = new Thread(() -> {
            try {
                if (authCancelEarly) {
                    throw new LanAuth.AuthCancelledException();
                }
                LanAuth.Grant grant;
                try {
                    grant = LanDiscover.requestAuthTcp(host, tcpPort, onSas);
                } catch (Exception tcpErr) {
                    if (LanAuth.isDenied(tcpErr) || LanAuth.isCancelled(tcpErr)) {
                        throw tcpErr;
                    }
                    grant = LanDiscover.requestAuth(host, udpPort, onSas);
                }
                int port = grant.tcpPort > 0 ? grant.tcpPort : tcpPort;
                String uri = TsUri.format(host, port, grant.pskB64());
                SwingUtilities.invokeLater(() -> {
                    authSasByHost.remove(hostKey);
                    lanPeerSig = "";
                    refreshLanPeers();
                    JTextArea area = miniMode && miniTextArea != null ? miniTextArea : areaMessage;
                    if (area != null) {
                        area.setText(uri);
                    }
                    flashStatus("已获授权，正在连接 " + host);
                    startClient();
                });
            } catch (Exception e) {
                if (LanAuth.isCancelled(e)) {
                    SwingUtilities.invokeLater(() -> {
                        authSasByHost.remove(hostKey);
                        lanPeerSig = "";
                        refreshLanPeers();
                        flashStatus("已取消授权申请");
                    });
                    return;
                }
                Throwable shown = e;
                if (e instanceof java.util.concurrent.ExecutionException && e.getCause() != null) {
                    shown = e.getCause();
                }
                String msg = shown.getMessage() == null ? "授权失败" : shown.getMessage();
                if (shown instanceof java.util.concurrent.TimeoutException
                        || e instanceof java.util.concurrent.TimeoutException) {
                    msg = "等待服务端允许超时";
                }
                String tip = msg;
                SwingUtilities.invokeLater(() -> {
                    authSasByHost.remove(hostKey);
                    lanPeerSig = "";
                    refreshLanPeers();
                    flashStatus(tip);
                });
            } finally {
                if (hostKey.equals(authWaitHost)) {
                    authWaitHost = null;
                }
            }
        }, "ts-lan-auth");
        t.setDaemon(true);
        t.start();
    }

    private static String htmlEsc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 网页桥收到文件后在状态栏提一句。 */
    static void noteWeb(String s) {
        flashStatus(s);
    }

    /** 网页长连接增减。按钮上的在线人数把 App 和网页算在一起。 */
    static void onWebListenersChanged() {
        SwingUtilities.invokeLater(() -> {
            updateClientsButton();
            if (transferUi || labelStatus == null) {
                return;
            }
            if (statusFlashTimer != null && statusFlashTimer.isRunning()) {
                return;
            }
            labelStatus.setText(runningStatusText());
        });
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

    private static void showWebBridge() {
        if (webDialog != null && !webDialog.isDisplayable()) {
            webDialog = null;
        }
        if (webDialog == null) {
            webDialog = new JDialog(frame, "兼容模式", false);
            webDialog.setAlwaysOnTop(true);
            webDialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
            JPanel box = new JPanel(new BorderLayout(0, UserConfig.s(8)));
            box.setBackground(CARD);
            box.setBorder(new EmptyBorder(UserConfig.s(12), UserConfig.s(12), UserConfig.s(12), UserConfig.s(12)));
            webWayB = new JButton("B 加密");
            webWayA = new JButton("A 明文");
            webWayC = new JButton("C 证书");
            webWayA.setToolTipText("明文加令牌。和 B、C 不能同时开。");
            webWayB.setToolTipText("正文加密。和 A、C 不能同时开。");
            webWayC.setToolTipText("HTTPS。根证书可选，不装时浏览器会提示不受信任。和 A、B 不能同时开。");
            webWayB.addActionListener(e -> showWebWay('B'));
            webWayA.addActionListener(e -> showWebWay('A'));
            webWayC.addActionListener(e -> showWebWay('C'));
            Dimension waySz = new Dimension(UserConfig.s(96), UserConfig.s(32));
            for (JButton b : List.of(webWayB, webWayA, webWayC)) {
                b.setPreferredSize(waySz);
                b.setMinimumSize(waySz);
                b.setMaximumSize(waySz);
                b.setMargin(new Insets(4, 8, 4, 8));
                b.setFont(b.getFont().deriveFont((float) UserConfig.s(13)));
            }
            JPanel ways = new JPanel(new GridLayout(1, 3, UserConfig.s(6), 0));
            ways.setOpaque(false);
            ways.setAlignmentX(Component.LEFT_ALIGNMENT);
            ways.setMaximumSize(new Dimension(Integer.MAX_VALUE, UserConfig.s(36)));
            ways.add(webWayA);
            ways.add(webWayB);
            ways.add(webWayC);
            webHintLabel = new JLabel(" ");
            webHintLabel.setForeground(MUTED);
            webHintLabel.setFont(webHintLabel.getFont().deriveFont((float) UserConfig.s(12)));
            webHintLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            webStateLabel = new JLabel("未启动");
            webStateLabel.setForeground(TEXT);
            webStateLabel.setFont(webStateLabel.getFont().deriveFont((float) UserConfig.s(13)));
            webStateLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            webPortField = new JTextField(6);
            sizeDigitField(webPortField, 6);
            webPowerBtn = new JButton("启动");
            webPowerBtn.setToolTipText("<html>左键：启动或停止<br>右键：启动，并用默认浏览器打开链接。已经在跑则只打开。</html>");
            webRefreshBtn = new JButton("刷新会话");
            webCaBtn = new JButton("根证书");
            webCaBtn.setToolTipText("可选。打开根证书。装进手机并信任后，浏览器不再提示不安全。");
            webCaBtn.addActionListener(e -> openCaCert());
            // 固定同高；启动按钮留够「切换并启动」的宽度
            fixToolbarButton(webPowerBtn, 112);
            fixToolbarButton(webRefreshBtn, 96);
            fixToolbarButton(webCaBtn, 80);
            JPanel portRow = new JPanel(new FlowLayout(FlowLayout.LEFT, UserConfig.s(6), 0));
            portRow.setOpaque(false);
            portRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            portRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, UserConfig.s(72)));
            JLabel portLabel = new JLabel("端口");
            portLabel.setForeground(TEXT);
            portLabel.setFont(portLabel.getFont().deriveFont((float) UserConfig.s(13)));
            portRow.add(portLabel);
            portRow.add(webPortField);
            portRow.add(webPowerBtn);
            portRow.add(webRefreshBtn);
            portRow.add(webCaBtn);
            JPanel north = new JPanel();
            north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
            north.setOpaque(false);
            north.add(ways);
            north.add(Box.createVerticalStrut(UserConfig.s(8)));
            north.add(webHintLabel);
            north.add(Box.createVerticalStrut(UserConfig.s(6)));
            north.add(webStateLabel);
            north.add(Box.createVerticalStrut(UserConfig.s(4)));
            north.add(portRow);
            int qrSide = UserConfig.s(280);
            webQrPanel = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    int w = getWidth();
                    int h = getHeight();
                    if (webQrImage == null) {
                        g.setColor(MUTED);
                        FontMetrics fm = g.getFontMetrics();
                        String msg = webQrPlaceholder == null ? "" : webQrPlaceholder;
                        g.drawString(msg, Math.max(8, (w - fm.stringWidth(msg)) / 2), h / 2);
                        return;
                    }
                    int side = Math.min(w, h);
                    if (side < 4) {
                        return;
                    }
                    int x = (w - side) / 2;
                    int y = (h - side) / 2;
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g2.drawImage(webQrImage, x, y, side, side, null);
                    g2.dispose();
                }
            };
            webQrPanel.setBackground(Color.WHITE);
            webQrPanel.setPreferredSize(new Dimension(qrSide, qrSide));
            webQrPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            webQrPanel.setToolTipText("点击复制链接");
            webQrPanel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    copyWebLink();
                }
            });
            webUrlArea = new JTextArea();
            webUrlArea.setEditable(false);
            webUrlArea.setLineWrap(true);
            webUrlArea.setWrapStyleWord(true);
            webUrlArea.setRows(2);
            webUrlArea.setBackground(BG);
            webUrlArea.setForeground(new Color(0x15, 0x4F, 0xBF));
            webUrlArea.setFont(webUrlArea.getFont().deriveFont((float) UserConfig.s(12)));
            webUrlArea.setBorder(new EmptyBorder(UserConfig.s(6), UserConfig.s(6), UserConfig.s(6), UserConfig.s(6)));
            webUrlArea.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            webUrlArea.setToolTipText("用默认浏览器打开");
            webUrlArea.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    openWebLink();
                }
            });
            box.add(north, BorderLayout.NORTH);
            box.add(webQrPanel, BorderLayout.CENTER);
            box.add(webUrlArea, BorderLayout.SOUTH);
            webDialog.setContentPane(box);
            webPowerBtn.addActionListener(e -> toggleWebBridgeShown());
            webPowerBtn.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.getButton() != MouseEvent.BUTTON3) {
                        return;
                    }
                    if (!webPowerBtn.isEnabled() || !webPowerBtn.contains(e.getPoint())) {
                        return;
                    }
                    openWebBridgeInBrowser();
                }
            });
            webRefreshBtn.addActionListener(e -> refreshWebBridgeShown());
            webPortField.addActionListener(e -> applyWebPort(false));
            webDialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    if (webDialogTimer != null) {
                        webDialogTimer.stop();
                    }
                }
            });
            webDialog.getRootPane().registerKeyboardAction(e -> hideWebDialog(),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_IN_FOCUSED_WINDOW);
            webDialog.setMinimumSize(new Dimension(UserConfig.s(340), UserConfig.s(500)));
            webDialog.pack();
        }
        if (webDialogTimer != null) {
            webDialogTimer.stop();
        }
        webDialogTimer = new Timer(1000, e -> {
            if (webDialog != null && webDialog.isVisible()) {
                paintWebDialog();
            }
        });
        webDialogTimer.start();
        paintWebDialog();
        webDialog.setLocationRelativeTo(frame);
        webDialog.setVisible(true);
        webDialog.toFront();
    }

    private static void hideWebDialog() {
        if (webDialogTimer != null) {
            webDialogTimer.stop();
        }
        if (webDialog != null) {
            webDialog.setVisible(false);
        }
    }

    /** 右键启动：没开就先开，再用默认浏览器打开当前链接。已在跑则只打开，不停止。 */
    private static boolean webWayRunning(char way) {
        if (way == 'A') {
            return WebBridgeA.isRunning();
        }
        if (way == 'C') {
            return WebBridge.isRunning() && WebBridge.isTls();
        }
        if (way == 'B') {
            return WebBridge.isRunning() && !WebBridge.isTls();
        }
        return false;
    }

    private static void openWebBridgeInBrowser() {
        if (webWayShown != 'A' && webWayShown != 'B' && webWayShown != 'C') {
            return;
        }
        boolean onThis = webWayRunning(webWayShown);
        if (!onThis) {
            applyWebPort(true);
            onThis = webWayRunning(webWayShown);
        }
        if (!onThis) {
            return;
        }
        paintWebDialog();
        openWebLink();
    }

    private static void toggleWebBridgeShown() {
        if (webWayShown != 'A' && webWayShown != 'B' && webWayShown != 'C') {
            return;
        }
        char active = webBridgeActiveWay();
        if (active == webWayShown) {
            stopOtherWebBridge((char) 0);
            refreshRunningChrome();
            paintWebDialog();
            return;
        }
        applyWebPort(true);
    }

    private static void refreshWebBridgeShown() {
        if (webWayShown == 'A' && WebBridgeA.isRunning()) {
            WebBridgeA.refreshSession();
            paintWebDialog();
            return;
        }
        if (webWayShown == 'B' && webWayRunning('B')) {
            WebBridge.refreshSession();
            paintWebDialog();
            return;
        }
        if (webWayShown == 'C' && webWayRunning('C')) {
            WebBridge.refreshSession();
            paintWebDialog();
        }
    }

    private static boolean confirmStartWayA() {
        if (UserConfig.isWebBridgeASkipWarn()) {
            return true;
        }
        JCheckBox skip = new JCheckBox("不再询问");
        Object[] msg = {
                "A 明文：消息在局域网里不加密，能抓包的人可能看到内容。",
                "请只在可信网络使用，不要发密码/验证码。",
                "需要加密请用「B 加密」。A/B 同时只能开一档。",
                skip
        };
        int r = JOptionPane.showConfirmDialog(webDialog, msg, "开启兼容（未加密）",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return false;
        }
        if (skip.isSelected()) {
            UserConfig.setWebBridgeASkipWarn(true);
        }
        return true;
    }

    private static void applyWebPort(boolean startIfStopped) {
        if (webWayShown != 'A' && webWayShown != 'B' && webWayShown != 'C') {
            return;
        }
        int port;
        try {
            port = Integer.parseInt(webPortField.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(webDialog, "端口要在 1–65535", "兼容模式", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (port < 1 || port > 65535) {
            JOptionPane.showMessageDialog(webDialog, "端口要在 1–65535", "兼容模式", JOptionPane.WARNING_MESSAGE);
            return;
        }
        UserConfig.setWebBridgePort(port);
        char active = webBridgeActiveWay();
        if (!startIfStopped) {
            // 只改端口：当前在跑的那档 rebind
            try {
                if ((active == 'B' || active == 'C') && WebBridge.port() != port) {
                    WebBridge.rebind(port);
                } else if (active == 'A' && WebBridgeA.port() != port) {
                    WebBridgeA.rebind(port);
                }
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "改端口失败" : e.getMessage();
                JOptionPane.showMessageDialog(webDialog, msg, "兼容模式", JOptionPane.ERROR_MESSAGE);
            }
            refreshRunningChrome();
            paintWebDialog();
            return;
        }
        if (webWayShown == 'A' && !confirmStartWayA()) {
            return;
        }
        stopOtherWebBridge(webWayShown);
        if (UserConfig.isWebAppExclusive() && isServerRunning()) {
            stopServer();
        }
        try {
            if (webWayShown == 'A') {
                WebBridgeA.start(port);
            } else if (webWayShown == 'C') {
                WebBridge.startTls(port);
            } else {
                WebBridge.start(port);
            }
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "启动失败" : e.getMessage();
            JOptionPane.showMessageDialog(webDialog, msg, "兼容模式", JOptionPane.ERROR_MESSAGE);
        }
        refreshRunningChrome();
        paintWebDialog();
    }

    private static String selectedLanHost() {
        if (comboIps == null || comboIps.getSelectedItem() == null) {
            return "127.0.0.1";
        }
        String host = comboIps.getSelectedItem().toString().trim();
        return host.isEmpty() ? "127.0.0.1" : host;
    }

    private static void showWebWay(char way) {
        webWayShown = way == 'A' || way == 'C' ? way : 'B';
        webCopiedUntil = 0;
        paintWebDialog();
    }

    /** 选中用描边+浅底；字始终深色（系统 L&F 常忽略按钮底色，白字会看不见）。 */
    private static void styleWayButton(JButton button, boolean current) {
        if (button == null) {
            return;
        }
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setForeground(TEXT);
        button.setBackground(current ? new Color(0xE8F0FF) : CARD);
        int thick = current ? 2 : 1;
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(current ? ACCENT : BORDER, thick),
                new EmptyBorder(UserConfig.s(4), UserConfig.s(8), UserConfig.s(4), UserConfig.s(8))));
        Font base = button.getFont();
        button.setFont(base.deriveFont(current ? Font.BOLD : Font.PLAIN, base.getSize2D()));
    }

    /** 切标签只换面板；A/B 互斥，真正启停在「启动/停止」。 */
    private static void paintWayChrome() {
        boolean onB = webWayShown == 'B';
        boolean onA = webWayShown == 'A';
        styleWayButton(webWayB, onB);
        styleWayButton(webWayA, onA);
        styleWayButton(webWayC, webWayShown == 'C');
        if (webHintLabel != null) {
            String html = switch (webWayShown) {
                case 'A' -> "<html>明文，靠扫码令牌。和 B、C 不能同时开。</html>";
                case 'C' -> "<html>HTTPS，正文仍加密。页面上可以安装证书，也有清除步骤。不装也能用，浏览器会提示不受信任。</html>";
                default -> "<html>正文加密。点二维码复制，点链接打开。</html>";
            };
            webHintLabel.setText(html);
        }
        if (webCaBtn != null) {
            webCaBtn.setVisible(webWayShown == 'C');
        }
        if (webPowerBtn != null) {
            webPowerBtn.setEnabled(true);
        }
        if (webPortField != null) {
            webPortField.setEnabled(true);
        }
    }

    private static void paintWebDialog() {
        if (webDialog == null || webStateLabel == null) {
            return;
        }
        paintWayChrome();
        if (webWayShown != 'A' && webWayShown != 'B' && webWayShown != 'C') {
            return;
        }
        boolean wayA = webWayShown == 'A';
        boolean onThis = webWayRunning(webWayShown);
        boolean session = wayA ? WebBridgeA.hasSession() : (onThis && WebBridge.hasSession());
        char active = webBridgeActiveWay();
        int livePort = active == 'A' ? WebBridgeA.port() : (active != 0 ? WebBridge.port() : 0);
        int listeners = wayA ? WebBridgeA.listenerCount() : WebBridge.listenerCount();
        int savedPort = UserConfig.getWebBridgePort();
        String state;
        if (System.currentTimeMillis() < webCopiedUntil) {
            state = "已复制链接";
        } else if (onThis) {
            if (!session) {
                state = "会话已过期。点「刷新会话」再扫一次。";
            } else if (webWayShown == 'C') {
                state = "HTTPS 0.0.0.0:" + livePort + "  ·  手机 " + listeners;
            } else {
                state = "监听 0.0.0.0:" + livePort + "  ·  手机 " + listeners;
            }
        } else if (active != 0) {
            state = "当前在跑 " + active + "（:" + livePort + "）。在此启动会关掉它。";
        } else {
            state = "未启动";
        }
        webStateLabel.setText(state);
        webPowerBtn.setText(onThis ? "停止" : (active != 0 ? "切换并启动" : "启动"));
        webRefreshBtn.setEnabled(onThis);
        if (!webPortField.isFocusOwner()) {
            int show = active != 0 ? livePort : savedPort;
            String text = Integer.toString(show);
            if (!text.equals(webPortField.getText().trim())) {
                webPortField.setText(text);
            }
        }
        String url = onThis
                ? (wayA ? WebBridgeA.pageUrl(selectedLanHost()) : WebBridge.pageUrl(selectedLanHost()))
                : null;
        if (url == null) {
            webQrImage = null;
            webQrPlaceholder = onThis ? "会话已过期，点「刷新会话」"
                    : (active != 0 ? ("正在跑 " + active + "，点「切换并启动」") : "启动后显示二维码");
            webUrlArea.setText("");
            webQrShown = null;
            if (webQrPanel != null) {
                webQrPanel.repaint();
            }
            return;
        }
        webUrlArea.setText(url);
        if (url.equals(webQrShown) && webQrImage != null) {
            return;
        }
        try {
            webQrImage = QR_Util.createQRImage(url, 360);
            webQrShown = url;
        } catch (Exception e) {
            webQrImage = null;
            webQrPlaceholder = "二维码生成失败";
            webQrShown = null;
        }
        if (webQrPanel != null) {
            webQrPanel.repaint();
        }
    }

    private static void openCaCert() {
        try {
            java.nio.file.Path cert = LanCert.caCertificate();
            copyText(cert.toAbsolutePath().toString());
            if (webStateLabel != null) {
                webStateLabel.setText("已复制根证书路径");
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(cert.toFile());
            }
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "打不开根证书" : e.getMessage();
            JOptionPane.showMessageDialog(webDialog, msg, "兼容模式", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void copyWebLink() {
        if (webUrlArea == null || webQrImage == null) {
            return;
        }
        String url = webUrlArea.getText();
        if (url == null || url.isBlank()) {
            return;
        }
        copyText(url);
        webCopiedUntil = System.currentTimeMillis() + 1200;
        if (webStateLabel != null) {
            webStateLabel.setText("已复制链接");
        }
    }

    private static void openWebLink() {
        if (webUrlArea == null) {
            return;
        }
        String url = webUrlArea.getText();
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // 下面统一提示
        }
        if (webStateLabel != null) {
            webStateLabel.setText("打不开默认浏览器");
        }
    }

    private static void showHelp() {
        String text = """
                TextSend PC  ·  协议 v1  ·  %s

                —— 按钮 ——
                · 「启动」左键：最多 1 人，并打开二维码。中键：最多 N 人（设置里改，默认 7）。右键：最多 1 人，不弹二维码。再点是停止。
                · 「发送」：有图片预览先发图；待发列表有选中则发这些文件；否则把输入框里的文字发出去。Ctrl+Enter 同样。
                · 「文件」：左键选文件加入待发，右键用系统文件框。选中后还要点「发送」才传。
                · 「取消」：停这一轮传输，网页正在传来的文件也停。待发列表还在；半截留在磁盘，再发会续上。
                · 「浏览」：往待发里加文件。Shift+浏览可选多个文件夹。
                · 「移除」：从待发列表拿掉选中项，不删磁盘上的文件。
                · 「下载目录」：打开本机「下载/TextSend」。
                · 「二维码」：手动打开连接二维码。Esc 关闭。
                · 「发到」：在固定 PIN 那一行，默认发给所有（App 和网页都收到）。只发 App：只给已连接的 App，绝不发网页（无客户端则提示，不回落，避免误发）。只发兼容：只给网页。发给所有时，App 服务没开仍可发网页；网页也没开就没有发送口。状态栏显示兼容端口和页面数。设置里「兼容模式与 App 互斥」默认关。
                · 「兼容模式」：在监听那一行。窗口里有 A 明文、B 加密、C 证书，默认停在 B。A/B/C 同时只开一档，共用网页端口（默认 54301）。C 是 HTTPS，正文仍加密。根证书可选：不装也能扫码打开，浏览器会提示不受信任；想去掉提示再点「根证书」，装进手机并完全信任。「启动」左键开或停；右键启动并用默认浏览器打开链接，已经在跑则只打开。网页可发文字、图片、文件；文件夹看浏览器是否支持。收到的文件进「下载/TextSend」，单张图片还会放进剪贴板。电脑发来的文件在网页里预览、下载或清空。点二维码复制链接，点链接用浏览器打开。
                · 「在线」：待授权、App 客户端和网页会话在同一张列表。服务端没开也能看网页。先对核对码，再允许 / 拒绝 / 拉黑；已连接可踢下线。网页可断开，也可拉黑。拉黑 App 只拒 App，拉黑网页只拒兼容模式。外接或本机键盘须按住 Alt：Alt+Y 允许、Alt+N 拒绝、Alt+B 拉黑、Alt+K 踢下线或断开网页，↑↓ 换一台。
                · 「设置」：探测、限流、冷却、App 黑名单、兼容模式黑名单、人数上限。
                · 「帮助」：本页。F1 同样。
                · 「置顶小窗」：收成小窗；Esc 回到大窗。

                —— 服务端 ——
                · 选择网卡 IP 和端口，点「启动」。
                · 启动后仍可换 IP，二维码和连接串会跟着变，不用停服务。
                · 左键启动：最多 1 个客户端，并打开二维码。
                · 中键启动：最多 N 个客户端（N 在「设置」里改，默认 7，范围 1–64）。
                · 右键启动：最多 1 个客户端，不弹二维码。
                · 可勾选「启动即开服」：下次打开自动开服（默认关）。会记住上次监听 IP。
                · 可勾选「固定 PIN」并输入 8 位数字：每次启动服务端用同一 PIN（默认关，随机 PIN + 长密钥）。
                · PIN 卡片：单击复制 ts://…/kPIN 连接串；双击复制 8 位 PIN。
                · 长密钥卡片：单击复制 PSK 连接串；双击复制长密钥。

                —— 客户端 ——
                · 粘贴连接串 ts://IP/k... 后点「连接」。端口可省略，默认 54300。
                · 可勾选「记住上次连接」：PIN 连成功后记住 IP、端口和 PIN（默认关，不记长密钥）。
                · 「设置」里可开局域网探测（默认关）：客户端列出同一局域网开服的 TextSend。
                · 点设备会向服务端请求临时授权；服务端出现「待授权」列表，两边显示同一串核对码，对得上再「允许 / 拒绝 / 拉黑」。打开列表时 Alt+Y 允许、Alt+N 拒绝、Alt+B 拉黑、Alt+K 踢下线，↑↓ 换一台，作用在蓝框那台。单独按字母会进输入框，不会误同意。
                · 设置可调授权限流（默认每 IP 每分钟 3 次）、拒绝后冷却（默认 10 秒）。App 黑名单和兼容模式黑名单分开。
                · 连接是 TCP，发现设备是 UDP（广播不能走 TCP）。默认「探测端口跟随连接端口」，两边同号。
                · 防火墙如果只放了 TCP，手机仍能连上，但发往电脑的探测进不来。请再放行同号 UDP。「有连接时关闭探测」默认开。
                · 服务端「已连接」列表可对某一客户端「踢下线」，无需重启服务。
                · 不要只填 IP。PIN 路径用 8 位短码，扫码用长密钥。
                · 握手成功后才清空输入框；失败或点断开则保留连接串。

                —— 手机连不上（安卓 14） ——
                · 电脑已「接入 TCP」却超时 0/8：多半是手机网络加速，不是 PIN。
                · 请对方开飞行模式后再开 Wi‑Fi，或关掉智能双通道 / 游戏加速。
                · 本机不要让同一网段同时挂在有线和 USB 网卡上。

                —— 小窗 ——
                · 点「置顶小窗」进入紧凑窗；Esc 回到大窗。

                —— 输入 ——
                · 大窗：窗口已聚焦时，点空白处或直接打字会进输入框；Ctrl+V 粘贴文字，图片仍先预览再发送。
                · Ctrl+Z 撤销；Ctrl+Y 重做（也可用 Ctrl+Shift+Z）。Mac 用 Cmd。
                · 文件：「浏览」左键 Java 对话框（可把文件拖进对话框，打开该文件所在文件夹）；右键用系统文件选择框。
                · 文件按列表一个个发。发送中仍可浏览/拖入追加，队列里的会接着发。
                · 列表里可点选、Ctrl 多选、Shift 连选、Ctrl+A 全选、Delete 移除。
                · 选中后点「发送」。发送中浏览/拖入会追加并接着发；没选中的不会自动捎上，可再选中点发送。
                · 对面落到「下载/TextSend」（重名自动加 (1)），不弹保存框。点「下载目录」用资源管理器打开本机该文件夹。
                · 图片 ≤20MB：PC 写入剪贴板可直接粘贴；更大的当文件保存。
                · 本机 Ctrl+V 图片会先出现在输入区预览，点缩略图或「查看大图」可看原图，点「发送」才传；Backspace 清除预览。
                · Shift+「浏览」打勾选多个文件夹，按相对路径在对面重建（软链默认不跟随，列表旁可开）。隐藏目录（如 .logger）会带上。进度显示 3/40。
                · 传输中断后可再发同一文件续传（认文件头哈希，不只是同名同大小）。暂无手动选断点。
                · 多个客户端时会提示将发给几台。

                —— 快捷键 ——
                · Ctrl+Shift+W / Ctrl+Q：退出程序
                · Ctrl+Enter：发送（列表有选中则发文件；输入框有焦点则发文字）
                · Ctrl+O：浏览添加文件（Java 对话框）
                · Ctrl+V：窗口聚焦即可粘贴到输入框（不必先点输入框）；图片进预览
                · Esc：小窗回到大窗
                · F1：帮助
                · 「在线」列表打开时：Alt+Y 允许，Alt+N 拒绝，Alt+B 拉黑，Alt+K 踢下线；↑↓ 换一台。单独按字母不会操作。Esc 关闭列表

                —— 缩放 ——
                · 鼠标放在标题旁的百分比上，滚轮 ±1%%；按住 Ctrl 时 ±0.1%%。
                · 双击百分比：恢复 100%%。
                · 范围 50%%–300%%，立刻写入配置文件。

                —— 二维码 ——
                · 左键启动会弹出二维码；有客户端连上后自动关闭。
                · 点「二维码」手动打开则保持显示，连上也不关。Esc 关闭。
                · 设置里「弹出窗口失焦即关」默认开：点到别处时，二维码、兼容模式、大图、帮助、设置这些窗口会关掉。主窗口不关。设置有改动时会先问是否保存。
                · 高 UI 缩放下二维码默认完整显示；可拖动窗口边角放大，始终整张码可见可扫。

                —— 配置文件 ——
                · 优先：程序同一目录的 textsend.properties（jar / 绿色版）。
                · 程序目录不能写时（系统安装包）：主目录下仅一份 .textsend.properties。
                · 不建 ~/.config。保存项含 maxClients、authRatePerMin、authCooldownSec、authBlacklist、webBlacklist、lanDiscover* 等。
                · 「启动即开服」默认关；会记住上次选的监听 IP，换网后该 IP 不在网卡列表则自动重选。
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
        JLabel repo = new JLabel("<html><a href=\"https://github.com/rmshadows/TextSend_Desktop\">GitHub · TextSend Desktop</a></html>");
        repo.setHorizontalAlignment(SwingConstants.CENTER);
        repo.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        repo.setBorder(new EmptyBorder(UserConfig.s(6), UserConfig.s(8), UserConfig.s(8), UserConfig.s(8)));
        repo.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(URI.create("https://github.com/rmshadows/TextSend_Desktop"));
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(helpDialog, "打不开浏览器", "帮助", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        JPanel helpBox = new JPanel(new BorderLayout());
        helpBox.setBackground(CARD);
        helpBox.add(sp, BorderLayout.CENTER);
        helpBox.add(repo, BorderLayout.SOUTH);
        if (helpDialog != null && helpDialog.isShowing()) {
            helpDialog.toFront();
            return;
        }
        JOptionPane helpPane = new JOptionPane(helpBox, JOptionPane.PLAIN_MESSAGE);
        helpDialog = helpPane.createDialog(frame, "帮助 · TextSend " + VERSION);
        helpDialog.setModalityType(Dialog.ModalityType.MODELESS);
        helpDialog.setResizable(true);
        helpDialog.pack();
        helpDialog.setLocationRelativeTo(frame);
        helpDialog.setVisible(true);
    }

    private static void copyText(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (Exception e) {
            System.err.println("clipboard: " + e.getMessage());
        }
    }

    private static void reinstallFileDrops() {
        if (fileTable != null) {
            fileTable.setDropMode(DropMode.ON);
            installFileDrop(fileTable);
            if (fileTable.getTableHeader() != null) {
                installFileDrop(fileTable.getTableHeader());
            }
        }
        if (scrollFiles != null) {
            installFileDrop(scrollFiles);
            if (scrollFiles.getViewport() != null) {
                installFileDrop(scrollFiles.getViewport());
            }
        }
        installFileDrop(filePanel);
        installFileDrop(labelFileQueue);
    }

    private static void installFileDrop(JComponent c) {
        if (c == null) {
            return;
        }
        if (c instanceof JTextComponent tc) {
            installTextAreaTransfer(tc);
            return;
        }
        c.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                if (support.isDrop() && (support.getSourceDropActions() & COPY) != 0) {
                    support.setDropAction(COPY);
                }
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || hasUriFlavor(support)) {
                    return true;
                }
                if (support.isDataFlavorSupported(DataFlavor.imageFlavor)
                        && support.getComponent() instanceof JTextComponent) {
                    return true;
                }
                return support.isDataFlavorSupported(DataFlavor.stringFlavor);
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
                    List<File> files = filesFromTransfer(support.getTransferable());
                    if (!files.isEmpty()) {
                        enqueueFiles(files);
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

    private static boolean hasUriFlavor(TransferHandler.TransferSupport support) {
        for (DataFlavor flavor : support.getDataFlavors()) {
            if (isUriFlavor(flavor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUriFlavor(DataFlavor flavor) {
        String sub = flavor.getSubType();
        if (sub == null) {
            return false;
        }
        sub = sub.toLowerCase(java.util.Locale.ROOT);
        return sub.contains("uri-list") || sub.contains("gnome-copied-files") || sub.contains("x-moz-url");
    }

    /** Linux 文件管理器通常给 text/uri-list，不一定给 javaFileListFlavor。 */
    private static List<File> filesFromTransfer(java.awt.datatransfer.Transferable t) {
        try {
            if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                Object data = t.getTransferData(DataFlavor.javaFileListFlavor);
                if (data instanceof List<?> list) {
                    List<File> files = new ArrayList<>();
                    for (Object o : list) {
                        if (o instanceof File f) {
                            files.add(f);
                        }
                    }
                    if (!files.isEmpty()) {
                        return files;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        List<File> fromText = List.of();
        for (DataFlavor flavor : t.getTransferDataFlavors()) {
            if (!isUriFlavor(flavor) && !flavor.isFlavorTextType()) {
                continue;
            }
            try {
                List<File> files = filesFromUriText(transferText(t.getTransferData(flavor)));
                if (files.isEmpty()) {
                    continue;
                }
                if (isUriFlavor(flavor)) {
                    return files;
                }
                fromText = files;
            } catch (Exception ignored) {
            }
        }
        return fromText;
    }

    private static String transferText(Object data) throws IOException {
        if (data instanceof String s) {
            return s;
        }
        if (data instanceof java.io.Reader r) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[2048];
            int n;
            while ((n = r.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
            r.close();
            return sb.toString();
        }
        if (data instanceof java.io.InputStream in) {
            byte[] b = in.readAllBytes();
            in.close();
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (data instanceof byte[] b) {
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        }
        return "";
    }

    private static List<File> filesFromUriText(String text) {
        List<File> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || "copy".equals(line) || "cut".equals(line)) {
                continue;
            }
            if (line.regionMatches(true, 0, "file:", 0, 5)) {
                File f = fileFromUri(line);
                if (f != null) {
                    out.add(f);
                }
            } else if (line.startsWith("/") && new File(line).exists()) {
                out.add(new File(line));
            }
        }
        return out;
    }

    private static File fileFromUri(String line) {
        try {
            URI uri = new URI(line.replace(" ", "%20"));
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            return new File(uri);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 输入框保留系统剪切/复制/选中，同时还能拖入文件和图片。
     * 直接 setTransferHandler 会盖掉 JTextArea 自带的，鼠标拖选和 Ctrl+X 会坏。
     */
    private static void installTextAreaTransfer(JTextComponent tc) {
        TransferHandler text = (TransferHandler) tc.getClientProperty("ts.textTh");
        if (text == null) {
            text = tc.getTransferHandler();
            tc.putClientProperty("ts.textTh", text);
        }
        TransferHandler textTh = text;
        tc.setDragEnabled(false);
        tc.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                if (support.isDataFlavorSupported(DataFlavor.imageFlavor)
                        || support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                        || hasUriFlavor(support)) {
                    return true;
                }
                return textTh != null && textTh.canImport(support);
            }

            @Override
            public boolean importData(TransferSupport support) {
                try {
                    if (support.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                        Object data = support.getTransferable().getTransferData(DataFlavor.imageFlavor);
                        BufferedImage img = data instanceof BufferedImage bi ? bi
                                : PasteUtil.toBuffered(data instanceof Image im ? im : null);
                        if (offerPasteImage(img)) {
                            return true;
                        }
                    }
                    List<File> files = filesFromTransfer(support.getTransferable());
                    if (!files.isEmpty()) {
                        enqueueFiles(files);
                        return true;
                    }
                } catch (Exception e) {
                    flashStatus("拖入失败: " + e.getMessage());
                    return false;
                }
                return textTh != null && textTh.importData(support);
            }

            @Override
            public int getSourceActions(JComponent comp) {
                return NONE;
            }

            @Override
            public void exportToClipboard(JComponent comp, Clipboard clip, int action)
                    throws IllegalStateException {
                if (textTh != null) {
                    textTh.exportToClipboard(comp, clip, action);
                }
            }
        });
    }

    private static void ensureFileTable() {
        if (fileTable != null) {
            fileTable.updateUI();
            if (fileTable.getTableHeader() != null) {
                fileTable.getTableHeader().updateUI();
            }
            reinstallFileDrops();
            if (buttonBrowse != null) {
                buttonBrowse.updateUI();
            }
            if (buttonInbox != null) {
                buttonInbox.updateUI();
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
        fileTable.setDropMode(DropMode.ON);
        fileTable.setShowHorizontalLines(true);
        fileTable.setShowVerticalLines(false);
        fileTable.setBackground(Color.WHITE);
        fileTable.setForeground(TEXT);
        fileTable.setGridColor(new Color(0xE0E0E0));
        fileTable.setToolTipText("拖入文件显示路径。点选 / Ctrl 多选 / Shift 连选 / Ctrl+A 全选 / Delete 移除");
        fileTable.getColumnModel().getColumn(0).setPreferredWidth(48);
        fileTable.getColumnModel().getColumn(0).setMaxWidth(64);
        fileTable.getColumnModel().getColumn(0).setMinWidth(40);
        fileTable.getColumnModel().getColumn(1).setPreferredWidth(160);
        fileTable.getColumnModel().getColumn(2).setPreferredWidth(360);
        fileTable.getColumnModel().getColumn(3).setPreferredWidth(80);
        fileTable.getColumnModel().getColumn(3).setMaxWidth(100);
        DefaultTableCellRenderer idxTip = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setHorizontalAlignment(SwingConstants.CENTER);
                setToolTipText(null);
                return c;
            }
        };
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
        fileTable.getColumnModel().getColumn(0).setCellRenderer(idxTip);
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
        popup.addSeparator();
        popup.add(new AbstractAction("打开下载目录") {
            @Override
            public void actionPerformed(ActionEvent e) {
                openInboxDir();
            }
        });
        fileTable.setComponentPopupMenu(popup);

        scrollFiles = new JScrollPane(fileTable);
        scrollFiles.setBorder(BorderFactory.createLineBorder(BORDER));
        scrollFiles.getViewport().setBackground(Color.WHITE);

        labelFileQueue = new JLabel();
        labelFileQueue.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        labelFileQueue.setToolTipText("点击展开或收起。文件可以拖到这一行或下面的列表");
        labelFileQueue.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    setFileListExpanded(!fileListExpanded);
                }
            }
        });
        buttonBrowse = new JButton("浏览");
        buttonBrowse.setToolTipText("左键选文件；Shift+左键打勾选多个文件夹（按相对路径重建）。右键：系统文件选择框");
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
        buttonInbox = new JButton("下载目录");
        buttonInbox.setToolTipText("打开本机「下载/TextSend」（收到的文件落在这里）");
        buttonInbox.addActionListener(e -> openInboxDir());
        fixToolbarButton(buttonInbox, 96);
        buttonRemoveFiles = new JButton("移除");
        buttonRemoveFiles.setToolTipText("从列表去掉所选（不删磁盘上的文件）");
        buttonRemoveFiles.addActionListener(e -> removeSelectedQueuedFiles());
        fixToolbarButton(buttonBrowse, 72);
        fixToolbarButton(buttonRemoveFiles, 72);
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
        fileBtns.add(buttonInbox);
        fileBtns.add(buttonRemoveFiles);
        fileHead.add(fileBtns, BorderLayout.EAST);
        installFileDrop(fileHead);

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
            scrollFiles.setPreferredSize(new Dimension(UserConfig.s(640), listHeight()));
            filePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            filePanel.setPreferredSize(null);
            scrollMessage.setPreferredSize(new Dimension(UserConfig.s(640), Math.max(UserConfig.s(90), listHeight() / 2)));
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

    private static int listHeight() {
        int avail = 0;
        if (filePanel != null && filePanel.getParent() != null) {
            avail = filePanel.getParent().getHeight();
        }
        if (avail <= 0 && frame != null) {
            avail = Math.max(0, frame.getHeight() - UserConfig.s(200));
        }
        if (avail <= 0) {
            return UserConfig.s(220);
        }
        return Math.max(UserConfig.s(140), avail / 2);
    }

    private static Object[] queueRow(File f) {
        File parent = f.getParentFile();
        return new Object[]{
                fileQueue.size(),
                f.getName(),
                parent == null ? f.getAbsolutePath() : parent.getAbsolutePath(),
                fmtBytes(f.length())
        };
    }

    private static void renumberFileRows() {
        for (int i = 0; i < fileModel.getRowCount(); i++) {
            fileModel.setValueAt(i + 1, i, 0);
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
        File start = lastFileChooserDir;
        if (start == null || !start.isDirectory()) {
            start = FileNames.downloads().toFile();
        }
        if (start == null || !start.isDirectory()) {
            start = new File(System.getProperty("user.home", "."));
        }
        List<File> dirs = FolderPickDialog.show(frame, start);
        if (dirs == null || dirs.isEmpty()) {
            return;
        }
        File first = dirs.get(0);
        if (first.getParentFile() != null) {
            lastFileChooserDir = first.getParentFile();
        }
        enqueueFolders(dirs);
    }

    private static void enqueueFolders(List<File> dirs) {
        if (dirs == null || dirs.isEmpty()) {
            return;
        }
        int trees = 0;
        int files = 0;
        for (File dir : dirs) {
            int n = enqueueFolder(dir, false);
            if (n > 0) {
                trees++;
                files += n;
            }
        }
        if (files == 0) {
            flashStatus("文件夹是空的或无法读取");
            return;
        }
        if (trees == 1) {
            flashStatus("已加入文件夹 " + FileNames.sanitize(dirs.get(0).getName()) + "（" + files + " 个文件）");
        } else {
            flashStatus("已加入 " + trees + " 个文件夹（" + files + " 个文件）");
        }
    }

    private static void enqueueFolder(File root) {
        enqueueFolder(root, true);
    }

    /** @return 加入的文件数 */
    private static int enqueueFolder(File root, boolean announce) {
        if (root == null || !root.isDirectory()) {
            if (announce) {
                flashStatus("请选择文件夹");
            }
            return 0;
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
                    if (!dir.equals(rootPath) && FolderPickDialog.skipJunk(name)) {
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
                    if (FolderPickDialog.skipJunk(name)) {
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
            if (announce) {
                flashStatus("读取文件夹失败: " + e.getMessage());
            }
            return 0;
        }
        if (files.isEmpty()) {
            if (announce) {
                flashStatus("文件夹是空的或无法读取");
            }
            return 0;
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
            fileModel.addRow(queueRow(f));
            added++;
        }
        if (added > 0) {
            setFileListExpanded(true);
            if (fileBusy) {
                appendToSendQueue(files);
            }
        }
        refreshFileQueueLabel();
        if (announce) {
            String msg = "已加入文件夹 " + treeName + "（" + added + " 个文件）";
            if (dup > 0) {
                msg += "，跳过重复 " + dup;
            }
            flashStatus(msg);
        }
        return added;
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

    /** 用系统文件管理器打开「下载/TextSend」。 */
    private static void openInboxDir() {
        try {
            Path dir = FileNames.inboxDir();
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir.toFile());
                flashStatus("已打开 下载/" + FileNames.FOLDER);
                return;
            }
            if (!openInboxFallback(dir)) {
                flashStatus("无法打开: " + dir);
            } else {
                flashStatus("已打开 下载/" + FileNames.FOLDER);
            }
        } catch (Exception e) {
            flashStatus("无法打开下载目录: " + e.getMessage());
        }
    }

    /** Desktop.OPEN 不可用时：Windows explorer / macOS open / Linux xdg-open。 */
    private static boolean openInboxFallback(Path dir) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        try {
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("explorer.exe", dir.toAbsolutePath().toString());
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", dir.toAbsolutePath().toString());
            } else {
                pb = new ProcessBuilder("xdg-open", dir.toAbsolutePath().toString());
            }
            pb.start();
            return true;
        } catch (Exception e) {
            return false;
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
        List<File> dirs = new ArrayList<>();
        List<File> onlyFiles = new ArrayList<>();
        for (File f : dropped) {
            if (f == null) {
                continue;
            }
            if (f.isDirectory()) {
                dirs.add(f);
            } else if (f.isFile()) {
                onlyFiles.add(f);
            }
        }
        if (!dirs.isEmpty()) {
            enqueueFolders(dirs);
        }
        if (onlyFiles.isEmpty()) {
            return;
        }
        ensureFileTable();
        int dup = 0;
        int added = 0;
        List<Integer> newRows = new ArrayList<>();
        List<File> justAdded = new ArrayList<>();
        Set<String> have = new HashSet<>();
        for (File q : fileQueue) {
            have.add(fileKey(q));
        }
        for (File f : onlyFiles) {
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
            fileModel.addRow(queueRow(f));
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
            if (dup > 0) {
                flashStatus("已在列表中");
            }
            return;
        }
        int queued = appendToSendQueue(justAdded);
        String msg = queued > 0 ? "已追加 " + queued + " 个，将依次发送" : "已加入 " + added + " 个文件";
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
        renumberFileRows();
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
        renumberFileRows();
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
            SendRoute route = routeSend();
            if (route.block != null) {
                flashStatus(route.block);
                return;
            }
            peers = route.app ? Math.max(0, TsServer.aliveCountCurrent()) : 0;
            if (route.app && peers > 1) {
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
        if (clientPeer != null) {
            clientPeer.prepareOutgoingSend();
        }
        TsServer.prepareOutgoingCurrent();
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
                            SendRoute route = routeSend();
                            if (route.block != null) {
                                err = route.block;
                                break sendMore;
                            }
                            int got = 0;
                            if (route.app) {
                                int n = TsServer.sendFileToAllCurrent(path, cancelFileSend::get, tree, rel, seq, of);
                                if (n > 0) {
                                    got += n;
                                } else if (!route.web) {
                                    err = n < 0 ? "服务未运行" : "没有已连接的客户端";
                                    break sendMore;
                                }
                            }
                            if (route.web) {
                                int n = pushFileToWebBridge(path, tree, rel, cancelFileSend::get, seq, of);
                                if (n > 0) {
                                    got++;
                                } else if (got == 0) {
                                    err = n == -1 ? "文件超过 20GB"
                                            : (n == -2 ? "网页收件已满，请在手机上清空" : "兼容模式会话已过期");
                                    break sendMore;
                                }
                            }
                            if (got == 0) {
                                err = "没有发送口";
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
            SendRoute route = routeSend();
            if (route.block != null || (!route.app && !route.web)) {
                flashStatus(route.block == null ? "没有发送口" : route.block);
                return false;
            }
            return true;
        }
        if (!isClientConnected || clientPeer == null || !clientPeer.isAlive()) {
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
                pasteInto(area);
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

    /** 图片进预览，文字进输入框（走系统粘贴，保留选区替换）。 */
    private static void pasteInto(JTextComponent area) {
        if (area == null) {
            return;
        }
        if (offerPasteImage(PasteUtil.getClipboardImage())) {
            return;
        }
        area.paste();
    }

    private static JTextComponent messageInput() {
        if (miniMode) {
            return miniTextArea != null && miniTextArea.isEditable() && miniTextArea.isShowing()
                    ? miniTextArea : null;
        }
        return areaMessage;
    }

    private static boolean isDedicatedTextField(Component c) {
        return c instanceof JTextComponent tc
                && tc != areaMessage
                && tc != miniTextArea;
    }

    private static boolean isClickInteractive(Component c) {
        while (c != null) {
            if (c instanceof AbstractButton || c instanceof JComboBox || c instanceof JTable
                    || c instanceof JList || c instanceof JTextComponent || c instanceof JScrollBar
                    || c instanceof JSlider) {
                return true;
            }
            c = c.getParent();
        }
        return false;
    }

    /** 窗口已聚焦时：点空白/打字/Ctrl+V 落到输入框（端口、固定 PIN 等输入栏除外）。 */
    private static void installWindowInputHooks() {
        if (windowInputHooks) {
            return;
        }
        windowInputHooks = true;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (frame == null || !frame.isDisplayable() || !frame.isFocused()) {
                return false;
            }
            Component fo = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (fo != null && !SwingUtilities.isDescendingFrom(fo, frame)) {
                return false;
            }
            if (isDedicatedTextField(fo)) {
                return false;
            }
            JTextComponent input = messageInput();
            if (input == null) {
                return false;
            }
            int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
            if (e.getID() == KeyEvent.KEY_PRESSED
                    && e.getKeyCode() == KeyEvent.VK_V
                    && (e.getModifiersEx() & (menu | InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0) {
                if (fo != input) {
                    input.requestFocusInWindow();
                    pasteInto(input);
                    return true;
                }
                return false;
            }
            if (e.getID() == KeyEvent.KEY_PRESSED
                    && !e.isControlDown() && !e.isMetaDown() && !e.isAltDown()
                    && fo != input) {
                int vk = e.getKeyCode();
                if (vk != KeyEvent.VK_TAB && vk != KeyEvent.VK_ESCAPE && vk != KeyEvent.VK_ENTER
                        && vk != KeyEvent.VK_SHIFT && vk != KeyEvent.VK_CONTROL
                        && vk != KeyEvent.VK_ALT && vk != KeyEvent.VK_META
                        && vk != KeyEvent.VK_WINDOWS && vk != KeyEvent.VK_CONTEXT_MENU
                        && vk != KeyEvent.VK_BACK_SPACE && vk != KeyEvent.VK_DELETE
                        && vk != KeyEvent.VK_LEFT && vk != KeyEvent.VK_RIGHT
                        && vk != KeyEvent.VK_UP && vk != KeyEvent.VK_DOWN
                        && vk != KeyEvent.VK_HOME && vk != KeyEvent.VK_END
                        && vk != KeyEvent.VK_PAGE_UP && vk != KeyEvent.VK_PAGE_DOWN
                        && vk != KeyEvent.VK_F1 && vk != KeyEvent.VK_UNDEFINED) {
                    input.requestFocusInWindow();
                }
            }
            if (e.getID() == KeyEvent.KEY_TYPED
                    && !e.isControlDown() && !e.isMetaDown() && !e.isAltDown()
                    && fo != input) {
                char ch = e.getKeyChar();
                if (ch >= 0x20 && ch != KeyEvent.CHAR_UNDEFINED) {
                    input.requestFocusInWindow();
                    input.replaceSelection(String.valueOf(ch));
                    return true;
                }
            }
            return false;
        });
        Toolkit.getDefaultToolkit().addAWTEventListener(ev -> {
            if (!(ev instanceof MouseEvent me) || me.getID() != MouseEvent.MOUSE_PRESSED) {
                return;
            }
            if (me.getButton() != MouseEvent.BUTTON1) {
                return;
            }
            if (frame == null || miniMode || !frame.isDisplayable()) {
                return;
            }
            Component src = me.getComponent();
            if (src == null || !SwingUtilities.isDescendingFrom(src, frame)) {
                return;
            }
            if (isClickInteractive(src) || isDedicatedTextField(src)) {
                return;
            }
            if (areaMessage != null && areaMessage.isShowing()) {
                areaMessage.requestFocusInWindow();
            }
        }, AWTEvent.MOUSE_EVENT_MASK);
        Toolkit.getDefaultToolkit().addAWTEventListener(ev -> {
            if (!(ev instanceof WindowEvent we) || we.getID() != WindowEvent.WINDOW_DEACTIVATED) {
                return;
            }
            if (!UserConfig.isPopupsCloseOnFocusLost()) {
                return;
            }
            Window w = we.getWindow();
            if (!(w instanceof JDialog) || w == frame || !w.isShowing()) {
                return;
            }
            Window next = we.getOppositeWindow();
            if (next != null && windowOwns(w, next)) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
                if (focused == w || (focused != null && windowOwns(w, focused))) {
                    return;
                }
                dismissPopup(w);
            });
        }, AWTEvent.WINDOW_EVENT_MASK);
    }

    /** 点到设置窗口外面时：没改动就关；开了失焦即关且有改动，先问是否保存。 */
    private static void closeSettingsFromOutside() {
        JDialog d = settingsDialog;
        if (d == null || settingsPrompt || settingsHandled) {
            return;
        }
        boolean ask = UserConfig.isPopupsCloseOnFocusLost()
                && settingsDirty != null && settingsDirty.getAsBoolean();
        if (!ask) {
            settingsHandled = true;
            d.setVisible(false);
            if (settingsAfterClose != null) {
                settingsAfterClose.run();
            }
            return;
        }
        if (!d.isShowing()) {
            d.setVisible(true);
        }
        settingsPrompt = true;
        try {
            int a = JOptionPane.showConfirmDialog(d, "设置有改动，是否保存？", "设置",
                    JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (a == JOptionPane.YES_OPTION) {
                if (settingsApply != null && settingsApply.getAsBoolean()) {
                    settingsHandled = true;
                    d.setVisible(false);
                    if (settingsAfterClose != null) {
                        settingsAfterClose.run();
                    }
                } else {
                    d.toFront();
                }
            } else if (a == JOptionPane.NO_OPTION) {
                settingsHandled = true;
                d.setVisible(false);
                if (settingsAfterClose != null) {
                    settingsAfterClose.run();
                }
            } else {
                d.toFront();
                d.requestFocus();
            }
        } finally {
            settingsPrompt = false;
        }
    }

    /** child 是否由 parent 拥有（设置里再弹出确认框时，不要把设置一起关掉）。 */
    private static boolean windowOwns(Window parent, Window child) {
        for (Window w = child; w != null; w = w.getOwner()) {
            if (w == parent) {
                return true;
            }
        }
        return false;
    }

    private static void dismissPopup(Window w) {
        if (w == null || !w.isShowing() || w == frame) {
            return;
        }
            if (w == settingsDialog) {
            closeSettingsFromOutside();
            return;
        }
        if (w == helpDialog) {
            helpDialog.setVisible(false);
            return;
        }
        if (w == webDialog) {
            hideWebDialog();
            return;
        }
        if (w == qrDialog) {
            closeQrDialog();
            return;
        }
        if (w instanceof JDialog dialog
                && dialog.getDefaultCloseOperation() == WindowConstants.DISPOSE_ON_CLOSE) {
            dialog.dispose();
            return;
        }
        w.setVisible(false);
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
        WebBridgeInbox.shared().abortActive();
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
            int genSize = Math.min(1024, Math.max(360, UserConfig.s(480)));
            BufferedImage qrSource = QR_Util.createQRImage(lastConnectUri, genSize);
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

            JPanel qrPanel = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    int w = getWidth();
                    int h = getHeight();
                    int side = Math.min(w, h);
                    if (side < 4 || qrSource == null) {
                        return;
                    }
                    int x = (w - side) / 2;
                    int y = (h - side) / 2;
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g2.drawImage(qrSource, x, y, side, side, null);
                    g2.dispose();
                }
            };
            qrPanel.setBackground(Color.WHITE);
            qrPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            qrPanel.setToolTipText("点击复制连接串；拖动窗口边角可放大（始终显示完整图案）");
            qrPanel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (lastConnectUri == null || lastConnectUri.isEmpty()) {
                        return;
                    }
                    copyText(lastConnectUri);
                    flashStatus("已复制连接串");
                }
            });
            qrPanel.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    qrPanel.repaint();
                }
            });

            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            int pad = UserConfig.s(40);
            int uriReserve = UserConfig.s(88);
            int maxSide = Math.min((int) (screen.width * 0.92), (int) (screen.height * 0.88) - uriReserve);
            int initialSide = Math.min(qrSource.getWidth() + pad, Math.max(maxSide, UserConfig.s(280)));
            qrPanel.setPreferredSize(new Dimension(initialSide, initialSide));

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
            panel.add(qrPanel, BorderLayout.CENTER);
            panel.add(uriArea, BorderLayout.SOUTH);
            qrDialog.setContentPane(panel);
            qrDialog.pack();
            qrDialog.setResizable(true);
            qrDialog.setMinimumSize(new Dimension(UserConfig.s(220), UserConfig.s(260)));
            qrDialog.setLocationRelativeTo(frame);
            JRootPane rp = qrDialog.getRootPane();
            rp.registerKeyboardAction(e -> closeQrDialog(),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_IN_FOCUSED_WINDOW);
            qrDialog.setVisible(true);
        } catch (Throwable e) {
            e.printStackTrace();
            flashStatus("二维码生成失败: " + e);
        }
    }

    private static boolean netIpsContains(String ip) {
        if (ip == null || ip.isBlank() || netIps == null) {
            return false;
        }
        for (String candidate : netIps) {
            if (ip.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveListenIp(String heuristic) {
        String saved = UserConfig.getListenIp();
        if (!saved.isEmpty() && netIpsContains(saved)) {
            return saved;
        }
        if (!saved.isEmpty()) {
            System.out.println("保存的监听 IP 不在当前网卡列表，改用自动选择: " + saved);
        }
        if (heuristic != null && !heuristic.isBlank()) {
            return heuristic;
        }
        return netIps.isEmpty() ? "127.0.0.1" : netIps.getFirst();
    }

    private static void selectListenIpInCombo(String ip) {
        if (comboIps == null) {
            return;
        }
        if (ip != null && netIpsContains(ip)) {
            comboIps.setSelectedItem(ip);
            return;
        }
        if (!netIps.isEmpty()) {
            comboIps.setSelectedIndex(0);
        }
    }

    private static void persistListenIp() {
        if (comboIps == null) {
            return;
        }
        Object selected = comboIps.getSelectedItem();
        if (selected == null) {
            return;
        }
        String ip = selected.toString().trim();
        if (!ip.isEmpty()) {
            UserConfig.setListenIp(ip);
        }
    }

    private static void persistCustomPin() {
        if (fieldCustomPin == null) {
            return;
        }
        String pin = fieldCustomPin.getText().trim();
        if (!pin.isEmpty() && !UserConfig.isValidPin(pin)) {
            flashStatus("PIN 须为 8 位数字");
            fieldCustomPin.setText(UserConfig.getCustomPin());
            if (checkCustomPin != null) {
                checkCustomPin.setSelected(UserConfig.isCustomPinEnabled());
            }
            return;
        }
        UserConfig.setCustomPin(pin);
    }

    private static void maybeAutoStartServer() {
        if (!autoStartPending || !UserConfig.isAutoStartServer() || !isServerMode || isServerRunning()) {
            return;
        }
        autoStartPending = false;
        if (!miniMode) {
            syncPortFromField();
        }
        startServer(false);
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
        int best = -1;
        for (String ip : ipAddr) {
            int score = lanScore(ip);
            if (score > best) {
                best = score;
                prefer = ip;
            }
        }
        if (prefer != null) {
            System.out.printf("猜测局域网 IP：%s%n", prefer);
        }
        System.err.println("TextSend " + VERSION + " 就绪，端口 " + serverListenPort);
        return prefer;
    }

    /** 优先家里 Wi‑Fi，避开 USB 共享 / ZeroTier / Hyper-V 等虚拟网卡。 */
    private static int lanScore(String ip) {
        if (ip == null || ip.contains(":")) {
            return -1;
        }
        if (ip.startsWith("127.") || ip.startsWith("169.254.")) {
            return -1;
        }
        if (ip.startsWith("10.147.") || ip.startsWith("100.")) {
            return 1;
        }
        if (ip.startsWith("192.168.137.") || ip.startsWith("192.168.56.")
                || ip.startsWith("192.168.193.")) {
            return 2;
        }
        if (ip.startsWith("192.168.31.") || ip.startsWith("192.168.1.")
                || ip.startsWith("192.168.0.") || ip.startsWith("192.168.2.")) {
            return 50;
        }
        if (ip.startsWith("192.168.")) {
            return 40;
        }
        if (ip.startsWith("172.")) {
            return 20;
        }
        if (ip.startsWith("10.")) {
            return 10;
        }
        return 0;
    }
}
