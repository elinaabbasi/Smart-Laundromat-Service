
package com.smartlaundromat;

import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/machines")
public class Machines {

    private final JdbcClient database;

    public Machines(JdbcClient database) {
        this.database = database;
    }

    // UC2: List machines with optional filters.
    @GetMapping
    public List<Machine> getMachines(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "false")
                    boolean availableOnly) {

        String machineType = null;

        if (type != null && !type.isBlank()) {
            machineType = type.strip()
                    .toUpperCase(Locale.ROOT);

            if (!machineType.equals("WASHER")
                    && !machineType.equals("DRYER")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Invalid machine type.");
            }
        }

        return database.sql("""
                SELECT id, name, type, status
                FROM machines
                WHERE (CAST(:type AS VARCHAR) IS NULL OR type = :type)      
                       AND (:availableOnly = FALSE
                       OR status = 'AVAILABLE')
                ORDER BY id
                """)
                .param("type", machineType)
                .param("availableOnly", availableOnly)
                .query((row, index) -> new Machine(
                        row.getLong("id"),
                        row.getString("name"),
                        row.getString("type"),
                        row.getString("status")))
                .list();
    }

    // UC2: Get the details of one machine.
    @GetMapping("/{id}")
    public Machine getMachine(@PathVariable long id) {

        return database.sql("""
                SELECT id, name, type, status
                FROM machines
                WHERE id = :id
                """)
                .param("id", id)
                .query((row, index) -> new Machine(
                        row.getLong("id"),
                        row.getString("name"),
                        row.getString("type"),
                        row.getString("status")))
                .optional()
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Machine not found."));
    }

    public record Machine(
            long id,
            String name,
            String type,
            String status
    ) {}
}
