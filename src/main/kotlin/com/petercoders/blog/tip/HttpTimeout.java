import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

// tip-p15 — JDK HttpClient 의 HttpRequest.timeout(1s) 이 응답 헤더까지만 재는가, 바디 소비까지 재는가(JDK 25 vs 26).
// 로컬 서버가 헤더를 바로 보내고 바디를 200ms 마다 1바이트씩 3초 동안(15바이트) 보낸다.
//   $J25/bin/java HttpTimeout.java ; $J26/bin/java HttpTimeout.java
public class HttpTimeout {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", ex -> {
            ex.sendResponseHeaders(200, 0);                  // 0 = chunked, 헤더는 지금 나간다
            try (OutputStream os = ex.getResponseBody()) {
                for (int i = 0; i < 15; i++) { os.write('x'); os.flush(); Thread.sleep(200); }
            } catch (Exception ignored) { }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow");
        System.out.println("java.runtime.version=" + System.getProperty("java.runtime.version"));
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        for (int run = 1; run <= 3; run++) {
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(1)).build();
            long t0 = System.nanoTime();
            String result;
            try {
                HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
                result = "status=" + r.statusCode() + " bodyLength=" + r.body().length();
            } catch (Exception e) { result = e.getClass().getName() + ": " + e.getMessage(); }
            System.out.printf("run %d ofString()       timeout(1s): %5d ms  %s%n", run, (System.nanoTime() - t0) / 1_000_000, result);

            t0 = System.nanoTime();
            long headersAt = -1;
            try {
                HttpResponse<InputStream> r = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                headersAt = (System.nanoTime() - t0) / 1_000_000;
                int n = 0;
                try (InputStream in = r.body()) { while (in.read() != -1) n++; }
                result = "status=" + r.statusCode() + " bytesRead=" + n;
            } catch (Exception e) { result = e.getClass().getName() + ": " + e.getMessage(); }
            System.out.printf("run %d ofInputStream()  timeout(1s): %5d ms  (headers at %d ms)  %s%n", run, (System.nanoTime() - t0) / 1_000_000, headersAt, result);
        }
        server.stop(0);
        System.exit(0);
    }
}
