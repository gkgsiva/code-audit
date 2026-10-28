package com.acme.tools;

/** Helper used by operators (built only with -Ptools). */
public final class Checksum {
    private Checksum() {
    }

    public static long of(byte[] data) {
        long sum = 0;
        for (byte b : data) {
            sum += b;
        }
        return sum;
    }
}
