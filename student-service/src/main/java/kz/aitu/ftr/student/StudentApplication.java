package kz.aitu.ftr.student;

import kz.aitu.ftr.common.ChaosController;
import kz.aitu.ftr.common.Host;
import kz.aitu.ftr.common.ReplicatedDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootApplication(scanBasePackages = "kz.aitu.ftr")
public class StudentApplication {

    public static void main(String[] args) {
        SpringApplication.run(StudentApplication.class, args);
    }

    @Bean
    public ReplicatedDatabase db(
            @Value("${db.primary-url}") String primaryUrl,
            @Value("${db.standby-url:}") String standbyUrl,
            @Value("${db.replication-enabled:true}") boolean replication,
            @Value("${spring.datasource.username:university}") String user,
            @Value("${spring.datasource.password:university}") String password) {
        ReplicatedDatabase db = new ReplicatedDatabase(primaryUrl, standbyUrl, user, password, replication);
        db.ensureSchema("CREATE TABLE IF NOT EXISTS students (" +
                "id VARCHAR(32) PRIMARY KEY, name VARCHAR(200), program VARCHAR(100), semester INT)");
        return db;
    }

    @RestController
    @RequestMapping("/students")
    public static class StudentController {

        private static final Logger log = LoggerFactory.getLogger(StudentController.class);

        private final ReplicatedDatabase db;
        private final ChaosController chaos;

        public StudentController(ReplicatedDatabase db, ChaosController chaos) {
            this.db = db;
            this.chaos = chaos;
        }

        @PostMapping
        public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body) {
            chaos.apply("register");
            String id = body.getOrDefault("id", "S" + System.currentTimeMillis());
            String name = body.getOrDefault("name", "Unknown");
            String program = body.getOrDefault("program", "BSc");
            int semester = Integer.parseInt(body.getOrDefault("semester", "1"));

            db.write(c -> {
                try (PreparedStatement st = c.prepareStatement(
                        "INSERT INTO students (id, name, program, semester) VALUES (?, ?, ?, ?)" +
                                " ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name")) {
                    st.setString(1, id);
                    st.setString(2, name);
                    st.setString(3, program);
                    st.setInt(4, semester);
                    st.executeUpdate();
                }
                return null;
            });
            log.info("STUDENT registered {} on {}", id, Host.get());
            return ResponseEntity.ok(Map.of(
                    "status", "REGISTERED", "id", id, "instance", Host.get()));
        }

        @GetMapping("/{id}")
        public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
            chaos.apply("get");
            Map<String, Object> student = db.read(c -> {
                try (PreparedStatement st = c.prepareStatement(
                        "SELECT id, name, program, semester FROM students WHERE id = ?")) {
                    st.setString(1, id);
                    ResultSet rs = st.executeQuery();
                    if (rs.next()) {
                        Map<String, Object> m = new HashMap<>();
                        m.put("id", rs.getString(1));
                        m.put("name", rs.getString(2));
                        m.put("program", rs.getString(3));
                        m.put("semester", rs.getInt(4));
                        return m;
                    }
                    return null;
                }
            });
            if (student == null) {
                return ResponseEntity.status(404).body(Map.of("error", "student_not_found", "id", id));
            }
            student.put("instance", Host.get());
            return ResponseEntity.ok(student);
        }

        @GetMapping
        public ResponseEntity<Map<String, Object>> list() {
            chaos.apply("list");
            List<Map<String, Object>> students = db.read(c -> {
                List<Map<String, Object>> out = new ArrayList<>();
                try (PreparedStatement st = c.prepareStatement(
                        "SELECT id, name, program, semester FROM students ORDER BY id LIMIT 100");
                     ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new HashMap<>();
                        m.put("id", rs.getString(1));
                        m.put("name", rs.getString(2));
                        m.put("program", rs.getString(3));
                        m.put("semester", rs.getInt(4));
                        out.add(m);
                    }
                }
                return out;
            });
            return ResponseEntity.ok(Map.of("count", students.size(), "students", students, "instance", Host.get()));
        }
    }
}
