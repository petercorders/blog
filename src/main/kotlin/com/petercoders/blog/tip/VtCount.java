import java.lang.management.ManagementFactory;
import java.util.stream.IntStream;
import jdk.management.VirtualThreadSchedulerMXBean;

// 가상 스레드 2,000개를 세워 두고 ThreadMXBean / VirtualThreadSchedulerMXBean 이 각각 무엇을 세는지 같은 시각에 읽는다.
public class VtCount {
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) {} }
    public static void main(String[] args) throws Exception {
        var tmx = ManagementFactory.getThreadMXBean();
        var smx = ManagementFactory.getPlatformMXBean(VirtualThreadSchedulerMXBean.class);
        System.out.println(ProcessHandle.current().pid());
        System.out.printf("before: ThreadMXBean threadCount=%d peak=%d%n", tmx.getThreadCount(), tmx.getPeakThreadCount());
        var ts = IntStream.range(0, 2000).mapToObj(i -> Thread.ofVirtual().name("vt-" + i).start(() -> sleep(8000))).toList();
        Thread.sleep(1000);
        System.out.printf("during: ThreadMXBean threadCount=%d peak=%d daemon=%d%n", tmx.getThreadCount(), tmx.getPeakThreadCount(), tmx.getDaemonThreadCount());
        System.out.printf("during: SchedulerMXBean parallelism=%d poolSize=%d mounted=%d queued=%d%n",
            smx.getParallelism(), smx.getPoolSize(), smx.getMountedVirtualThreadCount(), smx.getQueuedVirtualThreadCount());
        for (var t : ts) t.join();
        System.out.printf("after: ThreadMXBean threadCount=%d peak=%d%n", tmx.getThreadCount(), tmx.getPeakThreadCount());
    }
}
