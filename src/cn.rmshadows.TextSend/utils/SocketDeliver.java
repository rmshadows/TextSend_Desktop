package utils;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import application.TextSendMain;

/**
 * 启动一个服务器端口，监听。 分发Socket，仅保留一个链接
 *
 * @author jessie
 */
public class SocketDeliver implements Runnable {
    // Socket ID Mode
    public static List<Socket> socket_list = Collections.synchronizedList(new ArrayList<>());
    // 创建一个线程池
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);
    private static boolean started = false;
    static ServerSocket server;

    public static AtomicBoolean scheduleControl = new AtomicBoolean(false);
    public static AtomicBoolean socketDeliveryControl = new AtomicBoolean(false);

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
                    } catch (Exception e) {
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
        System.err.println("启动电脑端Textsend服务...");
        try {
            server = new ServerSocket(TextSendMain.getServerListenPort(), 1);
            started = true;
            scheduleControl.set(true);
            // 监听是否停止
            new Thread(() -> {
                Runnable Task = ()->{
                    // 如果服务停止 Socket停止
                    if(!TextSendMain.isServerRunning()){
                        stopSocketDeliver();
                        scheduleControl.set(false);
                    }
                };
                new ScheduleTask(Task, 1,1, scheduleControl, TimeUnit.SECONDS).startTask();
            }).start();
            socketDeliveryControl.set(true);
            Runnable SocketDelivery = () -> {
                // 分发socket
                if(socket_list.size() < TextSendMain.maxConnection){
                    final Socket socket;
                    try {
                        System.out.println("Socket分发中......");
                        socket = server.accept();
                        socket_list.add(socket);
                        executorService.execute(new Thread(new ServerMsgController(socket, socket_list)));
                    } catch (IOException e) {
                        socketDeliveryControl.set(false);
                        throw new RuntimeException(e);
                    }
                }
            };
            new ScheduleTask(SocketDelivery, 1, 1, socketDeliveryControl, 500, 800, TimeUnit.SECONDS).startTask();
        } catch (BindException e) {
            TextSendMain.stopServer();
            System.out.println("Port Already in use.");
        } catch (Exception e) {
            TextSendMain.stopServer();
            e.printStackTrace();
        } finally {
            System.out.println("Log: Socket Closing...");
        }
    }
}
