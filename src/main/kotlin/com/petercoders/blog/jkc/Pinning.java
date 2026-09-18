// Pinning.java (전문, 13줄) — 락은 스레드마다 다른 객체(경합이 아니라 pinning만 재기 위해)
public class Pinning {
    public static void main(String[] args) throws Exception {
        int n = 100; long t0 = System.nanoTime();
        Thread[] ts = new Thread[n];
        for (int i = 0; i < n; i++) {
            Object lock = new Object();
            ts[i] = Thread.ofVirtual().start(() -> {
                synchronized (lock) { try { Thread.sleep(100); } catch (InterruptedException e) {} } });
        }
        for (Thread t : ts) t.join();
        System.out.printf("%s  n=%d  %.0f ms%n", Runtime.version(), n, (System.nanoTime() - t0) / 1e6);
    }
}
