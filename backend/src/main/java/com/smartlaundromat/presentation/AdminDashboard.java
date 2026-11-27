package com.smartlaundromat.presentation;

import java.security.Principal;

import com.smartlaundromat.business.AdminService;
import com.smartlaundromat.model.AdminDashboardView;
import com.smartlaundromat.model.AdminDashboardView.Summary;
import com.smartlaundromat.model.Machine.Details;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminDashboard {
    private final AdminService admin;

    public AdminDashboard(AdminService admin) {
        this.admin = admin;
    }

    @GetMapping
    public AdminDashboardView dashboard(Principal account) {
        return admin.dashboard(account.getName());
    }

    @GetMapping("/summary")
    public Summary summary() {
        return admin.summary();
    }

    @PostMapping("/machines")
    @ResponseStatus(HttpStatus.CREATED)
    public Details addMachine(@Valid @RequestBody MachineRequest request) {
        return admin.addMachine(request.name(), request.type(), request.status());
    }

    @PutMapping("/machines/{id}")
    public Details updateMachine(@PathVariable long id, @Valid @RequestBody MachineRequest request) {
        return admin.updateMachine(id, request.name(), request.type(), request.status());
    }

    @PostMapping("/machines/{id}/deactivate")
    public Details deactivateMachine(@PathVariable long id) {
        return admin.deactivateMachine(id);
    }

    @PostMapping("/machines/{id}/reactivate")
    public Details reactivateMachine(@PathVariable long id) {
        return admin.reactivateMachine(id);
    }

    public record MachineRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = "WASHER|DRYER", message = "Choose WASHER or DRYER.") String type,
            @NotNull @Pattern(regexp = "AVAILABLE|RESERVED|IN_USE|OUT_OF_SERVICE",
                    message = "Choose AVAILABLE, RESERVED, IN_USE, or OUT_OF_SERVICE.") String status) {}
}
