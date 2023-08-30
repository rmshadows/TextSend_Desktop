package utils;

import java.io.Serializable;
import java.util.LinkedList;

import application.TextSendMain;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * 消息类
 * 加密的Msg类 解密请在App中实现，此类不包含解密的任何功能
 * @author ryan
 */
public class Message implements Serializable {
	private static final long serialVersionUID = 6697595348360693967L;
	// 储存数据
	private final LinkedList<String> encrypt_data = new LinkedList<>();
	// ID
	private int id;
	// 留言
	private String notes = null;

	public String getNotes() {
		return notes;
	}

	public void setNotes(String notes) {
		this.notes = notes;
	}

	public LinkedList<String> getData() {
		return encrypt_data;
	}

	void addData(String s) {
		encrypt_data.add(s);
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	/**
	 * 构造函数
	 * 
	 * @param text   字符串
	 * @param length 每次截取的长度
	 */
	public Message(String text, int length, int id, String notes) {
		if (text == null) {
			text = "";
		}else {
			System.out.println("封装字符串：" + text);
		}
		setId(id);
		if (notes != null) {
			setNotes(notes);
		} else {
			setNotes("");
		}
		// 需要截取的长度
		int t_len = text.length();
		int start = 0;
		int end = 0;
		// 000 000 000 0 3 10 0,3 3,6 6,9
		while (t_len > length) {
			end += length;
			addData(encryptData(text.substring(start, end)));
			start += length;
			t_len -= length;
		}
		String e = text.substring(start);
		if (!e.equals("")) {
			addData(encryptData(e));
		}
	}

	String encryptData(String msg) {
		return AES_Util.encrypt(TextSendMain.AES_TOKEN, msg);
	}

	public void printData() {
		for (String str : encrypt_data) {
			System.out.println(str);
		}
	}

	public String getJSON(){
		Gson gson = new GsonBuilder()
				.registerTypeAdapter(GsonMessage.class, new GsonMessageTypeAdapter())
				.create();
		return gson.toJson(new GsonMessage(String.valueOf(getId()), getData(), getNotes()));
	}

}
