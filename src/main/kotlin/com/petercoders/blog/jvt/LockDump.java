import java.util.concurrent.locks.ReentrantLock;
public class LockDump {
    static final ReentrantLock LOCK = new ReentrantLock();
    public static void main(String[] a) throws Exception {
        System.out.println("pid=" + ProcessHandle.current().pid());
        Thread.ofVirtual().name("holder").start(() -> {
            LOCK.lock();
            try { Thread.sleep(60_000); } catch (InterruptedException e) {}
        });
        Thread.sleep(300);
        Thread.ofVirtual().name("waiter").start(() -> { LOCK.lock(); });
        Thread.sleep(60_000);
    }
}
