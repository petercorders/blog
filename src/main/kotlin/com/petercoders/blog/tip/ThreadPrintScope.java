// tip-p23 — jcmd Thread.print 이 mount 된 Virtual Thread 를 찍는지 본다(JDK 25 / 26 비교).
// CPU spin VT 1개(계속 mount 상태) + sleep VT 1개(unmount 상태)를 세우고 PID 를 찍은 뒤 30초 기다린다.
//   $J26/bin/java ThreadPrintScope.java > pid.txt &  →  jcmd <PID> Thread.print | grep -n 'Mounted virtual thread\|vt-spin\|vt-sleep'
public class ThreadPrintScope {
    static volatile long sink;
    public static void main(String[] args) throws Exception {
        Thread.ofVirtual().name("vt-spin").start(() -> {
            long end = System.nanoTime() + 30_000_000_000L;
            long x = 0;
            while (System.nanoTime() < end) x++;
            sink = x;
        });
        Thread.ofVirtual().name("vt-sleep").start(() -> {
            try { Thread.sleep(30_000); } catch (InterruptedException e) { }
        });
        System.out.println(ProcessHandle.current().pid());
        System.out.flush();
        Thread.sleep(30_000);
    }
}
