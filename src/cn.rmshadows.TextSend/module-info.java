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
	requires com.google.gson;
	// 否则GSON报错: Failed making field 'xxjava.xxxx' accessible; either increase its visibility or write a custom TypeAdapter for its declaring type.
	// https://stackoverflow.com/questions/72769462/failed-making-field-property-accessible-either-change-its-visibility-or-write
	opens utils to com.google.gson;
}
