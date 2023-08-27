package utils;

import java.util.LinkedList;

/**
 * {id,  data, notes}
 */
public class GsonMessage {
    private String id = "";
    private LinkedList<String> data = new LinkedList<>();
    private String notes = "";

    public GsonMessage(String id, LinkedList<String> data, String notes){
        if(notes == null){
            id = "";
        }
        if(data == null){
            data = new LinkedList<String>();
        }
        if(notes == null){
            notes = "";
        }
        this.id = id;
        this.data = data;
        this.notes = notes;
    }

    public void print(){
        System.out.println(data);
    }

}
