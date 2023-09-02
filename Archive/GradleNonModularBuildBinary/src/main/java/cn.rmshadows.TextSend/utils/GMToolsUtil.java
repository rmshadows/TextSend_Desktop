package utils;
/*
 * JSON传输模式的类 可以将gson msg对象转为json
 */

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class GMToolsUtil {
    /**
     * 返回Gson对象 注意 没有加密！（除了Message自己加密的data）
     *
     * @param m Message
     */
    public static GsonMessage MessageToGsonMessage(Message m) {
        return new GsonMessage(String.valueOf(m.getId()), m.getData(), m.getNotes());
    }

    /**
     * 转成加密的Gson对象，可以直接用于发送
     * @param m Message
     * @return GsonMessage
     */
    public static GsonMessage MessageToEncrypptedGsonMessage(Message m) {
        return MessageCrypto.gsonMessageEncrypt(new GsonMessage(String.valueOf(m.getId()), m.getData(), m.getNotes()));
    }

    /**
     * JSON转GsonMessage对象 (未解密)
     * @param json json
     */
    public static GsonMessage JSONtoGsonMessage(String json){
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(GsonMessage.class, new GsonMessageTypeAdapter())
                .create();
        return gson.fromJson(json, GsonMessage.class);
    }

}
