package kz.aitu.ftr.records;

import kz.aitu.ftr.common.Backoff;
import kz.aitu.ftr.common.ChaosController;
import kz.aitu.ftr.common.DbRetry;
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
 * Academic records and transcript service.
 * Demonstrates retry with exponential backoff on database reads/writes and
 * serves transcript generation from replicated data.
 */
@SpringBootApplication(scanBasePackages = "kz.aitu.ftr")
public class RecordsApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecordsApplication.class, args);
    }

    @Bean
    public ReplicatedDatabase db(
            @Value("${db.primary-url}") String primaryUrl,
            @Value("${db.standby-url:}") String standbyUrl,
            @Value("${db.replication-enabled:true}") boolean replication,
            @Value("${spring.datasource.username:university}") String user,
            @Value("${spring.datasource.password:university}") String password) {
        ReplicatedDatabase db = new ReplicatedDatabase(primaryUrl, standbyUrl, user, password, replication);
        db.ensureSchema("CREATE TABLE IF NOT EXISTS courses (" +
                "id VARCHAR(32) PRIMARY KEY, student_id VARCHAR(32), course_code VARCHAR(20), " +
                "grade VARCHAR(4), credits INT)");
        return db;
    }

    @RestController
    @RequestMapping("/records")
    public static class RecordsController {

        private static final Logger log = LoggerFactory.getLogger(RecordsController.class);
        private static final int MAX_ATTEMPTS = 3;

        private final ReplicatedDatabase db;
        private final ChaosController chaos;

        public RecordsController(ReplicatedDatabase db, ChaosController chaos) {
            this.db = db;
            this.chaos = chaos;
        }

        @PostMapping
        public ResponseEntity<Map<String, Object>> addRecord(@RequestBody Map<String, String> body) {
            chaos.apply("addRecord");
            String studentId = body.getOrDefault("studentId", "S1");
            String course = body.getOrDefault("course", "CSE101");
            String grade = body.getOrDefault("grade", "A");
            int credits = Integer.parseInt(body.getOrDefault("credits", "3"));
            String id = studentId + "-" + course;

            DbRetry.withRetry("addRecord", MAX_ATTEMPTS, 300, () -> db.write(c -> {
                try (PreparedStatement st = c.prepareStatement(
                        "INSERT INTO courses (id, student_id, course_code, grade, credits) VALUES (?, ?, ?, ?, ?)" +
                                " ON CONFLICT (id) DO UPDATE SET grade = EXCLUDED.grade")) {
                    st.setString(1, id);
                    st.setString(2, studentId);
                    st.setString(3, course);
                    st.setString(4, grade);
                    st.setInt(5, credits);
                    st.executeUpdate();
                }
                return null;
            }));
            log.info("RECORD added {} {} {} on {}", studentId, course, grade, Host.get());
            return ResponseEntity.ok(Map.of(
                    "status", "RECORDED", "id", id, "instance", Host.get()));
        }

        /**
         * Transcript generation: reads all course records for a student.
         * Reads go through DbRetry, so transient database errors are retried
         * with exponential backoff before the request fails.
         */
        @GetMapping("/transcript/{studentId}")
        public ResponseEntity<Map<String, Object>> transcript(@PathVariable String studentId) {
            chaos.apply("transcript");
            long started = System.currentTimeMillis();
            List<Map<String, Object>> courses = DbRetry.withRetry("transcript", MAX_ATTEMPTS, 300, () ->
                    db.read(c -> {
                        List<Map<String, Object>> out = new ArrayList<>();
                        try (PreparedStatement st = c.prepareStatement(
                                "SELECT course_code, grade, credits FROM courses WHERE student_id = ? ORDER BY course_code")) {
                            st.setString(1, studentId);
                            ResultSet rs = st.executeQuery();
                            while (rs.next()) {
                                Map<String, Object> m = new HashMap<>();
                                m.put("course", rs.getString(1));
                                m.put("grade", rs.getString(2));
                                m.put("credits", rs.getInt(3));
                                out.add(m);
                            }
                        }
                        return out;
                    }));
            int totalCredits = 0;
            double weighted = 0;
            Map<String, Double> gpa = Map.of("A", 4.0, "B", 3.0, "C", 2.0, "D", 1.0, "F", 0.0);
            for (Map<String, Object> c : courses) {
                int cr = (Integer) c.get("credits");
                totalCredits += cr;
                weighted += gpa.getOrDefault(String.valueOf(c.get("grade")), 0.0) * cr;
            }
            double gpaValue = totalCredits == 0 ? 0 : weighted / totalCredits;
            long elapsed = System.currentTimeMillis() - started;
            log.info("TRANSCRIPT generated for {} ({} courses, {} ms, instance {})",
                    studentId, courses.size(), elapsed, Host.get());
            return ResponseEntity.ok(Map.of(
                    "studentId", studentId,
                    "courses", courses,
                    "totalCredits", totalCredits,
                    "gpa", Math.round(gpaValue * 100) / 100.0,
                    "latencyMillis", elapsed,
                    "instance", Host.get()));
        }
    }
}
