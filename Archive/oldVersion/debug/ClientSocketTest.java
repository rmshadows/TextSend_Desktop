package debug;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ClientSocketTest {
    public static void main(String[] args) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 54300), 5000);
            BufferedInputStream bis = new BufferedInputStream(socket.getInputStream());
            byte[] readBuf = new byte[4];
            int readLength = -1;
            StringBuilder chunk = new StringBuilder();
            while (true) {
                while ((readLength = bis.read(readBuf)) != -1) {
                    String rb = new String(readBuf, 0, readLength, StandardCharsets.UTF_8);
                    chunk.append(rb);
                    if (rb.endsWith("}")) {
                        System.out.println("Chunk: " + chunk);
                        chunk = new StringBuilder();
                        continue;
                    }
//                    System.out.println(rb);
                }
//                System.out.println(chunk);
                System.out.println("END");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
