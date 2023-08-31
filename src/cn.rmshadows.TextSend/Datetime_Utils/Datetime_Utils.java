package Datetime_Utils;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedList;

/**
 * 时间日期工具类
 *
 * @author ryan
 */
public class Datetime_Utils {
	// 系统默认UTC时钟（当前瞬时时间 System.currentTimeMillis()） 
	protected static Clock utcClock = Clock.systemUTC();
	// //系统默认时区时钟（当前瞬时时间）
	Clock zoneClock = Clock.systemDefaultZone();
	
	// 时间
	/**
	 * 获取当前时间毫秒时间戳 ，此方法不受时区影响
	 * @param isMillis 是否是毫秒级别
	 * @return 1675374966 417
	 */
	public static long getTimeStampNow(boolean isMillis) {
//		System.out.println(utcClock.millis());
//		System.out.println(System.currentTimeMillis());
		if (isMillis){
			return utcClock.millis();
		}else {
			return utcClock.instant().getEpochSecond();
		}
	}

	/**
	 * 返回当前时间，仅时间
	 * @param zoneClock 时区时钟 null默认当前
	 * @return 04:42:06.249760
	 */
	public static LocalTime getTimeNow(Clock zoneClock){
		if (zoneClock == null){
			return LocalTime.now();
		}else {
			return LocalTime.now(zoneClock);
		}
	}

	// 日期
	/**
	 * 返回今天日期（无时间）
	 * @param zoneClock 时区时钟 null则默认当前时区
	 * @return 2023-02-17
	 */
	public static LocalDate getDateNow(Clock zoneClock){
		if (zoneClock == null){
			return LocalDate.now();
		}else {
			return LocalDate.now(zoneClock);
		}
	}

	/**
	 * 返回d1， d2时间差数据
	 * @param d1
	 * @param d2
	 * @return 列表：0:年 1:月 2:日 3:字符串时间差
	 */
	public static LinkedList<String> periodLocalDate(LocalDate d1, LocalDate d2){
		LinkedList<String> ll = new LinkedList<>();
		Period p = Period.between(d1, d2);
		ll.add(String.valueOf(p.getYears()));
		ll.add(String.valueOf(p.getMonths()));
		ll.add(String.valueOf(p.getDays()));
		// 时间差字符串
		String s = "目标日期 "+ d1 +" 距离 "+ d2 +" 的时间差："+p.getYears()+" 年 "+p.getMonths()+" 月 "+p.getDays()+" 天";
		ll.add(s);
		return ll;
	}

	/**
	 * 获取某个月的第一天和最后一天
	 * @param localDate 某一天(本方法只在乎月份)
	 * @return 第一天和最后一天
	 */
	public static LinkedList<LocalDate> getFirstAndLastDayofMonth(LocalDate localDate){
		LinkedList<LocalDate> ll = new LinkedList<>();
		if (localDate == null){
			localDate = getDateNow(null);
		}
//		LocalDate firstDay = localDate.withDayOfMonth(1);
		LocalDate firstDay = localDate.with(TemporalAdjusters.firstDayOfMonth());
		LocalDate lastDay = localDate.with(TemporalAdjusters.lastDayOfMonth());
		ll.add(firstDay);
		ll.add(lastDay);
		return ll;
	}

	/**
	 * 获取某个月的第一个周几和最后一个周几
	 * @param localDate 某一天(本方法只在乎月份)
	 * @return 某个月的第一个周几和最后一个周几
	 */
	public static LinkedList<LocalDate> getFirstInAndLastInMonth(LocalDate localDate, int dayofWeek){
		LinkedList<LocalDate> ll = new LinkedList<>();
		if (localDate == null){
			localDate = getDateNow(null);
		}
		LocalDate firstin = localDate.with(TemporalAdjusters.firstInMonth(DayOfWeek.of(dayofWeek)));
		LocalDate lastin = localDate.with(TemporalAdjusters.lastInMonth(DayOfWeek.of(dayofWeek)));
		ll.add(firstin);
		ll.add(lastin);
		return ll;
	}

	// 日期时间
	/**
	 * 返回当前日期时间
	 * @param zoneClock 时区时钟，null为默认
	 * @return 2023-02-17T05:36:53.899425
	 */
	public static LocalDateTime getDateTimeNow(Clock zoneClock){
		if (zoneClock == null){
			return LocalDateTime.now();
		}else {
			return LocalDateTime.now(zoneClock);
		}
	}

	/**
	 * 获取某天的零点和最大时刻
	 * @param localDate 某一天
	 * @return 零点或最大 2023-02-17T00:00 and 2023-02-17T23:59:59.999999999
	 */
	public static LinkedList<LocalDateTime> getDayMaxAndMin(LocalDate localDate){
		LinkedList<LocalDateTime> ll = new LinkedList<>();
		if (localDate == null){
			localDate = getDateNow(null);
		}
		ll.add(LocalDateTime.of(localDate, LocalTime.MIN));
		ll.add(LocalDateTime.of(localDate, LocalTime.MAX));
		return ll;
	}

	/**
	 * 返回d1， d2时间差数据
	 * @param d1
	 * @param d2
	 * @return 列表：1.long数组 天、时、分、秒、毫秒、微秒 2.字符串时间差
	 */
	public static LinkedList<Object> durationLocalDateTime(LocalDateTime d1, LocalDateTime d2){
		LinkedList<Object> ll = new LinkedList<>();
		Duration d = Duration.between(d1, d2);
		// 天、时、分、秒、毫秒、微秒
		ll.add(new long[]{d.toDays(), d.toHours(), d.toMinutes(), d.toSeconds(), d.toMillis(), d.toNanos()});
		// 时间差字符串
		String s = d1 +" 和 "+ d2 +" 的时间差： 天数："+d.toDays()+
				" 小时数："+ d.toHours()+
				" 分钟数："+d.toMinutes()+
				" 秒数："+d.toSeconds()+
				" 毫秒数："+d.toMillis()+
				" 微秒数："+d.toNanos();
		ll.add(s);
		System.out.println(s);
		return ll;
	}

	// 时区日期 ZoneDateTime
	/**
	 * 获取时区时间 ZoneDateTime 2023-03-13T04:23:39.688183+08:00[Asia/Shanghai]
	 * @param szoneId String 时区
	 * @return 2023-03-13T04:23:39.688183+08:00[Asia/Shanghai]
	 */
	public static ZonedDateTime getZoneDateTimeNow(String szoneId){
		if (szoneId == null || szoneId.equals("")){
			return ZonedDateTime.now();
		}else {
			return ZonedDateTime.now(ZoneId.of(szoneId));
		}
	}

	/**
	 * 不同时区的时间转换
	 * WARN:注意这两次转换后的纽约时间有1小时的夏令时时差。涉及到时区时，千万不要自己计算时差，否则难以正确处理夏令时。
	 * @param zonedDateTime 时区时间
	 * @param szoneId 要转换成的某个时区
	 * @return
	 */
	public static ZonedDateTime zonedDateTimeConversion(ZonedDateTime zonedDateTime, String szoneId){
		return zonedDateTime.withZoneSameInstant(ZoneId.of(szoneId));
	}


	// 格式 与 转换
	// ISO 8601规定的日期和时间分隔符是T。标准格式如下：
	// yyyy	4位数的年份
	//yy	显示2位数的年份，比如2022年，则显示为22年
	//MM	显示2位数的月份，不满2位数的，前面补0，比如7月份显示07月
	//M	月份，不满2位的月份不会补0
	//dd	天， 如果1位数的天数，则补0
	//d	天，不满2位数字的，不补0
	//HH	24小时制的时间显示，小时数，两位数，不满2位数字的前面补0
	//H	24小时制的时间显示，小时数，不满2位数字的不补0
	//hh	12小时制的时间显示，小时数，两位数，不满2位数字的前面补0
	//ss	秒数，不满2位的前面补0
	//s	秒数，不满2位的不补0
	//SSS	毫秒数
	//z	时区名称，比如北京时间东八区，则显示CST
	//Z	时区偏移信息，比如北京时间东八区，则显示+0800

	/**
	 * 获取格式
	 * 使用：LocalDate.parse(Text, formatter);
	 * @param format 如"yyyy-MM-dd"
	 * @return DateTimeFormatter
	 */
	public static DateTimeFormatter getFormatter(String format){
		if (format == null){
			return DateTimeFormatter.ofPattern("yyyy-MM-dd");
		}else {
			return DateTimeFormatter.ofPattern(format);
		}
	}

	/**
	 * localDate转localDateTime
	 * @param localDate
	 * @return
	 */
	public static LocalDateTime localDate2LocalDateTime(LocalDate localDate){
		return LocalDateTime.of(localDate.getYear(), localDate.getMonthValue(), localDate.getDayOfMonth(), 0, 0, 0);
	}

	/**
	 * localDateTime转为ZonedDateTime
	 * https://blog.csdn.net/arjun_yu/article/details/118091874
	 * @param localDateTime
	 * @param szoneId 时区名称
	 * @return
	 */
	public static ZonedDateTime LocalDateTime2zonedDateTime(LocalDateTime localDateTime, String szoneId){
		return localDateTime.atZone(ZoneId.of(szoneId));
	}

	/**
	 * 瞬时时间Instant转为上海LocalDateTime
	 * @param instant
	 * @return
	 */
	public static LocalDateTime instant2ShanghaiLocalDateTime(Instant instant){
		return LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Shanghai"));
	}

	/**
	 * 时间戳转为LocalDateTime
	 * @param timestamp 时间戳 毫秒或者秒
	 * @param szoneId 时区
	 * @param isEpochMilli 是否是毫秒级时间戳（否则当作秒级处理）
	 * @return
	 */
	public static LocalDateTime timestamp2LocalDateTime(long timestamp, String szoneId, boolean isEpochMilli){
		if (isEpochMilli){
			return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.of(szoneId));
		}else{
			return LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.of(szoneId));
		}
	}

	/**
	 * localDateTime转时间戳
	 * @param localDateTime
	 * @param zoneOffset null默认UTC
	 * @param isEpochMilli 是否是毫秒级别，否则返回秒
	 * @return
	 */
		public static long localDateTime2Timestamp(LocalDateTime localDateTime, ZoneOffset zoneOffset, boolean isEpochMilli){
		if (isEpochMilli){
			return localDateTime2Instant(localDateTime, zoneOffset).toEpochMilli();
		}else{
			return localDateTime2Instant(localDateTime, zoneOffset).getEpochSecond();
		}
	}

	/**
	 * LocalDateTime转Instant
	 * @param localDateTime
	 * @param zoneOffset null默认UTC
	 * @return
	 */
	public static Instant localDateTime2Instant(LocalDateTime localDateTime, ZoneOffset zoneOffset){
		if (zoneOffset == null){
			return localDateTime.toInstant(ZoneOffset.UTC);
		}else {
			return localDateTime.toInstant(zoneOffset);
		}
	}

	// 时区
	/**
	 * 返回某时区的时钟
	 * 如：巴黎时区："Europe/Paris"
	 * @param timezone
	 * @return
	 */
	public static Clock getZoneClock(String timezone) {
		return Clock.system(ZoneId.of(timezone));
	}

	/**
	 * 获取系统默认时区
	 * @return 时区
	 */
	public static ZoneId getSystemTimezone() {
		return ZoneId.systemDefault();
	}
}