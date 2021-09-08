/**
 * TextSend
 * Linux-Android双向传输文本
 * @author jessie
 *
 */
module cn.rmshadows.TextSend {
	exports application;

	requires java.datatransfer;
	requires java.desktop;
	requires com.google.zxing;
	requires com.google.zxing.javase;
}
