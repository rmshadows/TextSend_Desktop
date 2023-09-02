package AES_Utils;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;

public class AES_Tools {
    public static final String CHARACTER = "UTF-8";

    /**
     * 密钥长度补全
     * 把所给的String密钥转为PWD_SIZE长度 的 Byte数组并填充 0
     * 注意：只允许英文字母和数字，禁止中文且禁止超出长度
     * @param key     String KeyA or KeyB
     * @param length: 长度
     * @return Byte[] 密钥的byte数组
     */
    public static byte[] padding(String key, int length) {
        byte[] result = null;
        if (key != null) {
            byte[] pwd_bytes = new byte[0]; //一个中文3位长度，一数字1位
            try {
                pwd_bytes = key.getBytes(CHARACTER);
                // 抛出溢出
                if (pwd_bytes.length > length){
                    throw new RuntimeException("字符串长度超出");
                }
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
            if (key.length() < length) {
                // 从pwd_bytes 索引0 开始复制
                System.arraycopy(pwd_bytes, 0, result = new byte[length], 0, pwd_bytes.length);
            } else {
                result = pwd_bytes;
            }
        }
//        System.out.println(Arrays.toString(result));
        return result;
    }

    /**
     * 十六进制字符串转Byte数组
     *
     * @param inputHexString 十六进制字符串
     * @return byte[] 字节数组
     */
    public static byte[] hex2bytes(String inputHexString) {
        if (inputHexString == null || inputHexString.length() < 2) {
            return new byte[0];
        }
        inputHexString = inputHexString.toLowerCase();
        int l = inputHexString.length() / 2;
        byte[] result = new byte[l];
        for (int i = 0; i < l; ++i) {
            String tmp = inputHexString.substring(2 * i, 2 * i + 2);
            result[i] = (byte) (Integer.parseInt(tmp, 16) & 0xFF);
        }
        return result;
    }

    /**
     * Byte数组转十六进制字符串
     *
     * @param b 字节数组
     * @return String hex
     */
    public static String bytes2hex(byte[] b) { // 一个字节的数，
        StringBuilder sb = new StringBuilder(b.length * 2);
        String tmp;
        for (byte aB : b) {
            // 整数转成十六进制表示
            tmp = (Integer.toHexString(aB & 0XFF));
            if (tmp.length() == 1) {
                sb.append("0");
            }
            sb.append(tmp);
        }
        return sb.toString().toUpperCase(); // 转成大写
    }
}
