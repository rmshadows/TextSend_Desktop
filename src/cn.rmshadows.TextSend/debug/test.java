package debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import utils.GsonMessage;
import utils.GsonMessageTypeAdapter;
import utils.Message;

import java.util.LinkedList;

public class test {
    static String x = "123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ss123ssEND";
    static String y = "START123ss12123ssEND";
    public static void main(String[] args) {
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(GsonMessage.class, new GsonMessageTypeAdapter())
                .create();
        Message message = new Message(y, 10, 12, null);
        String json = message.getJSON();
        System.out.println(json);
        
        GsonMessage gm = gson.fromJson(json, GsonMessage.class);
        gm.print();

    }
}
