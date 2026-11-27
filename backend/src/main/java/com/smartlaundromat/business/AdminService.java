package com.smartlaundromat.business;

import com.smartlaundromat.business.ApplicationException.Reason;
import com.smartlaundromat.model.AdminDashboardView;
import com.smartlaundromat.model.AdminDashboardView.Summary;
import com.smartlaundromat.model.Machine.Details;
import com.smartlaundromat.dataaccess.AccountRepository;
import com.smartlaundromat.dataaccess.AdminRepository;
import com.smartlaundromat.dataaccess.MachineRepository;
import com.smartlaundromat.dataaccess.NotificationRepository;
import com.smartlaundromat.dataaccess.ReservationRepository;
import com.smartlaundromat.dataaccess.ReservationRepository.CancelledReservation;
import com.smartlaundromat.dataaccess.ReservationRepository.PaidPayment;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {
    private final AccountRepository accounts;
    private final AdminRepository admin;
    private final MachineRepository machines;
    private final ReservationRepository reservations;
    private final NotificationRepository notifications;
    private final PaymentGateway payments;

    public AdminService(AccountRepository accounts, AdminRepository admin, MachineRepository machines,
            ReservationRepository reservations, NotificationRepository notifications, PaymentGateway payments) {
        this.accounts = accounts;
        this.admin = admin;
        this.machines = machines;
        this.reservations = reservations;
        this.notifications = notifications;
        this.payments = payments;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminDashboardView dashboard(String email) {
        return new AdminDashboardView(
                accounts.getName(email), machines.findAllForAdmin(), admin.findReservations(), admin.findPayments());
    }

    public Summary summary() {
        return admin.getSummary();
    }

    public Details addMachine(String name, String type, String status) {
        try {
            return machines.add(name.strip(), type, status);
        } catch (DuplicateKeyException exception) {
            throw duplicateMachineName();
        }
    }

    public Details updateMachine(long id, String name, String type, String status) {
        try {
            return machines.update(id, name.strip(), type, status).orElseThrow(MachineService::machineNotFound);
        } catch (DuplicateKeyException exception) {
            throw duplicateMachineName();
        }
    }

    @Transactional
    public Details deactivateMachine(long id) {
        Details machine = machines.setActive(id, false).orElseThrow(MachineService::machineNotFound);

        for (CancelledReservation reservation : reservations.cancelUpcomingForMachine(id)) {
            String message = "Reservation cancelled: " + reservation.machine()
                    + " (reservation #" + reservation.id() + ") because the machine is no longer available.";

            PaidPayment paid = reservations.findSuccessfulPayment(reservation.id()).orElse(null);
            if (paid != null) {
                // The key stops Stripe from refunding the same payment twice if the admin retries.
                payments.refund(paid.stripePaymentIntentId(), "refund-" + paid.stripePaymentIntentId());
                reservations.markPaymentRefunded(paid.id());
                message += " Your payment of $%d.%02d %s has been refunded."
                        .formatted(paid.amountCents() / 100, paid.amountCents() % 100, paid.currency());
            }

            notifications.save(reservation.customerId(), message);
        }

        return machine;
    }

    public Details reactivateMachine(long id) {
        return machines.setActive(id, true).orElseThrow(MachineService::machineNotFound);
    }

    private static ApplicationException duplicateMachineName() {
        return new ApplicationException(Reason.CONFLICT, "A machine already uses that name.");
    }
}
