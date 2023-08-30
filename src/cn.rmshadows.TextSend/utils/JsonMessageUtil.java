package utils;
/*
 * JSON传输模式的类 可以将gson msg对象转为json
 */

import application.TextSendMain;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.LinkedList;

public class JsonMessageUtil {
    // 储存数据
    private LinkedList<String> encrypt_data = new LinkedList<String>();
    // ID
    private int id;
    // 留言
    private String notes = null;

    public LinkedList<String> getEncrypt_data() {
        return encrypt_data;
    }

    public void setEncrypt_data(LinkedList<String> encrypt_data) {
        this.encrypt_data = encrypt_data;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public JsonMessageUtil(String text, int length, int id, String notes) {
        // 创建一个JSONObject对象并设置键值对
        // Serialization
        Gson gson = new GsonBuilder().create();


        setId(id);
        if (notes != null) {
            setNotes(notes);
        } else {
            setNotes("undefined");
        }
        if (text != null) {
            // 需要截取的长度
            int r_len = length;
            int t_len = text.length();
            int start = 0;
            int end = 0;
            // 000 000 000 0 3 10 0,3 3,6 6,9
            while (t_len > r_len) {
                end += r_len;
                addData(encryptData(text.substring(start, end)));
                start += r_len;
                t_len -= r_len;
            }
            String e = text.substring(start);
            if (e != "") {
                addData(encryptData(e));
            }
        }
    }

    void addData(String s) {
        encrypt_data.add(s);
    }

    String encryptData(String msg) {
        return AES_Util.encrypt(TextSendMain.AES_TOKEN, msg);
    }

    public void printData() {
        for (String str : encrypt_data) {
            System.out.println(str);
        }
    }
}
