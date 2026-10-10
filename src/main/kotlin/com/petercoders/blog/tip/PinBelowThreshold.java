import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.foreign.ValueLayout.*;

// tip-p22 — JDK 24 이후에도 남는 pinning(Native frame on stack)을 일부러 만들고, 20ms 미만 pinning 이
// jdk.VirtualThreadPinned 기본 설정(threshold 20 ms)과 Micrometer VirtualThreadMetrics(20 ms 고정)에 보이는지 본다.
//   C qsort 를 FFM 으로 부르고, 비교 함수(upcall) 안에서 Thread.sleep 한다. 비교 함수가 도는 동안 VT 스택에 native 프레임이 있어 unmount 하지 못한다.
//   short: VT 8개, 원소 6개 역순 배열, 비교마다 sleep(5)   long: VT 1개, 원소 3개 역순 배열, 비교마다 sleep(25)
//   java --enable-native-access=ALL-UNNAMED [-XX:StartFlightRecording=...] -cp <micrometer jars> PinBelowThreshold.java
public class PinBelowThreshold {
    static final ThreadLocal<Integer> SLEEP_MS = ThreadLocal.withInitial(() -> 0);
    static final AtomicInteger shortCompares = new AtomicInteger();
    static final AtomicInteger longCompares = new AtomicInteger();

    static int cmp(MemorySegment a, MemorySegment b) {
        int ms = SLEEP_MS.get();
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        (ms >= 20 ? longCompares : shortCompares).incrementAndGet();
        return Integer.compare(a.get(JAVA_INT, 0), b.get(JAVA_INT, 0));
    }

    public static void main(String[] args) throws Throwable {
        Object metrics = null, registry = null;
        try {   // micrometer-java21 이 클래스패스에 있으면 VirtualThreadMetrics 를 붙인다(리플렉션으로, 없어도 돈다)
            registry = Class.forName("io.micrometer.core.instrument.simple.SimpleMeterRegistry").getConstructor().newInstance();
            metrics = Class.forName("io.micrometer.java21.instrument.binder.jdk.VirtualThreadMetrics").getConstructor().newInstance();
            metrics.getClass().getMethod("bindTo", Class.forName("io.micrometer.core.instrument.MeterRegistry")).invoke(metrics, registry);
        } catch (ClassNotFoundException e) { System.out.println("micrometer-java21 not on classpath: " + e.getMessage()); }
        Thread.sleep(1000);   // RecordingStream 이 뜰 시간

        Linker linker = Linker.nativeLinker();
        MethodHandle qsort = linker.downcallHandle(linker.defaultLookup().find("qsort").orElseThrow(),
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS));
        MethodHandle cmp = MethodHandles.lookup().findStatic(PinBelowThreshold.class, "cmp",
                MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class));
        MemorySegment cmpStub = linker.upcallStub(cmp,
                FunctionDescriptor.of(JAVA_INT, ADDRESS.withTargetLayout(JAVA_INT), ADDRESS.withTargetLayout(JAVA_INT)), Arena.global());

        long t0 = System.nanoTime();
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < 9; i++) {
            boolean isLong = (i == 8);
            threads.add(Thread.ofVirtual().name(isLong ? "vt-long" : "vt-short-" + i).start(() -> {
                SLEEP_MS.set(isLong ? 25 : 5);
                int[] values = isLong ? new int[]{3, 2, 1} : new int[]{6, 5, 4, 3, 2, 1};
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment arr = arena.allocateFrom(JAVA_INT, values);
                    qsort.invokeExact(arr, (long) values.length, 4L, cmpStub);
                } catch (Throwable t) { throw new RuntimeException(t); }
            }));
        }
        for (Thread t : threads) t.join();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("qsort done in %d ms: short compares (sleep 5 ms, 8 VTs) = %d, long compares (sleep 25 ms, 1 VT) = %d%n",
                ms, shortCompares.get(), longCompares.get());

        if (metrics != null) {
            Thread.sleep(2000);   // RecordingStream 은 약 1초 주기로 flush 한다
            Object timer = registry.getClass().getMethod("find", String.class).invoke(registry, "jvm.threads.virtual.pinned");
            timer = timer.getClass().getMethod("timer").invoke(timer);
            long count = (long) timer.getClass().getMethod("count").invoke(timer);
            double totalMs = (double) timer.getClass().getMethod("totalTime", java.util.concurrent.TimeUnit.class).invoke(timer, java.util.concurrent.TimeUnit.MILLISECONDS);
            double maxMs = (double) timer.getClass().getMethod("max", java.util.concurrent.TimeUnit.class).invoke(timer, java.util.concurrent.TimeUnit.MILLISECONDS);
            System.out.printf("Micrometer jvm.threads.virtual.pinned: count=%d totalTime=%.1f ms max=%.1f ms%n", count, totalMs, maxMs);
            ((AutoCloseable) metrics).close();
        }
    }
}
