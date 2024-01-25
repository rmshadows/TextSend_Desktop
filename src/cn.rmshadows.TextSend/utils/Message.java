package utils;

import java.io.Serializable;
import java.util.LinkedList;

import static utils.MessageCrypto.tsEncryptString;

/**
 * 消息类(从3.1.3之后开始，Message仅作保留，不做传输对象了。传输请使用GsonMessage)
 * 加密的Msg类 解密请在App中实package utils;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.LinkedList;

// https://www.javadoc.io/static/com.google.code.gson/gson/2.10.1/com.google.gson/com/google/gson/TypeAdapter.html
// https://www.kancloud.cn/apachecn/howtodoinjava-zh/1953303
public class GsonMessageTypeAdapter extends TypeAdapter<GsonMessage> {
    private void writeString(JsonWriter writer, String key, String value) throws IOException {
        writer.name(key).value(value);
    }

    private void writeLinkedList(JsonWriter writer, String key, LinkedList<String> list) throws IOException {
        writer.name(key).beginArray();
        for (String s : list) {
            writer.value(s);
        }
        writer.endArray();
    }

    @Override
    public void write(JsonWriter jsonWriter, GsonMessage gsonMessage) throws IOException {
        if (gsonMessage == null) {
            jsonWriter.nullValue();
            return;
        }
        jsonWriter.beginObject();
        // ID
        writeString(jsonWriter, "id", gsonMessage.getId());
        // DATA
        writeLinkedList(jsonWriter, "data", gsonMessage.getData());
        // NOTES
        writeString(jsonWriter, "notes", gsonMessage.getNotes());
        jsonWriter.endObject();
        jsonWriter.close();
    }

    private LinkedList<String> readAsLinkedList(JsonReader reader) throws IOException {
        LinkedList<String> strings = new LinkedList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            strings.add(reader.nextString());
        }
        reader.endArray();
        return strings;
    }

    @Override
    public GsonMessage read(JsonReader jsonReader) throws IOException {
        String id = "";
        LinkedList<String> data = new LinkedList<>();
        String notes = "";

        if (jsonReader.peek() == JsonToken.NULL) {
            jsonReader.nextNull();
            return null;
        }
        jsonReader.beginObject();
        while (jsonReader.hasNext()) {
            String name = jsonReader.nextName();
            if (name.equals("id")) {
                id = jsonReader.nextString();
            } else if (name.equals("notes")) {
                notes = jsonReader.nextString();
            } else if (name.equals("data") && jsonReader.peek() == JsonToken.BEGIN_ARRAY) {
                data = readAsLinkedList(jsonReader);
            }else {
                jsonReader.skipValue();
            }
        }
        jsonReader.endObject();
        return new GsonMessage(id, data, notes);
    }
}现，此类不包含解密的任何功能
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
