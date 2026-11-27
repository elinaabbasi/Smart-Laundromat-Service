package com.smartlaundromat.business;

import java.util.List;
import java.util.Locale;

import com.smartlaundromat.business.ApplicationException.Reason;
import com.smartlaundromat.model.Machine;
import com.smartlaundromat.dataaccess.MachineRepository;

import org.springframework.stereotype.Service;

@Service
public class MachineService {
    private final MachineRepository machines;

    public MachineService(MachineRepository machines) {
        this.machines = machines;
    }

    public List<Machine> listMachines(String type, boolean availableOnly) {
        if (type == null || type.isBlank()) {
            return machines.findMachines(null, availableOnly);
        }

        String machineType = type.strip().toUpperCase(Locale.ROOT);
        if (!machineType.equals("WASHER") && !machineType.equals("DRYER")) {
            throw new ApplicationException(Reason.INVALID_INPUT, "Invalid machine type.");
        }
        return machines.findMachines(machineType, availableOnly);
    }

    public Machine getMachine(long id) {
        return machines.findMachine(id).orElseThrow(MachineService::machineNotFound);
    }

    static ApplicationException machineNotFound() {
        return new ApplicationException(Reason.NOT_FOUND, "Machine not found.");
    }
}
