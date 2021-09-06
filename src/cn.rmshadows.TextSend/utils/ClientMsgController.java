package utils;

import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

import application.TextSendMain;

public class ClientMsgController implements Runnable {
	final static String FB_MSG = TextSendMain.FB_MSG;
	final static int MSG_LEN = TextSendMain.MSG_LEN;
	final static int SERVER_ID = TextSendMain.SERVER_ID;
	final static String AES_TOKEN = TextSendMain.AES_TOKEN;

	private static ObjectOutputStream oosStream;
	private static ObjectInputStream oisStream;
	// 服务器分配的ID
	public static int id;

	public ClientMsgController(Socket client){
		// 下面的流是唯一的，否则socket报错
		try {
			oosStream = new ObjectOutputStream(client.getOutputStream());
			oisStream = new ObjectInputStream(client.getInputStream());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void run() {
		new Thread(new ClientMsgR(oisStream, oosStream)).start();
	}

	/**
	 * PC端主动发送信息到移动端的方法
	 * 
	 */
	public static void sendMsgToServer(Message m) {
		new Thread(new ClientMsgT(oosStream, m)).start();
	}
}

/**
 * 客户端发送Msg到服务端
 * 
 * @author jessie
 *
 */
class ClientMsgT implements Runnable {
	private Message msg;
	private ObjectOutputStream oos;

	public ClientMsgT(ObjectOutputStream out, Message m) {
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
			TextSendMain.client_connected = false;
		}
	}
}

/**
 * 客户端接收服务端信息
 * 
 * @author jessie
 *
 */
class ClientMsgR implements Runnable {
	ObjectInputStream ois;
	ObjectOutputStream oos;

	public ClientMsgR(ObjectInputStream in, ObjectOutputStream out) {
		this.ois = in;
		this.oos = out;
	}

	@Override
	public void run() {
		try {
			boolean get_id = true;
			while (true) {
				// 断开操作在TextSendMain中实现
				Message m = (Message) ois.readObject();
				if (m.getId() == ClientMsgController.SERVER_ID) {
					if (get_id) {
						// 获取ID
						ClientMsgController.id = Integer.valueOf(m.getNotes());
						System.out.println("客户端获取到ID：" + String.valueOf(ClientMsgController.id));
						get_id = false;
					} else {
						if (m.getNotes().equals(ClientMsgController.FB_MSG)) {
							// 处理反馈信息
							System.out.println("服务器收到了消息。");
							TextSendMain.cleanText();
						} else {
							String text = decryptMsgToString(m);
							// 反馈服务器
							msgFeedBack(oos);
							System.out.println("收到服务器的消息："+text);
							copyToClickboard(text);
							pasteReceivedMsg();
						}
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			TextSendMain.client_connected = false;
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
			str += AES_Util.decrypt(TextSendMain.AES_TOKEN, s);
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

	// 反馈消息到服务端
	private static void msgFeedBack(ObjectOutputStream out) throws IOException {
		System.out.println("客户端发送反馈信息");
			out.writeObject(new Message(null, ClientMsgController.MSG_LEN, ClientMsgController.id, ClientMsgController.FB_MSG));
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
