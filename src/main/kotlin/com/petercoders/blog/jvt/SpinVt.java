public class SpinVt { static volatile long x; public static void main(String[] a) throws Exception {
  System.out.println("pid=" + ProcessHandle.current().pid());
  Thread.ofVirtual().name("spinner").start(() -> { while (true) x++; });
  Thread.ofVirtual().name("sleeper").start(() -> { try { Thread.sleep(60000); } catch (Exception e) {} });
  Thread.sleep(60000); } }
