package AES_Utils;

import java.io.UnsupportedEncodingException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES CFB加密工具类
 * @author 伍子夜 & Ryan
 * 参考来源：https://www.jb51.net/article/111057.htm
 */
public class AES_CFB {
    // 仅用于Debug，不加密
    final private static boolean debug_mode = false;
    private static final String CipherMode = "AES/CFB/NoPadding";// 与Python默认配置兼容
    //    private static final String CipherMode = "AES/CFB/PKCS5Padding";// 使用CFB加密，需要设置IV
    // 偏移量
    private byte[] cfb_iv;
    // 密钥长度
    private int key_length = 32;
    // 密码
    private byte[] cfb_key;
    private static final String CHARACTER = "UTF-8";

    public int getKey_length() {
        return key_length;
    }

    public void setKey_length(int key_length) {
        this.key_length = key_length;
    }

    public String getKey() {
        try {
            return new String(cfb_key, CHARACTER);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public void setKey(String key) {
        cfb_key = AES_Tools.padding(key, key_length);
    }

    public String getCfb_iv() {
        try {
            return new String(cfb_iv, CHARACTER);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public void setCfb_iv(String cfb_iv) {
        this.cfb_iv = AES_Tools.padding(cfb_iv, 16);
    }

    public AES_CFB(String passwd, String iv, int pwd_len) {
        key_length = pwd_len;
        cfb_key = AES_Tools.padding(passwd, key_length);
        cfb_iv = AES_Tools.padding(iv, 16);
    }

    public AES_CFB(String passwd, String iv) {
        cfb_key = AES_Tools.padding(passwd, key_length);
        cfb_iv = AES_Tools.padding(iv, 16);
    }

    public AES_CFB(String passwd){
        cfb_key = AES_Tools.padding(passwd, key_length);
        cfb_iv = AES_Tools.padding("", 16);
    }

    /**
     * 加密
     * @param password 密码
     * @param iv 偏移量
     * @param clear_content 明文
     * @return HexString 16进制字符串
     */
    public String encrypt(String password, String iv, String clear_content) {
        if(!debug_mode) {
            byte[] data = null;
            try {
                data = clear_content.getBytes(CHARACTER);
            } catch (Exception e) {
                e.printStackTrace();
            }
            data = bytesEncrypt(data, AES_Tools.padding(password, key_length), AES_Tools.padding(iv,16));
            return AES_Tools.bytes2hex(data);
        }else {
            return clear_content;
        }
    }

    /**
     * 默认密码加密
     * @param clear_content String 明文
     * @return HexString 密文
     */
    public String encrypt(String clear_content) {
        if(!debug_mode) {
            byte[] data = null;
            try {
                data = clear_content.getBytes(CHARACTER);
            } catch (Exception e) {
                e.printStackTrace();
            }
            data = bytesEncrypt(data, cfb_key, cfb_iv);
            return AES_Tools.bytes2hex(data);
        }else {
            return clear_content;
        }
    }

    /**
     * 解密16进制的字符串为字符串
     * @param password 密码
     * @param cfb_iv 偏移量
     * @param hex_content 密文
     * @return 字符串
     */
    public String decrypt(String password, String cfb_iv, String hex_content) {
        if(!debug_mode) {
            byte[] data = null;
            try {
                data = AES_Tools.hex2bytes(hex_content);
            } catch (Exception e) {
                e.printStackTrace();
            }
            data = bytesDecrypt(data, AES_Tools.padding(password, key_length), AES_Tools.padding(cfb_iv, 16));
            if (data == null)
                return null;
            String result = null;
            try {
                result = new String(data, CHARACTER);
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            return result;
        }else {
            return hex_content;
        }
    }

    /**
     * 默认密码解密
     * @param hex_content
     * @return
     */
    public String decrypt(String hex_content) {
        if(!debug_mode) {
            byte[] data = null;
            try {
                data = AES_Tools.hex2bytes(hex_content);
            } catch (Exception e) {
                e.printStackTrace();
            }
            data = bytesDecrypt(data, cfb_key, cfb_iv);
            if (data == null)
                return null;
            String result = null;
            try {
                result = new String(data, CHARACTER);
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            return result;
        }else {
            return hex_content;
        }
    }

    /**
     * 加密字节数据(base)
     * @param content
     * @param bkey
     * @param iv
     * @return
     */
    private static byte[] bytesEncrypt(byte[] content, byte[] bkey, byte[] iv) {
        try {
            // 生成加密后的密钥
            SecretKeySpec key = new SecretKeySpec(bkey, "AES");
            Cipher cipher = Cipher.getInstance(CipherMode);
            IvParameterSpec ivParameterSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, ivParameterSpec);
            return cipher.doFinal(content);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 解密字节数组(Base)
     * @param content
     * @param bkey
     * @param iv
     * @return
     */
    private static byte[] bytesDecrypt(byte[] content, byte[] bkey, byte[] iv) {
        try {
            SecretKeySpec key = new SecretKeySpec(bkey, "AES");
            Cipher cipher = Cipher.getInstance(CipherMode);
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));

            return cipher.doFinal(content);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
