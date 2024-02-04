package utils;

import java.io.Serializable;
import java.util.LinkedList;

import static utils.MessageCrypto.tsEncryptString;

/**
 * 消息类(从3.1.3之后开始，Message仅作保留，不做传输对象了。传输请使用GsonMessage)
 * 加密的Msg类 解密请在App中实现，此类不包含解密的任何功能
 * @author ryan
 */
public class Message implements Serializable {
	private static final long serialVersionUID = 6697595348360693967L;
	// 储存数据
	private final LinkedList<String> encrypt_data = new LinkedList<>();
	// ID
	private String id;
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

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	/**
	 * 构造函数
	 * 
	 * @param text   字符串
	 * @param length 每次截取的长度
	 */
	public Message(String id, String text, int length, String notes) {
		// 去除null
		if (text == null) {
			text = "";
		}else {
			System.out.println("Message encapsulation：" + text);
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
			addData(tsEncryptString(text.substring(start, end)));
			start += length;
			t_len -= length;
		}
		String e = text.substring(start);
		if (!e.equals("")) {
			addData(tsEncryptString(e));
		}
	}

	public void printData() {
		for (String str : encrypt_data) {
			System.out.println(str);
		}
	}
}
