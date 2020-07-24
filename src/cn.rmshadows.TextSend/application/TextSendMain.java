package application;

/**
 * TextSend
 * Ryan Yim
 * Java Swing
 * version 2.0
 */
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import server.AES_Util;
import server.MainServer;
import server.MsgCtrl;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class TextSendMain {

	static String PORT;

	private static void createAndShowGUI() {
		JFrame.setDefaultLookAndFeelDecorated(true);
		JFrame mainFrame = new JFrame("TextSend");
		mainFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
//		Image icon = Toolkit.getDefaultToolkit().getImage("");
		mainFrame.setIconImage(null);

		mainFrame.setSize(130, 90);
		mainFrame.setLayout(null);

		/**
		 * textField:文本框 sendText:发送 startServer:启动服务
		 */
		JPanel panelForTextField = new JPanel();
		JPanel panelForButtons = new JPanel();

		JButton startServer = new JButton("启动");
		JButton sendText = new JButton("发送");
		JTextField textField = new JTextField(30);

		textField.setEditable(true);
		textField.setBounds(0, 0, 122, 30);
		startServer.setBounds(0, 0, 60, 30);
		sendText.setBounds(60, 0, 60, 30);

		panelForTextField.setLayout(null);
		panelForButtons.setLayout(null);

		panelForTextField.add(textField);
		panelForButtons.add(startServer);
		panelForButtons.add(sendText);

		panelForTextField.setBounds(0, 0, 120, 30);
		panelForButtons.setBounds(0, 30, 120, 30);

		mainFrame.add(panelForTextField);
		mainFrame.add(panelForButtons);

		/**
		 * 启动按钮时事件
		 */
		startServer.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				serverMain(PORT);
			}
		});
		/**
		 * 发送按钮事件
		 */
		sendText.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				try {
					String str = "(amd6@received):" + textField.getText().toString();
					if (str.equals("(amd6@received):")) {

					} else {
						str = AES_Util.encrypt("RmY@TextSend!", str);
						MsgCtrl.sendMsg(str);
						textField.setText("");
					}
				} catch (Exception e2) {
					// TODO: handle exception
				}
			}
		});

		mainFrame.setResizable(false);
		mainFrame.setVisible(true);
		mainFrame.setAlwaysOnTop(true);
		mainFrame.setLocationRelativeTo(null);// 居中显示
	}

	/**
	 * 启动服务端
	 * 
	 * @param PORT String
	 */
	static void serverMain(String PORT) {
		new Thread(new Runnable() {
			public void run() {
				MainServer ms = new MainServer();
				ms.StartServer(Integer.valueOf(PORT));
			}
		}).start();
	}

	static void getIP() throws SocketException {// get all local ips
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
					getStatus = true;
				} else if (in instanceof Inet6Address) {
					System.out.println(" - IPv6地址:" + in.getHostAddress());
					getStatus = true;
				}
			}
			if (getStatus) {
				n += 1;
			}
		}
		System.out.println("<--没有第" + n + "组网卡，如果以上结果没有显示出你所在局域网的IP地址。请手动查看您的IPv4地址谢谢-->\n");
		System.out.println("请在您的TextSend安卓客户端中输入手机与电脑同在的局域网的IPv4地址(不出问题的话上面应该有你需要的IP)。");
		try {
			System.out.println("\n正在初始化...");
			Thread.sleep(2345);
		} catch (InterruptedException e) {
			// TODO 自动生成的 catch 块
			e.printStackTrace();
		}
		System.err.println("\n======>>>>>> 好的，准备就绪！现在，请点击“启动”按钮！<<<<<<======");
	}

	/**
	 * 设置服务端口
	 */
	static void setPort() {
		GetPort g = new GetPort();
		Thread th = new Thread(g);
		th.start();
		try {
			th.join(8000);
		} catch (InterruptedException e1) {
			// TODO 自动生成的 catch 块
			e1.printStackTrace();
		} finally {
			th.interrupt();
		}
		PORT = g.getPort();
		System.err.println("设置服务端口：" + PORT);
	}

	/**
	 * main方法
	 * 
	 * @param args
	 * @throws SocketException
	 */
	public static void main(String[] args) throws SocketException {
		// TODO 自动生成的方法存根
		setPort();
		getIP();
		javax.swing.SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				createAndShowGUI();
			}
		});
	}
}

/**
 * 设置端口的对话框
 * 
 * @author jessie
 *
 */
class GetPort implements Runnable {
	private String port;

	public String getPort() {
		return port;
	}

	public void setName(String port) {
		this.port = port;
	}

	@Override
	public void run() {
		String po = null;
		try {
			po = JOptionPane.showInputDialog("请选择服务端口，默认54300端口(未确认将视为退出程序)");
			if (po.equals(null)) {
				setName("54300");
			} else if (po.equals("")) {
				setName("54300");
			} else {
				setName(po);
			}
		} catch (Exception e) {
			// TODO: handle exception
			System.err.println("退出程序");
			System.exit(0);
		} finally {
//			System.out.println("Done.");
		}
	}
}
