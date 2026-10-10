import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.java21.instrument.binder.jdk.VirtualThreadMetrics;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.stream.IntStream;

// tip-p26 — VtCount.java(tip-p7) 와 같은 VT 2,000개 sleep 상황에 Micrometer VirtualThreadMetrics(micrometer-java21 1.17.1) 를 붙여
// jvm.threads.virtual.live{scheduling.status=mounted|queued}, parallelism, pool.size 를 읽는다.
// 대조로 CPU spin VT 200개(각 300ms)를 세웠을 때도 읽는다(게이지가 mounted·queued 를 실제로 읽는지).
//   java -cp <micrometer-core,commons,observation,HdrHistogram,micrometer-java21> VtGauges.java
public class VtGauges {
    static volatile long sink;
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { } }

    static String gauges(SimpleMeterRegistry r) {
        double mounted = r.get("jvm.threads.virtual.live").tag("scheduling.status", "mounted").gauge().value();
        double queued = r.get("jvm.threads.virtual.live").tag("scheduling.status", "queued").gauge().value();
        double par = r.get("jvm.threads.virtual.parallelism").gauge().value();
        double pool = r.get("jvm.threads.virtual.pool.size").gauge().value();
        return String.format("live{mounted}=%.0f live{queued}=%.0f (sum %.0f) parallelism=%.0f pool.size=%.0f", mounted, queued, mounted + queued, par, pool);
    }

    public static void main(String[] args) throws Exception {
        var registry = new SimpleMeterRegistry();
        try (var metrics = new VirtualThreadMetrics()) {
            metrics.bindTo(registry);
            var tmx = ManagementFactory.getThreadMXBean();
            System.out.println("registered: " + registry.getMeters().stream().map(m -> m.getId().getName() + m.getId().getTags()).sorted().toList());
            System.out.println("before: " + gauges(registry) + " | ThreadMXBean threadCount=" + tmx.getThreadCount());

            List<Thread> sleepers = IntStream.range(0, 2000).mapToObj(i -> Thread.ofVirtual().name("vt-" + i).start(() -> sleep(4000))).toList();
            Thread.sleep(1000);
            long alive = sleepers.stream().filter(Thread::isAlive).count();
            System.out.println("2000 sleeping VTs (alive=" + alive + "): " + gauges(registry) + " | ThreadMXBean threadCount=" + tmx.getThreadCount());
            for (var t : sleepers) t.join();

            List<Thread> spinners = IntStream.range(0, 200).mapToObj(i -> Thread.ofVirtual().name("spin-" + i).start(() -> {
                long end = System.nanoTime() + 300_000_000L; long x = 0; while (System.nanoTime() < end) x++; sink += x;
            })).toList();
            Thread.sleep(150);
            long aliveSpin = spinners.stream().filter(Thread::isAlive).count();
            System.out.println("200 CPU-spinning VTs (alive=" + aliveSpin + "): " + gauges(registry) + " | ThreadMXBean threadCount=" + tmx.getThreadCount());
            for (var t : spinners) t.join();
            System.out.println("after: " + gauges(registry));
        }
    }
}
