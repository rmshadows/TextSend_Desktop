package server;
/**
 * 未使用。
 */
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public class DES_Util {
	// 秘钥算法
	private static final String KEY_ALGORITHM = "DES";
	// 加密算法：algorithm/mode/padding 算法/工作模式/填充模式
	private static final String CIPHER_ALGORITHM = "DES/ECB/PKCS5Padding";
	// 秘钥
	private static final String KEY = "RmY@Te=!";// DES秘钥长度必须是8位

	public static void main(String args[]) {
		String data = "加密解密====>>>>";
		System.out.println("==>>加密数据：" + data);
		byte[] encryptData = encrypt(data.getBytes());
		System.out.println("==>>加密后的数据：" + new String(encryptData));
		byte[] decryptData = decrypt(encryptData);
		System.out.println("==>>解密后的数据：" + new String(decryptData));
	}

	public static byte[] encrypt(byte[] data) {
		// 初始化秘钥
		SecretKey secretKey = new SecretKeySpec(KEY.getBytes(), KEY_ALGORITHM);

		try {
			Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
			cipher.init(Cipher.ENCRYPT_MODE, secretKey);
			byte[] result = cipher.doFinal(data);
			return Base64.getEncoder().encode(result);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	public static byte[] decrypt(byte[] data) {
		byte[] resultBase64 = Base64.getDecoder().decode(data);
		SecretKey secretKey = new SecretKeySpec(KEY.getBytes(), KEY_ALGORITHM);

		try {
			Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
			cipher.init(Cipher.DECRYPT_MODE, secretKey);
			byte[] result = cipher.doFinal(resultBase64);
			return result;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}
}