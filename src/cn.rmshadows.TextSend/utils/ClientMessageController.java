package utils;

import application.TextSendMain;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class ClientMessageController implements Runnable {
    final static String FB_MSG = TextSendMain.FB_MSG;
    final static int MSG_LEN = TextSendMain.MSG_LEN;
    final static String SERVER_ID = TextSendMain.SERVER_ID;
    // 支持1 JSON(文本) 2 Object(直接传输GsonMessage)  SUPPORT-{"supportMode":[1, 2]}
    final static String SUPPORT_MODE = "{\"supportMode\":[1, 2]}";
    // 连接状态 -1:初始化 0:连接成功准备接受ID 1:ID接受成功，准备接受模式（已经将支持的模式发出）2:收到服务器返回的模式 进入正常通信
    static int connectionStat = -1;
    // 传输模式（服务器传回来的）传输模式 1:JSON(默认) 2:Java Class Object
    static int transmissionModeSet = -1;
    public static Socket socket;
    // 服务器分配的ID
    public static String clientId;

    public ClientMessageController(Socket client) {
        socket = client;
    }

    @Override
    public void run() {
        // 初始化成功必定连接成功
        connectionStat = 0;
        new Thread(new ClientMessageReceiver(socket)).start();
    }

    /**
     * PC端主动发送信息到移动端的方法
     */
    public static void sendMessageToServer(Message m) {
        // 初始化就用JSON发送
        if (connectionStat == 0 || connectionStat == 1) {
            new Thread(new ClientMessageTransmitter(socket, m, 1)).start();
        } else {
            // 根据模式来选择
            new Thread(new ClientMessageTransmitter(socket, m, ClientMessageController.transmissionModeSet)).start();
        }
    }
}

/**
 * 客户端发送Msg到服务端
 *
 * @author jessie
 */
class ClientMessageTransmitter implements Runnable {
    private final Message msg;
    private BufferedOutputStream bufferedOutputStream = null;
    private final int transmitterTransmissionMode;

    public ClientMessageTransmitter(Socket socket, Message m, int modeSet) {
        this.msg = m;
        this.transmitterTransmissionMode = modeSet;
        try {
            // 1:JSON 2:Object
            bufferedOutputStream = new BufferedOutputStream(socket.getOutputStream());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        try {
            // 先获取加密的GSM
            GsonMessage egm = GMToolsUtil.MessageToEncrypptedGsonMessage(msg);
            msg.printData();
            if (transmitterTransmissionMode == 0 || transmitterTransmissionMode == 1) {
                // JSON传输
                if(msg.getNotes().equals(ServerMessageController.FB_MSG)){
                    System.out.println("Log: 【发送反馈】JSON ==> ：" + egm);
                }else {
                    System.out.println("Log: 【发送】JSON ==> ：" + egm);
                }
                // 将GSM对象读取成文字传输
                int read;
                byte[] buf = new byte[1024];
                BufferedInputStream bufferedInputStream = new BufferedInputStream(new ByteArrayInputStream(egm.toString().getBytes(StandardCharsets.UTF_8)));
                while ((read = bufferedInputStream.read(buf)) != -1) {
                    bufferedOutputStream.write(buf, 0, read);
                }
                bufferedOutputStream.flush();
                // 会关闭输入流（GSM对象读取完了就关闭），不会关闭输出流(会关闭Socket)
                bufferedInputStream.close();
            } else if (transmitterTransmissionMode == 2) {
                // OBJECT传输
                if(msg.getNotes().equals(ServerMessageController.FB_MSG)){
                    System.out.println("Log: 【发送反馈】OBJECT ==> ：" + egm);
                }else {
                    System.out.println("Log: 【发送】OBJECT ==> ：" + egm);
                }
                // 将对象序列化为字节数组并分块发送
                byte[] begm = GMToolsUtil.gsonMessage2bytes(egm);
                if (begm != null) {
                    // 将GSM对象读取成byte传输
                    bufferedOutputStream.write(begm);
                    bufferedOutputStream.flush();
                }
            } else {
                throw new IOException("传输模式设置有误: Mode set error: " + transmitterTransmissionMode);
            }
        } catch (Exception e) {
            System.err.println("ClientMessageTransmitterError: ");
            e.printStackTrace();
            TextSendMain.isClientConnected = false;
        }
    }
}

/**
 * 客户端接收服务端信息
 * 流程：
 * 1.首先接受服务端给的ID
 * 2.发送自己支持的服务
 * 3.接受服务端分配模式
 * 4.证常通讯
 *
 * @author jessie
 */
class ClientMessageReceiver implements Runnable {
    private BufferedInputStream bufferedInputStream = null;
    private static int receiverTransmissionMode = -1;
    private int count = 0;

    public ClientMessageReceiver(Socket socket) {
        try {
            // 开始都是用JSON
            if (ClientMessageController.transmissionModeSet == 1 || getConnectionStat() == 0 || getConnectionStat() == 1 || getConnectionStat() == -1) {
                bufferedInputStream = new BufferedInputStream(socket.getInputStream());
                receiverTransmissionMode = 1;
            }
            else {
                throw new IOException("Mode Set Error.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 获取连接状态
    private int getConnectionStat() {
        return ClientMessageController.connectionStat;
    }

    @Override
    public void run() {
        try {
            while (TextSendMain.isClientConnected) {
                if (receiverTransmissionMode == 1) {
                    // 重复赋值(暂未处理)
                    ClientMessageController.connectionStat = 0;
                    ClientMessageController.transmissionModeSet = 1;
                    // 接收消息
                    // 如果是-1说明连接已经断了
                    byte[] readBuf = new byte[1024];
                    int readLength;
                    StringBuilder chunk = new StringBuilder();
                    while (receiverTransmissionMode == 1 && (readLength = bufferedInputStream.read(readBuf)) != -1) {
                        if (!TextSendMain.isClientConnected) {
                            break;
                        }
                        String read = new String(readBuf, 0, readLength, StandardCharsets.UTF_8);
                        chunk.append(read);
                        // 读取到JSON末尾
                        if (read.endsWith("}")) {
                            System.out.println("Log: 【接收】JSON <== : " + chunk);
                            // 这里开始处理
                            GsonMessage egm = GMToolsUtil.JSONtoGsonMessage(String.valueOf(chunk));
                            // 解密后的信息
                            GsonMessage cgm = MessageCrypto.gsonMessageDecrypt(egm);
                            if (getConnectionStat() == 0) {
                                // 获取ID
                                // 服务器发送的才接受
                                if (cgm != null && Objects.equals(cgm.getId(), ClientMessageController.SERVER_ID)) {
                                    ClientMessageController.clientId = cgm.getNotes();
                                    System.err.println("Log: 【接收】获取到服务器分配的ID：" + ClientMessageController.clientId);
                                    // 发送支持的模式 格式：SUPPORT-{"supportMode":[1]}
                                    String supportMode = "SUPPORT-" + ClientMessageController.SUPPORT_MODE;
                                    ClientMessageController.sendMessageToServer(new Message(ClientMessageController.clientId, "", TextSendMain.MSG_LEN, supportMode));
                                    // 进入接受传输模式
                                    ClientMessageController.connectionStat = 1;
                                } else {
                                    System.out.println("Log: 【丢弃】Drop id message (on get id) : " + cgm);
                                }
                            } else if (getConnectionStat() == 1) {
                                // 开始接受服务器发过来的传输模式
                                String[] tsp;
                                if (cgm != null) {
                                    tsp = cgm.getNotes().split("-");
                                    // 服务器发送的才接受 {"id":"-200","data":"","notes":"CONFIRM-1"}
                                    // 判断服务器ID 且CONFIRM开头
                                    if (Objects.equals(cgm.getId(), ClientMessageController.SERVER_ID) && Objects.equals(tsp[0], "CONFIRM")) {
                                        // 设置客户端传输模式
                                        ClientMessageController.transmissionModeSet = Integer.parseInt(tsp[1]);
                                        // 设置客户端接收模式
                                        receiverTransmissionMode = ClientMessageController.transmissionModeSet;
                                        System.err.println("Log: 【接收】获取到服务器传输模式：" + ClientMessageController.transmissionModeSet);
                                        // 进入通讯模式
                                        ClientMessageController.connectionStat = 2;
                                    } else {
                                        // 丢弃的信息
                                        System.out.println("Log: 【丢弃】Drop id message (on get modeSet) : " + cgm);
                                    }
                                }
                            } else {
                                if (cgm != null) {
                                    // 服务器发送的才接受
                                    if (Objects.equals(cgm.getId(), ClientMessageController.SERVER_ID)) {
                                        if (cgm.getNotes().equals(ClientMessageController.FB_MSG)) {
                                            // 处理反馈信息
                                            System.out.println("Log: 【接收反馈】服务器收到了消息。");
                                            TextSendMain.cleanTextArea();
                                        } else {
                                            StringBuilder text = new StringBuilder();
                                            for (String c : cgm.getData()) {
                                                text.append(c);
                                            }
                                            // 反馈服务器 注意：仅代表客户端收到信息
                                            messageFeedBack();
                                            System.out.println("Log: 【接收】JSON <== 服务器的消息：" + text);
                                            copyToClickboard(text.toString());
                                            pasteReceivedMessage();
                                        }
                                    } else {
                                        // 丢弃的常规通讯信息
                                        System.out.println("Log: 【丢弃】Drop id message (json mode) : " + cgm);
                                    }
                                }
                            }
                            // reset chunk
                            chunk = new StringBuilder();
                        }
                    }
                } else if (receiverTransmissionMode == 2) {
                    // 传输对象 传输对象的时候已经进入正常通信了
                    // -2 表示连接断开了 只有服务在运行、客户端没断开才会继续监听
                    // 断开操作在TextSendMain中实现 这里已经解密成明文GM了
                    // 读取对象字节数组并反序列化
                    // 用于记录上次的值
                    byte[] chunk = null;
                    byte[] readBuf = new byte[1024];
                    while (receiverTransmissionMode == 2 && bufferedInputStream.read(readBuf) != -1) {
                        // 如果服务停止
                        if (!TextSendMain.isClientConnected) {
                            break;
                        }
                        // 如果是第一次，就赋值
                        if(chunk == null){
                            chunk = readBuf.clone();
                        }else {
                            // 不是就合并
                            chunk = GMToolsUtil.mergeArrays(chunk, readBuf);
                        }
                        // 检查是否到达了结束标记
                        if (GMToolsUtil.bendsWith(chunk, TextSendMain.endMarker)) {
                            // 到达了结束标记，停止读取
                            GsonMessage egm = GMToolsUtil.bytes2GsonMessage(chunk);
                            // 解密后的信息
                            GsonMessage cgm = MessageCrypto.gsonMessageDecrypt(egm);
                            if (cgm != null) {
                                // 客户端发送的才接受
                                if (Objects.equals(cgm.getId(), TextSendMain.SERVER_ID)) {
                                    if (cgm.getNotes().equals(ServerMessageController.FB_MSG)) {
                                        // 处理反馈信息
                                        System.out.println("Log: 【接收反馈】2:服务器收到了消息。");
                                        TextSendMain.cleanTextArea();
                                    } else {
                                        StringBuilder text = new StringBuilder();
                                        for (String c : cgm.getData()) {
                                            text.append(c);
                                        }
                                        // 反馈客户端 注意：仅代表服务端收到信息
                                        messageFeedBack();
                                        System.out.println("Log: 【接收】OBJECT <== 服务器的消息：" + text);
                                        copyToClickboard(text.toString());
                                        pasteReceivedMessage();
                                    }
                                } else {
                                    // 丢弃的常规通讯信息
                                    System.out.println("Log: 【丢弃】2:Drop id message (object mode) : " + cgm);
                                }
                                chunk = null;
                            }
                        }
                    }
                } else {
                    throw new IOException("Mode set error.");
                }
                count ++ ;
                if(count > 10){
                    System.out.println("Log: Count 10 次，结束Socket。");
                    break;
                }
            }
            System.out.println("Log: Socket has ended.");
            ClientMessageController.connectionStat = -1;
            TextSendMain.isClientConnected = false;
        } catch (Exception e) {
            System.err.println("ClientMessageReceiverError: ");
            e.printStackTrace();
            TextSendMain.isClientConnected = false;
        }
    }

    /**
     * 模拟键盘-粘贴 粘贴收到的文字
     */
    private void pasteReceivedMessage() {
        try {
            Robot robot = new Robot();
            robot.delay(400);
            robot.keyPress(KeyEvent.VK_CONTROL);
            robot.delay(100);
            robot.keyPress(KeyEvent.VK_V);
            robot.delay(100);
            robot.keyRelease(KeyEvent.VK_CONTROL);
            robot.delay(100);
            robot.keyRelease(KeyEvent.VK_V);
            robot.delay(100);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("ROBOT ERROR");
        }

    }

    // 反馈消息到服务端
    private static void messageFeedBack() {
//        System.out.println("客户端发送反馈信息");
        ClientMessageController.sendMessageToServer(new Message(ClientMessageController.clientId, null, ClientMessageController.MSG_LEN, ClientMessageController.FB_MSG));
    }

    /**
     * 复制收到的消息到剪贴板
     */
    private static void copyToClickboard(String text) {
        String ret = "";
        Clipboard sysClip = Toolkit.getDefaultToolkit().getSystemClipboard();
        // 获取剪切板中的内容
        Transferable clipTf = sysClip.getContents(null);
        if (clipTf != null) {
            // 检查内容是否是文本类型
            if (clipTf.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                try {
                    ret = (String) clipTf.getTransferData(DataFlavor.stringFlavor);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        if (!ret.equals(text)) {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            // 封装文本内容
            Transferable trans = new StringSelection(text);
            // 把文本内容设置到系统剪贴板
            clipboard.setContents(trans, null);
        }
//        System.out.println("已复制到剪辑板。");
    }
}
