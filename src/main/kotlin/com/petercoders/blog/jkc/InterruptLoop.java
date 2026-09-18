public class InterruptLoop {
    static volatile long sink;
    public static void main(String[] args) throws Exception {
        Thread spin  = new Thread(() -> { long i = 0; while (true) { sink = i++; } });
        Thread poll  = new Thread(() -> { long i = 0; while (!Thread.currentThread().isInterrupted()) { sink = i++; } });
        Thread sleep = new Thread(() -> { try { Thread.sleep(60_000); } catch (InterruptedException e) {
            System.out.println("sleep: " + e.getClass().getSimpleName() + " flagAfter=" + Thread.currentThread().isInterrupted()); } });
        spin.start(); poll.start(); sleep.start();
        Thread.sleep(200);
        spin.interrupt(); poll.interrupt(); sleep.interrupt();
        Thread.sleep(3000);
        System.out.printf("after 3s: spin.isAlive=%b (isInterrupted=%b) poll.isAlive=%b sleep.isAlive=%b%n",
            spin.isAlive(), spin.isInterrupted(), poll.isAlive(), sleep.isAlive());
    }
}
