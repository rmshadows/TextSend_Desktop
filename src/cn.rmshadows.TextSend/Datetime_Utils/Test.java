package Datetime_Utils;

import java.time.*;
import java.time.format.DateTimeFormatter;

public class Test {
	public static void main(String[] args) {
		// https://blog.csdn.net/qq_25073223/article/details/125955626
		System.out.printf("当前日期时间：%s \n", Datetime_Utils.getDateTimeNow(null));
		System.out.printf("当前日期：%s \n", Datetime_Utils.getDateNow(null));
		System.out.printf("当前时间：%s \n", Datetime_Utils.getTimeNow(null));
		System.out.printf("当前时区时间：%s \n", Datetime_Utils.getZoneDateTimeNow(null));
		System.out.printf("当前时间戳（毫秒）：%s \n", Datetime_Utils.getTimeStampNow(true));
		System.out.printf("当前时间戳（秒级）：%s \n", Datetime_Utils.getTimeStampNow(false));
	}
}