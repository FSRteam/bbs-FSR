package mchorse.bbs_mod.utils.net;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Process-wide HTTP client shared by every downloader (FFmpeg assets, mod
 * self-updates, remote images). One client means one connection pool, so
 * concurrent transfers reuse sockets instead of handshaking per download.
 */
public final class SharedHttp
{
    private static volatile HttpClient client;

    private SharedHttp()
    {}

    public static HttpClient get()
    {
        HttpClient local = client;

        if (local == null)
        {
            synchronized (SharedHttp.class)
            {
                local = client;

                if (local == null)
                {
                    local = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15L))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
                    client = local;
                }
            }
        }

        return local;
    }
}
