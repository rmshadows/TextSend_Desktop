package utils;

import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.LinkedList;
import java.util.List;

import application.TextSendMain;

public class ServerMsgController implements Runnable {
	final static String FB_MSG = TextSendMain.FB_MSG;
	final static int MSG_LEN = TextSendMain.MSG_LEN;
	final static int SERVER_ID = TextSendMain.SERVER_ID;
	final static String AES_TOKEN = TextSendMain.AES_TOKEN;

	private static List<Socket> socket_list = new LinkedList<Socket>();
	private static Socket socket = new Socket();
	private static ObjectOutputStream oosStream;
	private static ObjectInputStream oisStream;
	private static int client_id;

	public ServerMsgController(Socket socket, List<Socket> socket_list) throws IOException {
		ServerMsgController.socket = socket;
		ServerMsgController.socket_list = socket_list;
		ServerMsgController.client_id = socket.hashCode();

		// 下面的流是唯一的，否则socket报错
		oosStream = new ObjectOutputStream(socket.getOutputStream());
		oisStream = new ObjectInputStream(socket.getInputStream());
		// 发送ID
		ServerMsgController.sendMsgToClient(new Message(null, ServerMsgController.MSG_LEN,
				ServerMsgController.SERVER_ID, String.valueOf(ServerMsgController.client_id)));
		System.out.println(String.format("用户 %s (%d) 已上线。", socket.getInetAddress().getHostAddress(), client_id));
	}

	@Override
	public void run() {
		// 启动监听器
		Thread receiver = new Thread(new ServerMsgR(socket, oisStream, client_id));
		receiver.start();
		try {
			receiver.join();
			socket_list.remove(socket);
			socket.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * PC端主动发送信息到移动端的方法
	 * 
	 */
	public static void sendMsgToClient(Message m) {
		new Thread(new ServerMsgT(socket, oosStream, m)).start();
	}
}

/**
 * 服务端发送Msg到客户端
 * 
 * @author jessie
 *
 */
class ServerMsgT implements Runnable {
	@SuppressWarnings("unused")
	private Socket socket;
	private Message msg;
	private ObjectOutputStream oos;

	public ServerMsgT(Socket s, ObjectOutputStream out, Message m) {
		this.socket = s;
		this.msg = m;
		this.oos = out;
	}

	@Override
	public void run() {
		try {
			System.out.print("发送加密后的数据：");
			msg.printData();
			oos.writeObject(msg);
			oos.flush();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}

/**
 * 服务端接收客户端信息
 * 
 * @author jessie
 *
 */
class ServerMsgR implements Runnable {
	@SuppressWarnings("unused")
	private Socket socket;
	private int id;
	ObjectInputStream ois;

	public ServerMsgR(Socket s, ObjectInputStream in, int id) {
		this.socket = s;
		this.ois = in;
		this.id = id;
	}

	@Override
	public void run() {
		while (true) {
			Message m;
			try {
				m = (Message) ois.readObject();
				// id一致才显示信息
				if (m.getId() == id) {
					// 如果是反馈信息
					if (m.getNotes().equals(ServerMsgController.FB_MSG)) {
						// 说明客户端收到了消息，清空文本框。
						System.out.println("客户端收到了信息。");
						TextSendMain.cleanText();
					} else {
						// 消息处理
						String msg = decryptMsgToString(m);
						System.out.println(msg);
						msgFeedBack();
						System.out.println("收到客户端的消息："+msg);
						copyToClickboard(msg);
						pasteReceivedMsg();
					}
				}
			} catch (EOFException e) {
				break;
			} catch (Exception e) {
				e.printStackTrace();
				break;
			}
		}
	}

	/**
	 * 将所给的加密msg对象转为解密后的string
	 * 
	 * @param m message类
	 * @return
	 */
	private String decryptMsgToString(Message m) {
		String str = "";
		for (String s : m.getData()) {
			System.out.println("正在解密："+s);
			str += AES_Util.decrypt(ServerMsgController.AES_TOKEN, s);
		}
		return str;
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

	/**
	 * 反馈核对信息到移动端，确保消息无误
	 * 
	 */
	private static void msgFeedBack() {
		System.out.println("发送反馈信息到客户端。");
		ServerMsgController.sendMsgToClient(new Message(null, ServerMsgController.MSG_LEN,
				ServerMsgController.SERVER_ID, ServerMsgController.FB_MSG));
	}

	/**
	 * 复制收到的消息到剪贴板
	 * 
	 * @param text
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
