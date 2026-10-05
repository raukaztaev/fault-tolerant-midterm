package kz.aitu.ftr.common;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Resolves the container hostname so logs can show which instance handled a request.
 */
public final class Host {

    private static final String HOST = resolve();

    private Host() {
    }

    private static String resolve() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    public static String get() {
        return HOST;
    }
}
