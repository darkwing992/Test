package com.castapp.tv;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Finds the phone's address on the local Wi-Fi (or hotspot) network. */
public final class NetUtil {
    private NetUtil() { }

    public static String localIp() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    // Prefer Wi-Fi / hotspot interfaces over mobile data or VPN.
                    if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")
                            || name.startsWith("eth")) {
                        return a.getHostAddress();
                    }
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    public static String url(int port) {
        String ip = localIp();
        return ip == null ? null : "http://" + ip + ":" + port;
    }
}
