package utils;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import application.TextSendMain;

/**
 * 启动一个服务器端口，监听。 分发Socket，仅保留一个链接
 * 
 * @author jessie
 *
 */
public class SocketDeliver implements Runnable {
	public static List<Socket> socket_list = Collections.synchronizedList(new ArrayList<Socket>());
	// 创建一个线程池
	private ExecutorService executorService = Executors.newFixedThreadPool(10);
	private static boolean started = false;
	static ServerSocket server;

	/**
	 * 停止分发socket，不会停止原有链接
	 */
	public static void stopSocketDeliver() {
		if (started) {
			try {
				server.close();
				for (Socket s : socket_list) {
					try {
						s.close();
					}catch (Exception e) {
						e.printStackTrace();
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public void run() {
		System.err.println("启动电脑端TextSend服务...");
		try {
			server = new ServerSocket(TextSendMain.getPort(), 1);
			started = true;
			// 监听是否停止
			new Thread(new Runnable() {
				@Override
				public void run() {
					while (TextSendMain.is_running) {
						try {
							Thread.sleep(1000);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
					stopSocketDeliver();
				}
			}).start();
			// 分发socket
			while (true) {
				// 如果有链接，不继续分发
				while (socket_list.size() >= 1) {
					Thread.sleep(1000);
				}
				System.out.println("Socket分发中。。。");
				final Socket socket = server.accept();
				socket_list.add(socket);
				executorService.execute(new Thread(new ServerMsgController(socket, socket_list)));
			}
		} catch (BindException e) {
			System.out.println("Port Already in use.");
		} catch (SocketException e) {
			System.out.println(e.toString());
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			System.out.println("Log: Socket Closing...");
		}
	}
}
