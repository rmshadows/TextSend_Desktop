package IpAddress;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IpAddressFilter {

    public static boolean isValidIPv4(String ipv4Address) {
        String ipv4Pattern = "^(25[0-5]|2[0-4][0-9]|[0-1]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[0-1]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[0-1]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[0-1]?[0-9][0-9]?)$";
        return ipv4Address.matches(ipv4Pattern);
    }

    public static boolean isValidIPv6(String ipv6Address) {
        String ipv6Pattern = "^\\[?([0-9a-fA-F]{0,4}:){1,7}([0-9a-fA-F]{0,4})?\\]?$";
        return ipv6Address.matches(ipv6Pattern);
    }

    public static boolean isValidIPv4AddressWithPort(String ipWithPort) {
        String ipWithPortPattern = "^(\\d{1,3}(?:\\.\\d{1,3}){3}):([0-9]{1,5})$";
        return ipWithPort.matches(ipWithPortPattern);
    }

    public static boolean isValidIPv6AddressWithPort(String ipWithPort) {
        String ipWithPortPattern = "^(?:\\[(.+?)\\])?(?::(\\d{1,5}))?$";
        return ipWithPort.matches(ipWithPortPattern);
    }

    // "IPv4":1/"IPv6":2/"IPv4 with Port":3/"IPv6 with Port":4/"Invalid IP":5
    public static int getIpType(String ip) {
        if (isValidIPv4(ip)) {
            return 1;
        } else if (isValidIPv6(ip)) {
            return 2;
        } else if (isValidIPv4AddressWithPort(ip)) {
            return 3;
        } else if (isValidIPv6AddressWithPort(ip)) {
            return 4;
        } else {
            return 5;
        }
    }

    public static Pair<String, String> splitIpAndPort(String ipAddressWithPort) {
        // 判断是 IPv4 还是 IPv6
        if (getIpType(ipAddressWithPort) == 4) {
            // IPv6 地址
            String[] parts = ipAddressWithPort.split("]:");
            return new Pair<>(parts[0].substring(1), parts[1]);
        } else if (getIpType(ipAddressWithPort) ==3 ) {
            // IPv4 地址
            String[] parts = ipAddressWithPort.split(":");
            return new Pair<>(parts[0], parts[1]);
        } else {
            return null;
        }
    }
}
