package utils;

import java.io.Serializable;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.LinkedList;

/**
 * {id,  data, notes}
 * 不允许直接使用！
 * 使用流程：
 * 加密：
 * Message->GsonMessage(除Data外明文)
 * GsonMessage->GsonMessage（加密所有参数到密文JSON）
 * 解密L：
 * GsonMessage->GsonMessage（解密所有参数到明文，包括Data）
 */
public class GsonMessage implements Serializable {
    private static final long serialVersionUID = 6697595348360693976L;
    private final String id;
    private final LinkedList<String> data;
    private final String notes;

    public String getId() {
        return id;
    }

    public LinkedList<String> getData() {
        return data;
    }

    public String getNotes() {
        return notes;
    }

    public GsonMessage(String id, LinkedList<String> data, String notes) {
        if (notes == null) {
            id = "";
        }
        if (data == null) {
            data = new LinkedList<>();
        }
        if (notes == null) {
            notes = "";
        }
        this.id = id;
        this.data = data;
        this.notes = notes;
    }

    /**
     * 重写的方法
     */
    @Override
    public String toString(){
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(GsonMessage.class, new GsonMessageTypeAdapter())
                .create();
        return gson.toJson(this);
    }

}