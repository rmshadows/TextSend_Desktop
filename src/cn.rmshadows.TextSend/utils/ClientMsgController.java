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

public class ClientMsgController implements Runnable {
    final static String FB_MSG = TextSendMain.FB_MSG;
    final static int MSG_LEN = TextSendMain.MSG_LEN;
    final static String SERVER_ID = TextSendMain.SERVER_ID;
    final static String AES_TOKEN = TextSendMain.AES_TOKEN;
    // 支持1 JSON(文本) 2 Object(直接传输GsonMessage)  SUPPORT-{"supportMode":[1, 2]}
    final static String SUPPORT_MODE = "{\"supportMode\":[1, 2]}";
    // 连接状态 -1:初始化 0:连接成功准备接受ID
    static int connectionStat = -1;
    // 传输模式（服务器传回来的）
    static int modeSet = -1;

    // 对象流 TODO:先写JSON
    private static ObjectOutputStream objectOutputStream;
    private static ObjectInputStream objectInputStream;

    // 字符流
    private static BufferedOutputStream bufferedOutputStream;
    private static BufferedInputStream bufferedInputStream;

    // 服务器分配的ID
    public static String clientId;

    public ClientMsgController(Socket client) {
        // 下面的流是唯一的，否则socket报错
        try {
//			objectOutputStream = new ObjectOutputStream(client.getOutputStream());
//			objectInputStream = new ObjectInputStream(client.getInputStream());
            bufferedOutputStream = new BufferedOutputStream(client.getOutputStream());
            bufferedInputStream = new BufferedInputStream(client.getInputStream());
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("ClientMsgController(Socket client) GetStream Error.");
        }
    }

    @Override
    public void run() {
        new Thread(new ClientMessageReceiver(objectInputStream, bufferedInputStream)).start();
    }

    /**
     * PC端主动发送信息到移动端的方法
     */
    public static void sendMsgToServer(Message m) {
        if(connectionStat == 0 || connectionStat == 1){
            new Thread(new ClientMessageTransmitter(objectOutputStream, bufferedOutputStream, m, 1)).start();
        }else{
            new Thread(new ClientMessageTransmitter(objectOutputStream, bufferedOutputStream, m, ClientMsgController.modeSet)).start();
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
    private final ObjectOutputStream objectOutputStream;
    private final BufferedOutputStream bufferedOutputStream;
    private int modeSet = -1;

    public ClientMessageTransmitter(ObjectOutputStream objectOutputStream, BufferedOutputStream bufferedOutputStream, Message m, int modeSet) {
        this.msg = m;
        this.objectOutputStream = objectOutputStream;
        this.bufferedOutputStream = bufferedOutputStream;
        this.modeSet = modeSet;
    }

    @Override
    public void run() {
        try {
            GsonMessage egm = GMToolsUtil.MessageToEncrypptedGsonMessage(msg);
            if (modeSet == 0 || modeSet == 1) {
                // JSON传输
                System.out.print("发送加密后的数据：" + egm);
                int read = -1;
                byte[] buf = new byte[1024];
                BufferedInputStream bis = new BufferedInputStream(new ByteArrayInputStream(egm.toString().getBytes(StandardCharsets.UTF_8)));
                while ((read = bis.read(buf)) != -1){
                    bufferedOutputStream.write(buf, 0, read);
                }
                bufferedOutputStream.flush();
            } else if (modeSet == 2) {
                System.out.print("发送加密后的数据(Object)：" + egm);
                objectOutputStream.writeObject(egm);
                objectOutputStream.flush();
            } else {
                System.out.println("传输模式设置有误: Modeset error: " + modeSet);
            }
        } catch (Exception e) {
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
    private final ObjectInputStream objectInputStream;
    private final BufferedInputStream bufferedInputStream;

    public ClientMessageReceiver(ObjectInputStream objectInputStream, BufferedInputStream bufferedInputStream) {
        this.objectInputStream = objectInputStream;
        this.bufferedInputStream = bufferedInputStream;
    }

    private int getConnectionStat() {
        return ClientMsgController.connectionStat;
    }

    @Override
    public void run() {
        try {
            ClientMsgController.connectionStat = 0;
            ClientMsgController.modeSet = 1;
            // 接收消息
            // 如果是-1说明连接已经断了
            byte[] readBuf = new byte[5];
            int readLength = -1;
            StringBuilder chunk = new StringBuilder();
            while ((readLength = bufferedInputStream.read(readBuf)) != -1) {
                if (!TextSendMain.isClientConnected) {
                    break;
                }
                String read = new String(readBuf, 0, readLength, StandardCharsets.UTF_8);
                chunk.append(read);
                if (read.endsWith("}")) {
                    System.out.println("Receive obj: " + chunk);
                    // 这里开始处理
                    GsonMessage egm = GMToolsUtil.JSONtoGsonMessage(String.valueOf(chunk));
                    // 解密后的信息
                    GsonMessage cgm = MessageCrypto.gsonMessageDecrypt(egm);
                    if (getConnectionStat() == 0) {
                        // 获取ID
                        // 服务器发送的才接受
                        if (cgm != null && Objects.equals(cgm.getId(), ClientMsgController.SERVER_ID)) {
                            ClientMsgController.clientId = cgm.getNotes();
                            System.err.println("获取到服务器分配的ID：" + ClientMsgController.clientId);
                            // 发送支持的模式 格式：SUPPORT-{"supportMode":[1]}
                            String supportMode = "SUPPORT-" + ClientMsgController.SUPPORT_MODE;
                            ClientMsgController.sendMsgToServer(new Message(ClientMsgController.clientId, "", TextSendMain.MSG_LEN, supportMode));
                            // 进入接受传输模式
                            ClientMsgController.connectionStat = 1;
                        } else {
                            System.out.println("Drop id message: " + cgm);
                        }
                    } else if (getConnectionStat() == 1) {
                        // 开始接受服务器发过来的传输模式
                        String[] tsp = new String[0];
                        if (cgm != null) {
                            tsp = cgm.getNotes().split("-");
                            // 服务器发送的才接受 {"id":"-200","data":"","notes":"CONFIRM-1"}
                            if (Objects.equals(cgm.getId(), ClientMsgController.SERVER_ID) && Objects.equals(tsp[0], "CONFIRM")) {
                                ClientMsgController.modeSet = Integer.parseInt(tsp[1]);
                                System.err.println("获取到服务器传输模式：" + ClientMsgController.modeSet);
                                // 进入通讯模式
                                ClientMsgController.connectionStat = 2;
                            } else {
                                System.out.println("Drop id message: " + cgm);
                            }
                        }
                    } else {
                        if (cgm != null) {
                            // 服务器发送的才接受
                            if (Objects.equals(cgm.getId(), ClientMsgController.SERVER_ID)) {
                                System.out.println(cgm);
                            } else {
                                System.out.println("Drop id message: " + cgm);
                            }
                        }
                    }
                    // reset chunk
                    chunk = new StringBuilder();
                }
            }
            System.out.println("Socket has ended.");
            ClientMsgController.connectionStat = -1;
            TextSendMain.isClientConnected = false;

//			while (true) {
//				// 断开操作在TextSendMain中实现
//				Message m = (Message) ois.readObject();
//				if (Objects.equals(m.getId(), ClientMsgController.SERVER_ID)) {
//					if (getConnectionStat() == 0) {
//						// 获取ID
//						ClientMsgController.id = m.getNotes();
//						System.out.println("客户端获取到ID：" + ClientMsgController.id);
//						// 获取到ID 进入下一步
//						ClientMsgController.connectionStat = 1;
//					} else {
//						if (m.getNotes().equals(ClientMsgController.FB_MSG)) {
//							// 处理反馈信息
//							System.out.println("服务器收到了消息。");
//							TextSendMain.cleanTextArea();
//						} else {
//							String text = decryptMsgToString(m);
//							// 反馈服务器
//							msgFeedBack(oos);
//							System.out.println("收到服务器的消息："+text);
//							copyToClickboard(text);
//							pasteReceivedMsg();
//						}
//					}
//				}
//			}
        } catch (Exception e) {
            e.printStackTrace();
            TextSendMain.isClientConnected = false;
        }
    }

    /**
     * 模拟键盘-粘贴 粘贴收到的文字
     */
    private void pasteReceivedMsg() {
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
    private static void msgFeedBack(ObjectOutputStream out) throws IOException {
        System.out.println("客户端发送反馈信息");
        out.writeObject(new Message(ClientMsgController.clientId, null, ClientMsgController.MSG_LEN, ClientMsgController.FB_MSG));
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
        System.out.println("已复制到剪辑板。");
    }
}
