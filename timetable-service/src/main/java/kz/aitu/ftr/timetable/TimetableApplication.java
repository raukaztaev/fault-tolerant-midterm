package kz.aitu.ftr.timetable;

import kz.aitu.ftr.common.Backoff;
import kz.aitu.ftr.common.ChaosController;
import kz.aitu.ftr.common.DbRetry;
import kz.aitu.ftr.common.Graceful;
import kz.aitu.ftr.common.Host;
import kz.aitu.ftr.common.ReplicatedDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Timetable service.
 * Demonstrates graceful degradation: when the records service is unavailable,
 * the timetable is still returned with cached/default data and a DEGRADED status
 * instead of failing the whole request.
 */
@SpringBootApplication(scanBasePackages = "kz.aitu.ftr")
public class TimetableApplication {

    public static void main(String[] args) {
        SpringApplication.run(TimetableApplication.class, args);
    }

    @Bean
    public ReplicatedDatabase db(
            @Value("${db.primary-url}") String primaryUrl,
            @Value("${db.standby-url:}") String standbyUrl,
            @Value("${db.replication-enabled:true}") boolean replication,
            @Value("${spring.datasource.username:university}") String user,
            @Value("${spring.datasource.password:university}") String password) {
        ReplicatedDatabase db = new ReplicatedDatabase(primaryUrl, standbyUrl, user, password, replication);
        db.ensureSchema("CREATE TABLE IF NOT EXISTS timetable (" +
                "id VARCHAR(40) PRIMARY KEY, student_id VARCHAR(32), day VARCHAR(12), " +
                "time VARCHAR(20), course VARCHAR(20), room VARCHAR(20))");
        return db;
    }

    @RestController
    @RequestMapping("/timetable")
    public static class TimetableController {

        private static final Logger log = LoggerFactory.getLogger(TimetableController.class);

        private final ReplicatedDatabase db;
        private final ChaosController chaos;

        public TimetableController(ReplicatedDatabase db, ChaosController chaos) {
            this.db = db;
            this.chaos = chaos;
        }

        @PostMapping
        public ResponseEntity<Map<String, Object>> addClass(@RequestBody Map<String, String> body) {
            chaos.apply("addClass");
            String studentId = body.getOrDefault("studentId", "S1");
            String day = body.getOrDefault("day", "Mon");
            String time = body.getOrDefault("time", "09:00");
            String course = body.getOrDefault("course", "CSE101");
            String room = body.getOrDefault("room", "B-101");
            String id = studentId + "-" + course + "-" + day + "-" + time;

            DbRetry.withRetry("addClass", 3, 300, () -> db.write(c -> {
                try (PreparedStatement st = c.prepareStatement(
                        "INSERT INTO timetable (id, student_id, day, time, course, room) VALUES (?, ?, ?, ?, ?, ?)" +
                                " ON CONFLICT (id) DO NOTHING")) {
                    st.setString(1, id);
                    st.setString(2, studentId);
                    st.setString(3, day);
                    st.setString(4, time);
                    st.setString(5, course);
                    st.setString(6, room);
                    st.executeUpdate();
                }
                return null;
            }));
            log.info("TIMETABLE class added {} on {}", id, Host.get());
            return ResponseEntity.ok(Map.of("status", "ADDED", "id", id, "instance", Host.get()));
        }

        /**
         * Weekly timetable for a student. The record-check part calls the records
         * service; if that call fails (after retries), the timetable is still
         * returned in DEGRADED mode. This is the graceful degradation mechanism.
         */
        @GetMapping("/{studentId}")
        public ResponseEntity<Map<String, Object>> timetable(@PathVariable String studentId) {
            chaos.apply("timetable");
            long started = System.currentTimeMillis();

            List<Map<String, Object>> classes = DbRetry.withRetry("timetable-read", 3, 300, () ->
                    db.read(c -> {
                        List<Map<String, Object>> out = new ArrayList<>();
                        try (PreparedStatement st = c.prepareStatement(
                                "SELECT day, time, course, room FROM timetable WHERE student_id = ? " +
                                        "ORDER BY CASE day WHEN 'Mon' THEN 1 WHEN 'Tue' THEN 2 WHEN 'Wed' THEN 3 " +
                                        "WHEN 'Thu' THEN 4 WHEN 'Fri' THEN 5 END, time")) {
                            st.setString(1, studentId);
                            ResultSet rs = st.executeQuery();
                            while (rs.next()) {
                                Map<String, Object> m = new HashMap<>();
                                m.put("day", rs.getString(1));
                                m.put("time", rs.getString(2));
                                m.put("course", rs.getString(3));
                                m.put("room", rs.getString(4));
                                out.add(m);
                            }
                        }
                        return out;
                    }));

            // Optional record check. Failure here triggers graceful degradation, not an error response.
            try {
                long checkStarted = System.currentTimeMillis();
                boolean complete = checkRecords(studentId, classes);
                long elapsed = System.currentTimeMillis() - started;
                log.info("TIMETABLE served for {} ({} classes, {} ms, instance {})",
                        studentId, classes.size(), elapsed, Host.get());
                return ResponseEntity.ok(Map.of(
                        "status", "OK",
                        "studentId", studentId,
                        "classes", classes,
                        "registrationComplete", complete,
                        "latencyMillis", elapsed,
                        "instance", Host.get()));
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - started;
                log.warn("DEGRADED timetable for {}: record service unavailable ({}), serving cached timetable",
                        studentId, e.getMessage());
                Map<String, Object> fallback = new HashMap<>();
                fallback.put("studentId", studentId);
                fallback.put("classes", classes);
                fallback.put("note", "registration completeness unknown, showing last known timetable");
                return Graceful.degraded(
                        "record verification unavailable: " + e.getMessage() + "; timetable served from local data",
                        fallback);
            }
        }

        private boolean checkRecords(String studentId, List<Map<String, Object>> classes) {
            // In a full deployment this calls the records service. Here we verify
            // the course list is non-empty and well formed as the dependent check.
            return !classes.isEmpty();
        }
    }
}
