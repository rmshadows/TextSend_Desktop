package utils;
/*
 * JSON传输模式的类 可以将gson msg对象转为json
 */

import application.TextSendMain;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;

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


    /**
     * GsonMessage转字节
     * @param gm GsonMessage
     * @return byte[]
     */
    public static byte[] gsonMessage2bytes(GsonMessage gm) {
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
             ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream)) {
            objectOutputStream.writeObject(gm);
            objectOutputStream.flush();
            objectOutputStream.close();
            byte[] data = byteArrayOutputStream.toByteArray();
            data = mergeArrays(data, TextSendMain.endMarker);
            return data;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 合并数组
     * @param array1 数组1
     * @param array2 数组2
     * @return 数组1+数组2
     */
    public static byte[] mergeArrays(byte[] array1, byte[] array2) {
        byte[] mergedArray = new byte[array1.length + array2.length];
        System.arraycopy(array1, 0, mergedArray, 0, array1.length);
        System.arraycopy(array2, 0, mergedArray, array1.length, array2.length);
        return mergedArray;
    }

    /**
     * 去除末尾的结束符号
     * @param c 字节数组
     * @param b 结束符号字节数组
     * @return 去除末尾的结束符号de字节数组
     */
    public static byte[] removeArray(byte[] c, byte[] b) {
        int index = indexOfSubArray(c, b);
        if (index != -1) {
            byte[] result = new byte[c.length - b.length];
            System.arraycopy(c, 0, result, 0, index);
            System.arraycopy(c, index + b.length, result, index, c.length - index - b.length);
            return result;
        } else {
            return c;
        }
    }

    /**
     * 查找子数组在数组中的起始索引
     * @param array 数组
     * @param subArray 子数组
     * @return 索引
     */
    public static int indexOfSubArray(byte[] array, byte[] subArray) {
        for (int i = 0; i <= array.length - subArray.length; i++) {
            boolean found = true;
            for (int j = 0; j < subArray.length; j++) {
                if (array[i + j] != subArray[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 去掉字节数组末尾的0
     * @param array 字节数组
     * @return 去尾0字节数组
     */
    public static byte[] removeTrailingZeros(byte[] array) {
        int lastIndex = array.length - 1;
        while (lastIndex >= 0 && array[lastIndex] == 0) {
            lastIndex--;
        }
        return Arrays.copyOf(array, lastIndex + 1);
    }


    /**
     * 字节转GM
     * @param bytes 字节数组
     * @return GsonMessage
     */
    public static GsonMessage bytes2GsonMessage(byte[] bytes) {
        // 去零
        bytes = removeTrailingZeros(bytes);
        // 去头
        bytes = removeArray(bytes, TextSendMain.endMarker);
        try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
             ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream)) {
            return (GsonMessage) objectInputStream.readObject();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 判断字节数组是否以指定的字节数组结尾
     * @param data 字节数组
     * @param endMarker 指定的字节数组
     * @return 是否
     */
    public static boolean bendsWith(byte[] data, byte[] endMarker) {
        data = removeTrailingZeros(data);
        if (data.length < endMarker.length) {
            return false;
        }
        for (int i = 0; i < endMarker.length; i++) {
            if (data[data.length - endMarker.length + i] != endMarker[i]) {
                return false;
            }
        }
        return true;
    }
}
