// BenchMT.java
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

// Thread T개가 각자 라이브 셋 조각을 갈아 끼웁니다. 코어를 전부 채워 GC Thread와 CPU를 경쟁시킵니다.
public class BenchMT {
    public static void main(String[] a) throws Exception {
        int threads = Integer.parseInt(a[0]);
        int liveSlots = 300_000;                                  // 합계 ≈ 300MB 라이브 셋
        byte[][] live = new byte[liveSlots][];
        for (int i = 0; i < liveSlots; i++) live[i] = new byte[1024];
        long end = System.nanoTime() + 15_000_000_000L;
        LongAdder ops = new LongAdder();
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int lo = liveSlots / threads * t, hi = liveSlots / threads * (t + 1);
            ts[t] = new Thread(() -> {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                long n = 0;
                while (System.nanoTime() < end) {
                    for (int k = 0; k < 10_000; k++) live[lo + r.nextInt(hi - lo)] = new byte[1024];
                    n += 10_000;
                }
                ops.add(n);
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        System.out.println("threads=" + threads + " ops=" + ops.sum() + " ops/s=" + ops.sum() / 15);
    }
}
