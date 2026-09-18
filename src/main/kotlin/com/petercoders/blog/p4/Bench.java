// Bench.java
import java.util.concurrent.ThreadLocalRandom;

// 라이브 셋 ~300MB를 유지한 채 15초 동안 1KB 객체를 계속 갈아 끼웁니다 (처리량 = 갈아 끼운 횟수)
public class Bench {
    public static void main(String[] a) throws Exception {
        int liveSlots = 300_000;                       // 300,000 × 1KB ≈ 300MB 라이브 셋
        byte[][] live = new byte[liveSlots][];
        for (int i = 0; i < liveSlots; i++) live[i] = new byte[1024];
        long end = System.nanoTime() + 15_000_000_000L;
        long ops = 0;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        while (System.nanoTime() < end) {
            for (int k = 0; k < 10_000; k++) live[r.nextInt(liveSlots)] = new byte[1024];   // 옛 객체는 가비지가 됩니다
            ops += 10_000;
        }
        System.out.println("ops=" + ops + " ops/s=" + ops / 15);
    }
}
