package com.smartlaundromat.presentation;

import java.util.List;

import com.smartlaundromat.business.MachineService;
import com.smartlaundromat.model.Machine;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/machines")
public class Machines {
    private final MachineService machines;

    public Machines(MachineService machines) {
        this.machines = machines;
    }

    @GetMapping
    public List<Machine> getMachines(@RequestParam(required = false) String type,
            @RequestParam(defaultValue = "false") boolean availableOnly) {
        return machines.listMachines(type, availableOnly);
    }

    @GetMapping("/{id}")
    public Machine getMachine(@PathVariable long id) {
        return machines.getMachine(id);
    }
}
