package utils;

import java.io.UnsupportedEncodingException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES加密工具类
 * @author 伍子夜
 * 来源：https://www.jb51.net/article/111057.htm
 */
public class AES_Util {
	// 仅用于Debug，不加密
	private static boolean debug = false;
	// private static final String CipherMode =
	// "AES/ECB/PKCS5Padding";使用ECB加密，不需要设置IV，但是不安全
	private static final String CipherMode = "AES/CFB/NoPadding";// 使用CFB加密，需要设置IV

	// /** 加密(结果为16进制字符串) **/
	public static String encrypt(String password, String content) {
		if(!debug) {
			byte[] data = null;
			try {
				data = content.getBytes("UTF-8");
			} catch (Exception e) {
				e.printStackTrace();
			}
			data = encrypt(data, password);
			String result = byte2hex(data);
			return result;
		}else {
			return content;
		}
	}
	
	// /** 解密16进制的字符串为字符串 **/
	public static String decrypt(String password, String content) {
		if(!debug) {
			byte[] data = null;
			try {
				data = hex2byte(content);
			} catch (Exception e) {
				e.printStackTrace();
			}
			data = decrypt(data, password);
			if (data == null)
				return null;
			String result = null;
			try {
				result = new String(data, "UTF-8");
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			}
			return result;
		}else {
			return content;
		}
	}
	
	/**
	 * 生成加密后的密钥
	 *
	 * @param password 密钥种子
	 * @return isSucceed
	 */
	private static SecretKeySpec createKey(String password) {
		byte[] data = null;
		if (password == null) {
			password = "";
		}
		StringBuilder sb = new StringBuilder(32);
		sb.append(password);
		while (sb.length() < 32) {
			sb.append("0");
		}
		if (sb.length() > 32) {
			sb.setLength(32);
		}

		try {
			data = sb.toString().getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		return new SecretKeySpec(data, "AES");
	}

	// /** 加密字节数据 **/
	private static byte[] encrypt(byte[] content, String password) {
		try {
			SecretKeySpec key = createKey(password);
//			System.out.println(key);
			Cipher cipher = Cipher.getInstance(CipherMode);
			cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(new byte[cipher.getBlockSize()]));
			return cipher.doFinal(content);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	// /** 解密字节数组 **/
	private static byte[] decrypt(byte[] content, String password) {

		try {
			SecretKeySpec key = createKey(password);
			Cipher cipher = Cipher.getInstance(CipherMode);
			cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(new byte[cipher.getBlockSize()]));

			return cipher.doFinal(content);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	// /** 字节数组转成16进制字符串 **/
	private static String byte2hex(byte[] b) { // 一个字节的数，
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

	// /** 将hex字符串转换成字节数组 **/
	private static byte[] hex2byte(String inputString) {
		if (inputString == null || inputString.length() < 2) {
			return new byte[0];
		}
		inputString = inputString.toLowerCase();
		int l = inputString.length() / 2;
		byte[] result = new byte[l];
		for (int i = 0; i < l; ++i) {
			String tmp = inputString.substring(2 * i, 2 * i + 2);
			result[i] = (byte) (Integer.parseInt(tmp, 16) & 0xFF);
		}
		return result;
	}
}
