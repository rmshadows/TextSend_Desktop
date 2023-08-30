package utils;


import java.util.LinkedList;

/**
 * {id,  data, notes}
 */
public class GsonMessage {
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

    public void print() {
        System.out.println(data);
    }

}