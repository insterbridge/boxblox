package app.boxblox;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/**
 * Names the region of the game server the guest joins.
 *
 * <p><b>Where the fact lives.</b> The engine logs every join with
 * {@code Transport selection: ... DatacenterId=437} followed by
 * {@code UDMUX Address = 128.116.13.34} — the datacenter id is Roblox's own and
 * the UDMUX address is the public edge the client is actually attached to. Both
 * lines are in the newest {@code *_last.log} under the build's data directory,
 * which this process writes, so a reader needs no special privilege.
 *
 * <p><b>Why the edge address and not the RCC one.</b> The join line's own
 * address is an internal 10.x; the UDMUX address is routable and identifies the
 * colo, which is the location a player means by "where is this server".
 */
final class ServerLocation {

    private static final String TAG = "RobloxWrapper";

    private static final Pattern DATACENTER =
            Pattern.compile("DatacenterId=(\\d+).*\\n.*UDMUX Address = ([0-9.]+)");

    private static volatile boolean running;

    private ServerLocation() {
    }

    /**
     * Watch the guest's newest log and toast each new server's region.
     *
     * <p>One watcher per process: a second call is a no-op. The thread is a
     * daemon, so it never holds the process open on its own.
     */
    static void start(RobloxStore.Build build, Context context) {
        if (running || build == null) {
            return;
        }
        running = true;
        Context app = context.getApplicationContext();
        File logs = new File(build.getDataDir(), "files/appData/logs");
        Handler main = new Handler(Looper.getMainLooper());
        Thread watcher = new Thread(() -> watch(logs, app, main), "server-location");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void watch(File logs, Context context, Handler main) {
        LogTail reader = new LogTail();
        String announced = "";
        while (running) {
            try {
                File newest = newest(logs);
                if (newest != null) {
                    String added = reader.read(newest);
                    Matcher m = DATACENTER.matcher(added);
                    // A chunk can hold several joins (the app sat idle, or a
                    // reconnect happened between polls); the last one is the
                    // server actually attached now, so later matches win.
                    String datacenter = null;
                    String edge = null;
                    while (m.find()) {
                        datacenter = m.group(1);
                        edge = m.group(2);
                    }
                    if (datacenter != null) {
                        String key = newest.getName() + ":" + datacenter + ":" + edge;
                        if (!key.equals(announced)) {
                            announced = key;
                            announce(context, main, datacenter, edge);
                        }
                    }
                }
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Log.w(TAG, "server location watch failed", t);
            }
        }
    }

    private static File newest(File dir) {
        File newest = null;
        File[] files = dir.listFiles((d, n) -> n.endsWith("_last.log"));
        if (files == null) {
            return null;
        }
        for (File f : files) {
            if (newest == null || f.lastModified() > newest.lastModified()) {
                newest = f;
            }
        }
        return newest;
    }

    private static void announce(Context context, Handler main, String datacenter, String edge) {
        // The lookup runs on the watcher thread; only the toast crosses to main.
        new Thread(() -> {
            String region = region(edge);
            main.post(() -> Toast.makeText(context,
                    region == null
                            ? "Server: datacenter " + datacenter
                            : "Server: " + region,
                    Toast.LENGTH_LONG).show());
        }, "server-location-lookup").start();
    }

    /**
     * The edge address's city and country, or null when every lookup fails.
     *
     * <p>One endpoint is not enough on a free tier: ipapi.co rate-limits
     * (measured — the first live test toasted only the datacenter id), so the
     * chain runs ipwho.is first (keyless HTTPS), then ip-api.com (HTTP), then
     * ipapi.co. Field names differ per service, so each is parsed for the
     * fields it actually answers. A miss everywhere still toasts the
     * datacenter id, which is the fact the feature promises.
     */
    private static String region(String ip) {
        String[][] services = {
                {"https://ipwho.is/" + ip, "city", "country"},
                {"http://ip-api.com/json/" + ip, "city", "country"},
                {"https://ipapi.co/" + ip + "/json/", "city", "country_name"},
        };
        for (String[] service : services) {
            String body = fetch(service[0]);
            if (body == null) {
                continue;
            }
            String city = jsonField(body, service[1]);
            String country = jsonField(body, service[2]);
            if (city != null && country != null) {
                return city + ", " + country;
            }
        }
        return null;
    }

    /** One URL's body, or null for anything but a 200. */
    private static String fetch(String address) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            try (InputStream in = connection.getInputStream();
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 64 * 1024) return null;
                    out.write(buffer, 0, count);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** One string field of a flat JSON object; enough for this response. */
    private static String jsonField(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }
}
