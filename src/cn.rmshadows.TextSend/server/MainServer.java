package server;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
/**
 * 启动一个服务器端口，监听。
 * @author jessie
 *
 */
public class MainServer {
	public void StartServer(int port){
		System.err.println("启动电脑端TextSend服务...");
		ArrayList<Socket> list = new ArrayList<>();
		ServerSocket serverSocket = null;
		try {
			serverSocket = new ServerSocket(port);
			while (true) {
				Socket socket = serverSocket.accept();
				list.add(socket);
				new MsgCtrl(socket, list);
			}
		}
		catch (BindException e) {
			System.out.println("Port Already in use.");
		}
		catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {
				serverSocket.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
