public class VtInterrupt {
    static volatile long sink;
    public static void main(String[] args) throws Exception {
        long[] woke = new long[1];
        var sleep = Thread.ofVirtual().name("sleep").start(() -> { try { Thread.sleep(60_000); } catch (InterruptedException e) { woke[0] = System.nanoTime(); } });
        var poll  = Thread.ofVirtual().name("poll").start(() -> { long i = 0; while (!Thread.currentThread().isInterrupted()) sink = i++; });
        var spin  = Thread.ofVirtual().name("spin").start(() -> { long i = 0; while (true) sink = i++; });
        Thread.sleep(200);
        System.out.println("before: sleep=" + sleep.getState() + " poll=" + poll.getState() + " spin=" + spin.getState());
        long t0 = System.nanoTime();
        sleep.interrupt(); poll.interrupt(); spin.interrupt();
        sleep.join(3000); poll.join(3000); spin.join(3000);
        System.out.printf("sleep woke after %.3f ms; after 3s: sleep.isAlive=%b poll.isAlive=%b spin.isAlive=%b (spin.isInterrupted=%b)%n",
            (woke[0] - t0) / 1e6, sleep.isAlive(), poll.isAlive(), spin.isAlive(), spin.isInterrupted());
    }
}
