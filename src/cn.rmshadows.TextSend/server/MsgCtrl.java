package server;

import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;
import java.io.InputStream;
import java.net.Socket;
import java.util.ArrayList;
/**
 * 消息处理模块
 * @author jessie
 *
 */
public class MsgCtrl extends Thread {
	ArrayList<Socket> listIn;
	Socket socket;
	static ArrayList<Socket> staticList;

	public MsgCtrl(Socket socket, ArrayList<Socket> list) {
		this.listIn = list;
		staticList = list;
		this.socket = socket;
		this.start();
	}

	@Override
	public void run() {
		try {
			InputStream inStream = socket.getInputStream();
//			OutputStream outStream = socket.getOutputStream();
//			PrintWriter out = new PrintWriter(outStream, true);
//			out.println("连接成功！");
			System.out.println("用户"+socket.getInetAddress().getHostAddress() + " 已上线。");
			byte[] buf = new byte[512];
			while (true) {
				int bytes_read = inStream.read(buf);
				if (bytes_read < 0) {
					break;
				} else {
					System.out.print("收到手机消息:");
					String x = new String(buf, 0, bytes_read);
					x = AES_Util.decrypt("RmY@TextSend!", x);
					System.out.println(x);
					copyToClickboard(x);
					keyboard();
					try {
						String re = "(i386@received):" + x.substring(0, 1);
						re = AES_Util.encrypt("RmY@TextSend!", re);
						tBack(re);
					} catch (Exception e) {
						String retry = "(i386@RETRY)";
						retry = AES_Util.encrypt("RmY@TextSend!", retry);
						tBack(retry);
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			listIn.remove(socket);
		}
	}
	
	/**
	 * 模拟键盘-粘贴
	 */
	private void keyboard(){
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
			// TODO: handle exception
			System.out.println("ROBOT ERROR");
		}

	}
	
	/**
	 * 被动返回核对信息到移动端，确保消息无误
	 * @param str
	 */
	private void tBack(String str) {
		for (int i = 0, n = listIn.size(); i < n; i++) {
			Socket sock = (Socket) listIn.get(i);
			try {
				sock.getOutputStream().write(str.getBytes());
				sock.getOutputStream().flush();
			} catch (Exception e) {
				e.printStackTrace();
				System.err.println(e.toString());
			}
		}
	}
	
	/**
	 * PC端主动发送信息到移动端的方法
	 * @param str
	 */
	public static void sendMsg(String str) {
		System.out.println("向手机发送信息：" + str);
		for (int i = 0, n = staticList.size(); i < n; i++) {
			Socket sock = (Socket) staticList.get(i);
			try {
				sock.getOutputStream().write(str.getBytes());
				sock.getOutputStream().flush();
			} catch (Exception e) {
				e.printStackTrace();
				System.err.println(e.toString());
			}
		}
	}

	/**
	 * 复制收到的消息到剪贴板
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
		if (ret.equals(text)) {

		} else {
			Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
			// 封装文本内容
			Transferable trans = new StringSelection(text);
			// 把文本内容设置到系统剪贴板
			clipboard.setContents(trans, null);
		}
		System.out.println("已复制到剪辑板。");
	}
}
