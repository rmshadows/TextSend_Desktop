package debug;

import application.TextSendMain;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import utils.*;

public class test {
    public static String x = "123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ssEND";
    static String y = "START123ss12123ssEND";

    public static void main(String[] args) {
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(GsonMessage.class, new GsonMessageTypeAdapter())
                .create();
        Message message = new Message("12", y, 10, null);
        // TODO
//        String json = message;
//        System.out.println(json);

//        String ss = "{\"id\":\"03087DC3A12C7203406BCED4DAF6\",\"data\":[],\"notes\":\"030B7ECA7686EAD7EDF4CA017B6C4559DEEFACA39A\"}";
//        GsonMessage gm = gson.fromJson(ss, GsonMessage.class);
//        gm = MessageCrypto.gsonMessageDecrypt(gm);
//        System.out.println(gm);
//        System.out.println(MessageCrypto.tsDecryptString("03087DC3A12C7203406BCED4DAF6"));

        String x = MessageCrypto.tsEncryptString("666");
        System.out.println("x = " + x);

    }
}
