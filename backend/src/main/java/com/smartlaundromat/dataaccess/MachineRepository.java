package com.smartlaundromat.dataaccess;

import java.util.List;
import java.util.Optional;

import com.smartlaundromat.model.Machine;
import com.smartlaundromat.model.Machine.Details;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MachineRepository {
    private final JdbcClient database;

    public MachineRepository(JdbcClient database) {
        this.database = database;
    }

    public List<Machine> findMachines(String type, boolean availableOnly) {
        return database.sql("""
                SELECT id, name, type,
                       CASE WHEN active THEN status ELSE 'UNAVAILABLE' END AS status
                FROM machines
                WHERE (CAST(:type AS VARCHAR) IS NULL OR type = :type)
                  AND (:availableOnly = FALSE OR (active AND status = 'AVAILABLE'))
                ORDER BY id
                """)
                .param("type", type)
                .param("availableOnly", availableOnly)
                .query(Machine.class)
                .list();
    }

    public Optional<Machine> findMachine(long id) {
        return database.sql("""
                SELECT id, name, type,
                       CASE WHEN active THEN status ELSE 'UNAVAILABLE' END AS status
                FROM machines
                WHERE id = :id
                """)
                .param("id", id)
                .query(Machine.class)
                .optional();
    }

    public Optional<Details> lockForCheckout(long id) {
        return database.sql("""
                SELECT id, name, type, status, active
                FROM machines
                WHERE id = :id
                FOR SHARE
                """)
                .param("id", id)
                .query(Details.class)
                .optional();
    }

    public List<Details> findAllForAdmin() {
        return database.sql("""
                SELECT id, name, type, status, active
                FROM machines
                ORDER BY id
                """)
                .query(Details.class)
                .list();
    }

    public Details add(String name, String type, String status) {
        return database.sql("""
                INSERT INTO machines (name, type, status)
                VALUES (:name, :type, :status)
                RETURNING id, name, type, status, active
                """)
                .param("name", name)
                .param("type", type)
                .param("status", status)
                .query(Details.class)
                .single();
    }

    public Optional<Details> update(long id, String name, String type, String status) {
        return database.sql("""
                UPDATE machines SET name = :name, type = :type, status = :status
                WHERE id = :id
                RETURNING id, name, type, status, active
                """)
                .param("id", id)
                .param("name", name)
                .param("type", type)
                .param("status", status)
                .query(Details.class)
                .optional();
    }

    public Optional<Details> setActive(long id, boolean active) {
        return database.sql("""
                UPDATE machines SET active = :active
                WHERE id = :id
                RETURNING id, name, type, status, active
                """)
                .param("id", id)
                .param("active", active)
                .query(Details.class)
                .optional();
    }
}
