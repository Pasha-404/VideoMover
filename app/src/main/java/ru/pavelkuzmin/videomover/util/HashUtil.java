package ru.pavelkuzmin.videomover.util;

import java.security.MessageDigest;

public class HashUtil {
    public static byte[] sha256(byte[] data, int len, MessageDigest md) {
        md.update(data, 0, len);
        return md.digest();
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
