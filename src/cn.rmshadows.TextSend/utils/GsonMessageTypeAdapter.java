package utils;

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
}