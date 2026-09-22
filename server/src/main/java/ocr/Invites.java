package ocr;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

public class Invites {

    static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    private final Map<String, String> nameByCode = new HashMap<>();
    private final Map<String, Integer> usedToday = new HashMap<>();
    private final int dailyLimit;
    private LocalDate day = LocalDate.now(ZONE);

    Invites(String spec, int dailyLimit) {
        this.dailyLimit = dailyLimit;
        for (String entry : spec.split("[\\r\\n;]+")) {
            String line = entry.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0 || eq == line.length() - 1) continue;
            String name = line.substring(0, eq).strip();
            String code = line.substring(eq + 1).strip();
            if (!name.isEmpty() && !code.isEmpty()) nameByCode.put(code, name);
        }
    }

    int size() {
        return nameByCode.size();
    }

    int dailyLimit() {
        return dailyLimit;
    }

    String nameFor(String code) {
        return code == null ? null : nameByCode.get(code);
    }

    synchronized boolean tryAcquire(String name) {
        rollover();
        int used = usedToday.getOrDefault(name, 0);
        if (used >= dailyLimit) return false;
        usedToday.put(name, used + 1);
        return true;
    }

    synchronized void release(String name) {
        rollover();
        usedToday.computeIfPresent(name, (k, v) -> v > 1 ? v - 1 : null);
    }

    synchronized int remaining(String name) {
        rollover();
        return Math.max(0, dailyLimit - usedToday.getOrDefault(name, 0));
    }

    private void rollover() {
        LocalDate today = LocalDate.now(ZONE);
        if (!today.equals(day)) {
            day = today;
            usedToday.clear();
        }
    }
}
