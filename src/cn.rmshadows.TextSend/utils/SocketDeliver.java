package utils;

import ScheduleTask.ScheduleTask;
import application.TextSendMain;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动一个服务器端口，监听。 分发Socket
 *
 * @author jessie
 */
public class SocketDeliver implements Runnable {
    // Socket ID Mode
    public static final List<ServerMessageController> socketList = Collections.synchronizedList(new LinkedList<>());
    // 创建一个线程池
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    // 服务Socket
    static ServerSocket server;

    // 控制定时器停止
    public static AtomicBoolean scheduleControl = new AtomicBoolean(false);
    // 控制Socket分发 true为允许分发 false 不允许分发，但保持现有连接
    public static AtomicBoolean socketDeliveryControl = new AtomicBoolean(false);

    /**
     * 服务端会把消息广播给所有客户端
     */
    public static void sendMessageToAllClients(Message m) {
        for (ServerMessageController s : SocketDeliver.socketList) {
            new Thread(new ServerMessageTransmitter(s, m)).start();
        }
    }

    /**
     * 检测到服务端停止，从内部停止socket
     */
    public static void stopSocketDeliver() {
        // 关闭服务端Socket
        try {
            server.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        // 关闭所有客户端Socket)(现有连接)
        for (ServerMessageController s : socketList) {
            try {
                s.getSocket().close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        // 二次赋值了
        scheduleControl.set(false);
        socketDeliveryControl.set(false);
        System.err.println("Socket Server shutdown.");
    }

    @Override
    public void run() {
        System.err.println("启动电脑端Textsend服务...");
        try {
            /* backlog是ServerSocket类中的参数，用来指定ServerSocket中等待客户端连接队列的最大数量，并且每调用一次accept方法，就从等待队列中取一个客户端连接出来，因此队列又空闲出一个位置出来，这里有两点需要注意：
                1、将等待队列设置得过大，容易造成内存溢出，因为所有的客户端连接都会堆积在等待队列中；
                2、不断的调用accpet方法如果是长任务容易内存溢出，并且文件句柄数会被耗光。
             */
            server = new ServerSocket(TextSendMain.getServerListenPort(), TextSendMain.maxConnection);
            // 监听服务是否停止
            scheduleControl.set(true); // 开启定时器
            new Thread(() -> {
                Runnable Task = () -> {
                    // 如果服务停止 Socket停止
                    if (!TextSendMain.isServerRunning()) {
                        stopSocketDeliver();
                        scheduleControl.set(false);
                    } else {
                        // 如果服务端开启多连接 显示连接数
                        if(TextSendMain.maxConnection != 1){
                            int clientCount = socketList.size();
                            TextSendMain.setClientCount(clientCount);
                        }
                    }
                };
                new ScheduleTask(Task, 1, 1, scheduleControl, TimeUnit.SECONDS).startTask();
            }).start();
            // 控制Socket是否继续分发
            socketDeliveryControl.set(true);
            Runnable SocketDeliveryTask = () -> {
                // 分发socket
                if (socketList.size() < TextSendMain.maxConnection) {
                    final Socket socket;
                    try {
                        System.out.println("Socket is delivering......");
                        socket = server.accept();
                        ServerMessageController client = new ServerMessageController(socket);
                        // 断开后删除列表的方法写在ServerMessageController
                        socketList.add(client);
                        // 启动定时任务 如果连接成功则取消运行 不成功就断开Socket
                        Runnable connectTimeout = () -> {
                            try {
                                Thread.sleep(8000);
                                if (client.getConnectionStat() != 2) {
                                    client.closeCurrentClientSocket();
                                    System.err.println("连接超时，断开客户端。");
                                } else {
                                    System.out.println("检测到客户端连接成功");
                                }
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        };
                        new Thread(connectTimeout).start();
                        executorService.execute(new Thread(client));
                    } catch (IOException e) {
                        socketDeliveryControl.set(false);
                        throw new RuntimeException(e);
                    }
                }
            };
            new ScheduleTask(SocketDeliveryTask, 1, 1, socketDeliveryControl,
                    500, 800, TimeUnit.SECONDS).startTask();
        } catch (BindException e) {
            TextSendMain.stopServer();
            System.out.println("Port Already in use.");
        } catch (Exception e) {
            TextSendMain.stopServer();
            e.printStackTrace();
        }
    }
}
