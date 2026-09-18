public class VtDumpCarrier {
    static volatile long sink;
    public static void main(String[] args) throws Exception {
        System.out.println(ProcessHandle.current().pid());
        Thread.ofVirtual().name("spin").start(() -> { while (true) sink++; });   // 마운트 유지
        Thread.ofVirtual().name("nap").start(() -> {
            try { Thread.sleep(30_000); } catch (InterruptedException e) {}      // 언마운트
        });
        Thread.sleep(30_000);
    }
}
// jcmd $PID Thread.dump_to_file -format=json carrier.json   <- carrier 키가 보임
// jcmd $PID Thread.dump_to_file carrier.txt                  <- carrier 키가 안 보임
