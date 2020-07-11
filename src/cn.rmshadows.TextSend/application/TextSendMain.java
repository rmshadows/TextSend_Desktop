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
		 * textField:文本框
		 * sendText:发送
		 * startServer:启动服务
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
					String str = textField.getText().toString();
					if (str.equals("")) {
						
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
		}
		finally {
			th.interrupt();
		}
		PORT = g.getPort();
		System.err.println("设置服务端口："+PORT);
	}

	/**
	 * main方法
	 * @param args
	 */
	public static void main(String[] args) {
		// TODO 自动生成的方法存根
		setPort();
		javax.swing.SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				createAndShowGUI();
			}
		});
	}
}

/**
 * 设置端口的对话框
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
			}
			else if (po.equals("")) {
				setName("54300");
			}
			else {
				setName(po);
			}
		} catch (Exception e) {
			// TODO: handle exception
			System.err.println("退出程序");
			System.exit(0);
		}
		finally {
//			System.out.println("Done.");
		}
	}
}
