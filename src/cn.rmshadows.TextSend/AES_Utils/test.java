package AES_Utils;

import javax.crypto.spec.SecretKeySpec;
import javax.swing.*;
import java.io.UnsupportedEncodingException;

public class test {
    public static void test(){
        String todo = "妳好Hello@";
        String hex;

        System.out.printf("CFB加密前：%s\n", todo);
        AES_Utils.AES_CFB cfb = new AES_Utils.AES_CFB("119", "", 32);
        hex = cfb.encrypt(todo);
        System.out.printf("CFB加密：%s\n", hex);
//        System.out.printf("CFB解密：%s\n", cfb.decrypt(hex));

        /**
         * CFB模式 密码123456 填充；符号
         */
//        System.out.printf("CFB加密前：%s\n", todo);
//        AES_Utils.AES_CFB cfb = new AES_Utils.AES_CFB("123456", ";");
//        hex = cfb.encrypt(todo);
//        System.out.printf("CFB加密：%s\n", hex);
//        System.out.printf("CFB解密：%s\n", cfb.decrypt(hex));

//        /**
//         * 临时更换密码
//         */
//        hex = cfb.encrypt("12345", "54321", todo);
//        System.out.printf("CFB临时加密(PWD: 12345;IV:54321)：%s\n", hex);
//        System.out.printf("CFB临时解密(PWD: 12345;IV:54321)：%s\n", cfb.decrypt("12345", "54321", hex));
//        System.out.println();
//        /**
//         * CBC模式 密码123456 偏移量：4321 位数 16:128/32:256
//         */
//        AES_Utils.AES_CBC cbc = new AES_Utils.AES_CBC("123456", "4321", 32);
//        System.out.printf("CBC加密前：%s\n", todo);
//        hex = cbc.encrypt(todo);
//        System.out.printf("CBC加密：%s\n", hex);
//        System.out.printf("CBC解密：%s\n", cbc.decrypt(hex));
//        /**
//         * 临时更换密码
//         */
//        hex = cbc.encrypt("12345", "54321", todo);
//        System.out.printf("CBC临时加密(PWD: 12345, IV: 54321)：%s\n", hex);
//        System.out.printf("CBC临时解密(PWD: 12345, IV: 54321)：%s\n", cbc.decrypt("12345", "54321", hex));
    }

    public static void main(String[] args){
        test();
    }
}
