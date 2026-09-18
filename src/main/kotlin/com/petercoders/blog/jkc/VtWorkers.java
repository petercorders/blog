import java.util.*; import java.util.concurrent.*;
public class VtWorkers {
    public static void main(String[] args) throws Exception {
        Set<String> carriers = ConcurrentHashMap.newKeySet();
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1000; i++) ex.submit(() -> {
                String s = Thread.currentThread().toString();
                carriers.add(s.substring(s.indexOf('@') + 1));
                Thread.sleep(50); return null; });
        }
        System.out.println("cores=" + Runtime.getRuntime().availableProcessors() + " carriers=" + carriers.size() + " " + new TreeSet<>(carriers));
    }
}
