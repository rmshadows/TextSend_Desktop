package application;

import java.awt.Desktop;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;
import java.util.LinkedList;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import utils.ClientMsgController;
import utils.Message;
import utils.QR_Util;
import utils.ServerMsgController;
import utils.SocketDeliver;

/**
 * TextSend Ryan Yim Java Swing version 3.0
 */
public class TextSendMain {
	// 服务器消息自带的ID
	final public static int SERVER_ID = -200;
	// 服务器成功接收的反馈信息
	final public static String FB_MSG = "cn.rmshadows.TextSend.ServerStatusFeedback";
	// 单个Msg拆分的长度
	final public static int MSG_LEN = 1000;
	// 加密用的Token
	final public static String AES_TOKEN = "cn.rmshadows.TS_TOKEN";
	// 服务运行的端口号
	private static String PORT;
	// 猜测的IP地址
	private static String Prefer_Addr;
	// Socket Server服务是否正在运行
	public static boolean is_running = false;
	// 是否是服务界面
	@SuppressWarnings("unused")
	private static boolean is_server = true;
	// 作为客户端使用时，生成的Socket
	private static Socket client = null;
	// 客户端是否连接
	public static boolean client_connected = false;
	// 网卡IP地址
	private static LinkedList<String> net_ip = new LinkedList<String>();

	// 下面是Swing界面组件
	private static JFrame frame;
	private static JPanel panelForTextField;
	private static JPanel panelForButtons;
	private static JButton start;
	private static JButton mode;
	private static JScrollPane s;
	private static JTextArea ta;
	private static JComboBox<String> ips;
	/**
	 * 客户端界面
	 */
	private static void textSendClient() {
		System.out.println("Client GUI");
		JFrame.setDefaultLookAndFeelDecorated(true);
		frame = new JFrame();
		panelForTextField = new JPanel();
		panelForButtons = new JPanel();
		start = new JButton();
		mode = new JButton();
		s = new JScrollPane();
		ta = new JTextArea();

		frame.setTitle("Text Send PC Client");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setIconImage(null);

		frame.setSize(400, 300);
		frame.setLayout(null);

		start.setText("连接");
		mode.setText("切换");
		s = new JScrollPane();
		ta = new JTextArea();

		// 设置字体
		ta.setFont(new Font(null, 0, 20));
		// 设置自动换行
		ta.setLineWrap(true);

		s.setViewportView(ta);
		s.setBounds(1, 1, 388, 223);
		panelForTextField.add(s);

		start.setBounds(50, 3, 90, 35);
		mode.setBounds(250, 3, 90, 35);

		panelForTextField.setLayout(null);
		panelForButtons.setLayout(null);

		panelForButtons.add(start);
		panelForButtons.add(mode);

		panelForTextField.setBounds(0, 0, 390, 225);
		panelForButtons.setBounds(0, 225, 390, 48);
//		panelForTextField.setBackground(new Color(0, 1, 0));
//		panelForButtons.setBackground(new Color(1, 0, 0));

		frame.add(panelForTextField);
		frame.add(panelForButtons);

		/**
		 * 连接按钮事件
		 */
		start.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (is_running) {
					is_running = false;
					client_connected = false;
					start.setText("连接");
					mode.setText("切换");
					// 停止连接
					try {
						client.close();
					} catch (IOException e1) {
						e1.printStackTrace();
					}
				} else {
					is_running = true;
					mode.setText("发送");
					start.setText("断开");
					// 连接
					try {
						String IP_Addr = "";
						int port = 54300;
						if (ta.getText().contains(":")) {
							String[] connection = ta.getText().split(":");
							IP_Addr = connection[0];
							port = Integer.valueOf(connection[1]);
						} else {
							IP_Addr = ta.getText();
						}
						System.out.println("地址：" + IP_Addr + "       端口：" + String.valueOf(port));
						client = new Socket(IP_Addr, port);
						new Thread(new ClientMsgController(client)).start();
						// 监视连接断开就恢复按钮状态
						new Thread(new Runnable() {
							@Override
							public void run() {
								while (client_connected) {
									try {
										Thread.sleep(1000);
									} catch (InterruptedException e) {
										e.printStackTrace();
									}
								}
								try {
									client.close();
								} catch (IOException e1) {
									e1.printStackTrace();
								}
								is_running = false;
								client_connected = false;
								start.setText("连接");
								mode.setText("切换");
							}
						}).start();
						client_connected = true;
					} catch (Exception e1) {
						e1.printStackTrace();
						// 连接失败
						System.out.println("客户端连接失败。");
						is_running = false;
						client_connected = false;
						start.setText("连接");
						mode.setText("切换");
					}
				}
			}
		});

		/**
		 * 发送按钮事件
		 */
		mode.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (is_running) {
					ClientMsgController
							.sendMsgToServer(new Message(ta.getText(), MSG_LEN, ClientMsgController.id, AES_TOKEN));
				} else {
					frame.setVisible(false);
					is_server = true;
					textSendServer();
				}
			}
		});
		frame.setResizable(false);
		frame.setVisible(true);
		frame.setAlwaysOnTop(true);
		frame.setLocationRelativeTo(null);// 居中显示
	}

	/**
	 * 服务端界面
	 */
	private static void textSendServer() {
		System.out.println("Server GUI");
		JFrame.setDefaultLookAndFeelDecorated(true);
		frame = new JFrame();
		panelForTextField = new JPanel();
		panelForButtons = new JPanel();
		start = new JButton();
		mode = new JButton();
		s = new JScrollPane();
		ta = new JTextArea();
		ips = new JComboBox<String>();
		
		// 添加IP 
		for(String ip:net_ip) {
			ips.addItem(ip);
		}
		
		// 选择默认IP
		ips.setSelectedItem(Prefer_Addr.split(":")[0]);

		frame.setTitle("Server");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		frame.setSize(130, 90);
		frame.setLayout(null);

		start.setText("启动");
		mode.setText("切换");

		// 设置字体
//		ta.setFont(new Font(null, 0, 20));
		// 设置自动换行
		ta.setLineWrap(true);

		s.setViewportView(ta);
		s.setBounds(1, 1, 388, 223);

		ta.setBounds(0, 0, 122, 30);
		s.setBounds(0, 0, 122, 30);
		
		ips.setBounds(0, 0, 122, 30);
		
		start.setBounds(0, 0, 60, 30);
		mode.setBounds(60, 0, 60, 30);

		panelForTextField.setLayout(null);
		panelForButtons.setLayout(null);
		
		panelForTextField.add(ips);
		
		panelForButtons.add(start);
		panelForButtons.add(mode);

		panelForTextField.setBounds(0, 0, 120, 30);
		panelForButtons.setBounds(0, 30, 120, 30);

		frame.add(panelForTextField);
		frame.add(panelForButtons);

		/**
		 * 启动按钮时事件
		 */
		start.addMouseListener(new MouseListener() {
			@Override
			public void mouseClicked(MouseEvent e) {
				// 鼠标左键 启动浏览器生成二维码
				if (e.getButton() == 1) {
					if (is_running) {
						panelForTextField.remove(s);
						panelForTextField.add(ips);
						start.setText("启动");
						mode.setText("切换");
						is_running = false;
					} else {
						String selected_ip = (String) ips.getSelectedItem();
						// 替换组件
						panelForTextField.remove(ips);
						panelForTextField.add(s);
//						System.out.println(selected_ip);
						// 生成二维码 启动默认浏览器
						File QR_file = QR_Util.serverQRCode(String.format("%s:%d", selected_ip, getPort()));
						QR_file.deleteOnExit();
						// 用浏览器打开
						// 解决Windows下的兼容问题
						String qr_url = QR_file.getAbsolutePath().replace("\\", "/");
						// 转义空格
						qr_url = qr_url.replace(" ", "%20");
						browser(String.format("file:///%s", qr_url));
						server(PORT);
						is_running = true;
						mode.setText("发送");
						start.setText("停止");
					}
				} else {// 不启动浏览器 不生成二维码
					if (is_running) {
						panelForTextField.remove(s);
						panelForTextField.add(ips);
						start.setText("启动");
						mode.setText("切换");
						is_running = false;
					} else {
						panelForTextField.remove(ips);
						panelForTextField.add(s);
						server(PORT);
						is_running = true;
						start.setText("停止");
						mode.setText("发送");
					}
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

		/**
		 * 发送按钮事件
		 */
		mode.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (is_running) {
					Message m = new Message(ta.getText(), MSG_LEN, SERVER_ID, null);
					ServerMsgController.sendMsgToClient(m);
				} else {
					frame.setVisible(false);
					is_server = false;
					textSendClient();
				}
			}
		});
		frame.setAlwaysOnTop(true);
		frame.setResizable(false);
		frame.setVisible(true);
		frame.setLocationRelativeTo(null);// 居中显示
	}

	/**
	 * main方法
	 * 
	 * @param args
	 * @throws SocketException
	 */
	public static void main(String[] args) throws SocketException {
		setPort();
		Prefer_Addr = getIP() + ":" + PORT;
		javax.swing.SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				textSendServer();
			}
		});
	}

	/**
	 * 启动服务端
	 * 
	 * @param PORT String
	 */
	private static void server(String port) {
		new Thread(new SocketDeliver()).start();
	}

	/**
	 * 清空文本框中的文字
	 * 
	 * @return
	 */
	public static void cleanText() {
		ta.setText("");
	}

	/**
	 * 打印网卡IP并返回可能的局域网IP
	 * 
	 * @throws SocketException
	 */
	public static String getIP() throws SocketException {// get all local ips
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
		net_ip = ip_addr;
		
		for (String ip : ip_addr) {
			try {
				if (ip.substring(0, 7).equals("192.168")) {
					prefer_ip = ip;
					break;
				} else if (ip.substring(0, 4).equals("172.")) {
					prefer_ip = ip;
					find172 = true;
				} else if (ip.substring(0, 3).equals("10.")) {
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
		if (prefer_ip.substring(0, 7).equals("192.168")) {
			System.out.println(String.format("猜测您当前的局域网IP是：%s ，具体请根据实际情况进行选择。", prefer_ip));
		} else {
			System.out.println(String.format("未能猜测到您当前的局域网IP，将使用：%s 作为启动二维码地址！具体可将实际IP填入文本框后启动!", prefer_ip));
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
	 * 设置服务端口
	 */
	static void setPort() {
		GetPort g = new GetPort();
		Thread th = new Thread(g);
		th.start();
		try {
			th.join(30000);
		} catch (InterruptedException e1) {
			e1.printStackTrace();
		} finally {
			th.interrupt();
		}
		PORT = g.getPort();
		System.err.println("设置服务端口：" + PORT);
	}

	/**
	 * @title 使用默认浏览器打开
	 * @param url 要打开的网址
	 */
	private static void browser(String url) {
		System.out.println("Log: 尝试用浏览器显示二维码...");
		Desktop desktop = Desktop.getDesktop();
		if (Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.BROWSE)) {
			URI uri;
			try {
				uri = new URI(url);
				desktop.browse(uri);
			} catch (URISyntaxException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * 返回端口
	 * 
	 * @return
	 */
	public static int getPort() {
		return Integer.valueOf(PORT);
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
		boolean showing = true;
		while (showing) {
			try {
				po = JOptionPane.showInputDialog("请选择服务端口，默认54300端口(未确认将视为退出程序)");
				if (po.equals(null)) {
					setName("54300");
					showing = false;
				} else if (po.equals("")) {
					setName("54300");
					showing = false;
				} else {
					System.out.println("asdsa");
					try {
						Integer.valueOf(po);
					} catch (NumberFormatException e) {
						JOptionPane.showMessageDialog(null, "请输入数字!", "错误", 1);
						showing = true;
						continue;
					}
					setName(po);
					showing = false;
				}
			} catch (Exception e) {
				System.err.println("退出程序");
				System.exit(0);
			}
		}
	}
}
