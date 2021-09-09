package debug;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Scanner;

import application.TextSendMain;
import utils.AES_Util;
import utils.Message;

public class ClientMsg {
	// 服务器消息自带的ID
	final static int SERVER_ID = -200;
	// 服务器成功接收的反馈信息前缀 后面接的数字：0-成功 1-发送id到客户端 2-其他消息
	final static String FB_MSG = "cn.rmshadows.TextSend.ServerStatusFeedback";
	// 单个Msg拆分的长度
	final static int MSG_LEN = 1000;

	public static int id;
	private static Socket socket;
	private static ObjectInputStream oisInputStream;
	private static ObjectOutputStream oosObjectOutputStream;

	public static void main(String[] args) {
		try {
			socket = new Socket("127.0.0.1", 54300);
			oisInputStream = new ObjectInputStream(socket.getInputStream());
			oosObjectOutputStream = new ObjectOutputStream(socket.getOutputStream());
		} catch (Exception e1) {
			e1.printStackTrace();
		}
		new Thread(new ClientT(socket, oosObjectOutputStream)).start();
		new Thread(new ClientR(socket, oisInputStream, oosObjectOutputStream)).start();
	}
}

class ClientT implements Runnable {
	@SuppressWarnings("unused")
	private Socket socket;
	private ObjectOutputStream oos;

	public ClientT(Socket s, ObjectOutputStream out) {
		this.socket = s;
		this.oos = out;
	}

	@Override
	public void run() {
		try (Scanner sc = new Scanner(System.in);) {
			while (true) {
				String string = null;
				string = sc.nextLine();
				Message m = new Message(string, ClientMsg.MSG_LEN, ClientMsg.id, null);
				oos.writeObject(m);
				oos.flush();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}

class ClientR implements Runnable {
	private Socket socket;
	private ObjectInputStream ois;
	private ObjectOutputStream oos;
	public ClientR(Socket s, ObjectInputStream in, ObjectOutputStream out) {
		this.socket = s;
		this.ois = in;
		this.oos = out;
	}

	/**
	 * 将所给的加密msg对象转为解密后的string
	 * 
	 * @param m messageMsgController.FB_MSG类
	 * @return
	 */
	private String decryptMsgToString(Message m) {
		String str = "";
		for (String s : m.getData()) {
			str += AES_Util.decrypt(TextSendMain.AES_TOKEN, s);
		}
		return str;
	}

	@Override
	public void run() {
		try {
			boolean get_id = true;
			while (true) {
				Message m = (Message) ois.readObject();
				if (m.getId() == ClientMsg.SERVER_ID) {
					if (get_id) {
						// 获取ID
						ClientMsg.id = Integer.valueOf(m.getNotes());
						System.out.print("客户端获取到ID：" + String.valueOf(ClientMsg.id));
						get_id = false;
					} else {
						if (m.getNotes().equals(ClientMsg.FB_MSG)) {
							// 处理反馈信息
							System.out.println("服务器收到了消息。");
						} else {
							m.printData();
							// TODO
							// 反馈服务器
							msgFeedBack(oos);
							
						}
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {
				socket.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			System.out.println("关闭Client");
		}
	}

	private static void msgFeedBack(ObjectOutputStream out) throws IOException {
		System.out.println("客户端发送反馈信息");
			out.writeObject(new Message(null, ClientMsg.MSG_LEN, ClientMsg.id, ClientMsg.FB_MSG));
	}
}
