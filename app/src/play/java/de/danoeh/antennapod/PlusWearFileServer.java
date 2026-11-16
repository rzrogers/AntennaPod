package de.danoeh.antennapod;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class PlusWearFileServer {
    private static final String TAG = "PlusWearFileServer";
    private static final int ACCEPT_TIMEOUT_MILLIS = 120_000;
    private static final int SOCKET_TIMEOUT_MILLIS = 30_000;
    private static final long WAKE_LOCK_MILLIS = 10 * 60_000L;
    private static final int MAX_TOKEN_BYTES = 100;
    private static ServerSocket current;

    static final class Offer {
        final String host;
        final int port;
        final String token;

        Offer(String host, int port, String token) {
            this.host = host;
            this.port = port;
            this.token = token;
        }
    }

    private PlusWearFileServer() {
    }

    static synchronized Offer offer(Context context, File file) {
        String host = wifiAddress(context);
        if (host == null) {
            return null;
        }
        closeCurrent();
        try {
            ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName(host));
            server.setSoTimeout(ACCEPT_TIMEOUT_MILLIS);
            String token = UUID.randomUUID().toString();
            current = server;
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "antennapod:plus-watch-copy");
            wakeLock.acquire(WAKE_LOCK_MILLIS);
            new Thread(() -> serve(server, file, token, wakeLock), TAG).start();
            return new Offer(host, server.getLocalPort(), token);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not offer file over Wi-Fi", e);
            return null;
        }
    }

    private static void closeCurrent() {
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                Log.d(TAG, "Closing previous server failed", e);
            }
            current = null;
        }
    }

    private static void serve(ServerSocket server, File file, String token, PowerManager.WakeLock wakeLock) {
        try (ServerSocket closing = server; Socket socket = closing.accept()) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            InputStream in = socket.getInputStream();
            StringBuilder received = new StringBuilder();
            int value;
            while ((value = in.read()) >= 0 && value != '\n' && received.length() < MAX_TOKEN_BYTES) {
                received.append((char) value);
            }
            if (!token.equals(received.toString())) {
                Log.w(TAG, "Rejected connection with wrong token");
                return;
            }
            OutputStream out = socket.getOutputStream();
            try (FileInputStream fileIn = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = fileIn.read(buffer)) >= 0) {
                    out.write(buffer, 0, count);
                }
            }
            out.flush();
        } catch (IOException e) {
            Log.d(TAG, "Serving file ended", e);
        } finally {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }

    static void send(File file, OutputStream out) throws IOException {
        try (FileInputStream fileIn = new FileInputStream(file); OutputStream closing = out) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = fileIn.read(buffer)) >= 0) {
                closing.write(buffer, 0, count);
            }
            closing.flush();
        }
    }

    private static String wifiAddress(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return null;
        }
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                continue;
            }
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties == null) {
                continue;
            }
            for (LinkAddress address : properties.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address) {
                    return address.getAddress().getHostAddress();
                }
            }
        }
        return null;
    }
}
