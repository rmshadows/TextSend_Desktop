package application;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 本机根证书和给网页桥 C 档用的服务器证书。
 * 根证书可选。装进系统信任后浏览器不再提示不受信任；不装时由浏览器自己提示。
 */
public final class LanCert {
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] STORE_PASS = "textsend-lan".toCharArray();
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private LanCert() {
    }

    public static final class Material {
        public final SSLContext context;
        public final Path caCert;
        public final String fingerprint;

        Material(SSLContext context, Path caCert, String fingerprint) {
            this.context = context;
            this.caCert = caCert;
            this.fingerprint = fingerprint;
        }
    }

    /** 保证根证书已写好，并给出当前网卡地址对应的服务器证书。 */
    public static Material material() throws Exception {
        Path dir = UserConfig.configDirectory().resolve("textsend-ca");
        Files.createDirectories(dir);
        Path caKeyFile = dir.resolve("ca.key");
        Path caCertFile = dir.resolve("ca.crt");
        Path p12 = dir.resolve("server.p12");
        Path sansFile = dir.resolve("server.sans");
        KeyPair ca = loadOrCreateCa(caKeyFile, caCertFile);
        X509Certificate caCert = readCert(caCertFile);
        String sans = String.join(",", hostNames());
        X509Certificate leaf = null;
        PrivateKey leafKey = null;
        boolean reuse = Files.isRegularFile(p12) && Files.isRegularFile(sansFile)
                && sans.equals(Files.readString(sansFile).trim());
        if (reuse) {
            try {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                try (var in = Files.newInputStream(p12)) {
                    ks.load(in, STORE_PASS);
                }
                leafKey = (PrivateKey) ks.getKey("textsend", STORE_PASS);
                leaf = (X509Certificate) ks.getCertificate("textsend");
                Instant renewAt = Instant.now().plusSeconds(7L * 24 * 3600);
                reuse = leafKey != null && leaf != null && leaf.getNotAfter().toInstant().isAfter(renewAt);
            } catch (Exception ignored) {
                reuse = false;
            }
        }
        if (!reuse) {
            KeyPair server = rsa();
            leafKey = server.getPrivate();
            leaf = sign(ca.getPrivate(), caCert, server.getPublic(), "TextSend", false, hostNames());
            leaf.verify(ca.getPublic());
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, STORE_PASS);
            ks.setKeyEntry("textsend", leafKey, STORE_PASS, new Certificate[]{leaf, caCert});
            try (var out = Files.newOutputStream(p12)) {
                ks.store(out, STORE_PASS);
            }
            Files.writeString(sansFile, sans + "\n");
            tighten(p12);
        }
        caCert.verify(ca.getPublic());
        KeyStore live = KeyStore.getInstance("PKCS12");
        live.load(null, STORE_PASS);
        live.setKeyEntry("textsend", leafKey, STORE_PASS, new Certificate[]{leaf, caCert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(live, STORE_PASS);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, RNG);
        return new Material(ctx, caCertFile, fingerprint(caCert));
    }

    public static Path caCertificate() throws Exception {
        return material().caCert;
    }

    private static KeyPair loadOrCreateCa(Path keyFile, Path certFile) throws Exception {
        if (Files.isRegularFile(keyFile) && Files.isRegularFile(certFile)) {
            byte[] pkcs8 = pemBody(Files.readString(keyFile));
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            X509Certificate cert = readCert(certFile);
            cert.verify(cert.getPublicKey());
            return new KeyPair(cert.getPublicKey(), key);
        }
        KeyPair ca = rsa();
        X509Certificate cert = sign(ca.getPrivate(), null, ca.getPublic(), "TextSend LAN CA", true, List.of());
        cert.verify(ca.getPublic());
        Files.writeString(keyFile, pem("PRIVATE KEY", ca.getPrivate().getEncoded()));
        Files.writeString(certFile, pem("CERTIFICATE", cert.getEncoded()));
        tighten(keyFile);
        return ca;
    }

    private static X509Certificate readCert(Path file) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        try (var in = Files.newInputStream(file)) {
            return (X509Certificate) cf.generateCertificate(in);
        }
    }

    private static X509Certificate sign(PrivateKey issuerKey, X509Certificate issuerCert,
                                        PublicKey subjectKey, String cn, boolean ca,
                                        List<String> hosts) throws Exception {
        byte[] serial = new byte[16];
        RNG.nextBytes(serial);
        serial[0] = (byte) (serial[0] & 0x7f);
        Instant now = Instant.now().minusSeconds(300);
        Instant until = now.plusSeconds(ca ? 3650L * 24 * 3600 : 825L * 24 * 3600);
        byte[] spki = subjectPublicKeyInfo((RSAPublicKey) subjectKey);
        byte[] ski = sha1(bitStringContent(spki));
        byte[] aki = ca ? null : sha1(bitStringContent(subjectPublicKeyInfo((RSAPublicKey) issuerCert.getPublicKey())));
        String issuerCn = ca ? cn : "TextSend LAN CA";
        byte[] tbs = tbs(serial, issuerCn, cn, utc(now), utc(until), spki, ca, hosts, ski, aki);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(issuerKey);
        sig.update(tbs);
        byte[] signature = sig.sign();
        byte[] der = seq(concat(tbs, algId(), bitString(signature)));
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] tbs(byte[] serial, String issuerCn, String subjectCn, String notBefore,
                              String notAfter, byte[] spki, boolean ca, List<String> hosts,
                              byte[] ski, byte[] aki) {
        byte[] version = explicit(0, integer(new byte[]{2}));
        byte[] validity = seq(concat(utcTime(notBefore), utcTime(notAfter)));
        List<byte[]> exts = new ArrayList<>();
        exts.add(extension(new byte[]{0x55, 0x1d, 0x13}, true, basicConstraints(ca)));
        exts.add(extension(new byte[]{0x55, 0x1d, 0x0f}, true, keyUsage(ca)));
        exts.add(extension(new byte[]{0x55, 0x1d, 0x0e}, false, octet(ski)));
        if (aki != null) {
            exts.add(extension(new byte[]{0x55, 0x1d, 0x23}, false, akiSeq(aki)));
        }
        if (!ca) {
            exts.add(extension(new byte[]{0x55, 0x1d, 0x25}, false, ekuServer()));
            exts.add(extension(new byte[]{0x55, 0x1d, 0x11}, false, san(hosts)));
        }
        byte[] extBytes = explicit(3, seq(concat(exts.toArray(byte[][]::new))));
        return seq(concat(version, integer(serial), algId(), name(issuerCn), validity,
                name(subjectCn), spki, extBytes));
    }

    private static byte[] subjectPublicKeyInfo(RSAPublicKey key) {
        byte[] n = integer(key.getModulus().toByteArray());
        byte[] e = integer(key.getPublicExponent().toByteArray());
        byte[] rsa = seq(concat(n, e));
        byte[] alg = seq(concat(oid(new byte[]{0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01}),
                new byte[]{0x05, 0x00}));
        return seq(concat(alg, bitString(rsa)));
    }

    private static byte[] bitStringContent(byte[] spki) {
        int p = valueAt(spki, 0);
        p = skip(spki, p);
        int bitLen = lengthAt(spki, p);
        int bitStart = valueAt(spki, p);
        return java.util.Arrays.copyOfRange(spki, bitStart + 1, bitStart + bitLen);
    }

    private static byte[] name(String cn) {
        byte[] cnOid = oid(new byte[]{0x55, 0x04, 0x03});
        byte[] val = printable(cn);
        byte[] atv = seq(concat(cnOid, val));
        return seq(set(atv));
    }

    private static byte[] algId() {
        return seq(concat(
                oid(new byte[]{0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b}),
                new byte[]{0x05, 0x00}));
    }

    private static byte[] basicConstraints(boolean ca) {
        if (!ca) {
            return seq(new byte[0]);
        }
        return seq(new byte[]{0x01, 0x01, (byte) 0xff});
    }

    private static byte[] keyUsage(boolean ca) {
        if (ca) {
            return bitStringRaw(7, new byte[]{0x06, 0x00});
        }
        return bitStringRaw(7, new byte[]{(byte) 0xa0, 0x00});
    }

    private static byte[] ekuServer() {
        return seq(oid(new byte[]{0x2b, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x01}));
    }

    private static byte[] san(List<String> hosts) {
        List<byte[]> names = new ArrayList<>();
        for (String host : hosts) {
            if (host.indexOf(':') >= 0 || looksIp(host)) {
                byte[] raw = ipBytes(host);
                if (raw != null) {
                    names.add(context(7, raw));
                    continue;
                }
            }
            names.add(context(2, host.getBytes(StandardCharsets.US_ASCII)));
        }
        return seq(concat(names.toArray(byte[][]::new)));
    }

    private static byte[] akiSeq(byte[] ski) {
        return seq(context(0, ski));
    }

    private static byte[] extension(byte[] oidBody, boolean critical, byte[] value) {
        byte[] id = oid(oidBody);
        byte[] wrapped = octet(value);
        if (critical) {
            return seq(concat(id, new byte[]{0x01, 0x01, (byte) 0xff}, wrapped));
        }
        return seq(concat(id, wrapped));
    }

    private static List<String> hostNames() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add("localhost");
        out.add("127.0.0.1");
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isMulticastAddress() || addr.isLinkLocalAddress()) {
                        continue;
                    }
                    String host = addr.getHostAddress();
                    int zone = host.indexOf('%');
                    if (zone >= 0) {
                        host = host.substring(0, zone);
                    }
                    if (addr instanceof Inet4Address || addr instanceof Inet6Address) {
                        out.add(host);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return List.copyOf(out);
    }

    private static boolean looksIp(String host) {
        return host.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.');
    }

    private static byte[] ipBytes(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr instanceof Inet4Address || (addr instanceof Inet6Address && host.indexOf(':') >= 0)) {
                return addr.getAddress();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, RNG);
        return gen.generateKeyPair();
    }

    private static String fingerprint(X509Certificate cert) throws Exception {
        byte[] dig = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dig.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02X", dig[i]));
        }
        return sb.toString();
    }

    private static byte[] sha1(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-1").digest(data);
    }

    private static String utc(Instant instant) {
        return UTC.format(instant);
    }

    private static String pem(String kind, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return "-----BEGIN " + kind + "-----\n" + b64 + "\n-----END " + kind + "-----\n";
    }

    private static byte[] pemBody(String pem) {
        StringBuilder sb = new StringBuilder();
        for (String line : pem.split("\n")) {
            if (line.startsWith("-----")) {
                continue;
            }
            sb.append(line.trim());
        }
        return Base64.getDecoder().decode(sb.toString());
    }

    private static void tighten(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, perms);
        } catch (Exception ignored) {
        }
    }

    private static byte[] seq(byte[] body) {
        return tlv(0x30, body);
    }

    private static byte[] set(byte[] body) {
        return tlv(0x31, body);
    }

    private static byte[] integer(byte[] be) {
        int i = 0;
        while (i < be.length - 1 && be[i] == 0) {
            i++;
        }
        if ((be[i] & 0x80) != 0) {
            byte[] padded = new byte[be.length - i + 1];
            System.arraycopy(be, i, padded, 1, be.length - i);
            return tlv(0x02, padded);
        }
        return tlv(0x02, java.util.Arrays.copyOfRange(be, i, be.length));
    }

    private static byte[] oid(byte[] body) {
        return tlv(0x06, body);
    }

    private static byte[] octet(byte[] body) {
        return tlv(0x04, body);
    }

    private static byte[] printable(String s) {
        return tlv(0x13, s.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] utcTime(String s) {
        return tlv(0x17, s.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] bitString(byte[] body) {
        byte[] withUnused = new byte[body.length + 1];
        System.arraycopy(body, 0, withUnused, 1, body.length);
        return tlv(0x03, withUnused);
    }

    private static byte[] bitStringRaw(int unused, byte[] content) {
        byte[] withUnused = new byte[content.length + 1];
        withUnused[0] = (byte) unused;
        System.arraycopy(content, 0, withUnused, 1, content.length);
        return tlv(0x03, withUnused);
    }

    private static byte[] explicit(int n, byte[] inner) {
        return tlv(0xa0 | n, inner);
    }

    private static byte[] context(int n, byte[] body) {
        return tlv(0x80 | n, body);
    }

    private static byte[] tlv(int tag, byte[] body) {
        byte[] len = length(body.length);
        byte[] out = new byte[1 + len.length + body.length];
        out[0] = (byte) tag;
        System.arraycopy(len, 0, out, 1, len.length);
        System.arraycopy(body, 0, out, 1 + len.length, body.length);
        return out;
    }

    private static byte[] length(int n) {
        if (n < 128) {
            return new byte[]{(byte) n};
        }
        if (n < 256) {
            return new byte[]{(byte) 0x81, (byte) n};
        }
        return new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    private static int skip(byte[] der, int at) {
        return valueAt(der, at) + lengthAt(der, at);
    }

    private static int lengthAt(byte[] der, int at) {
        int b = der[at + 1] & 0xff;
        if (b < 128) {
            return b;
        }
        int nbytes = b & 0x7f;
        int n = 0;
        for (int i = 0; i < nbytes; i++) {
            n = (n << 8) | (der[at + 2 + i] & 0xff);
        }
        return n;
    }

    private static int valueAt(byte[] der, int at) {
        int b = der[at + 1] & 0xff;
        if (b < 128) {
            return at + 2;
        }
        return at + 2 + (b & 0x7f);
    }
}
