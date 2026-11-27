package com.smartlaundromat.business;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.smartlaundromat.business.ApplicationException.Reason;
import com.smartlaundromat.model.Machine.Details;
import com.smartlaundromat.model.Reservation;
import com.smartlaundromat.model.Reservation.Page;
import com.smartlaundromat.model.Reservation.Payment;
import com.smartlaundromat.model.Reservation.PaymentStatus;
import com.smartlaundromat.model.Reservation.Pricing;
import com.smartlaundromat.model.Reservation.Request;
import com.smartlaundromat.dataaccess.AccountRepository;
import com.smartlaundromat.dataaccess.AccountRepository.CheckoutCustomer;
import com.smartlaundromat.dataaccess.MachineRepository;
import com.smartlaundromat.dataaccess.NotificationRepository;
import com.smartlaundromat.dataaccess.ReservationRepository;
import com.smartlaundromat.dataaccess.ReservationRepository.ReservedSlot;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {
    private static final Pricing PRICING = new Pricing(
            "CAD", 30, Map.of("WASHER", 500, "DRYER", 300), 8);

    private final AccountRepository accounts;
    private final MachineRepository machines;
    private final ReservationRepository reservations;
    private final NotificationRepository notifications;
    private final PaymentGateway payments;

    public ReservationService(AccountRepository accounts, MachineRepository machines,
            ReservationRepository reservations, NotificationRepository notifications, PaymentGateway payments) {
        this.accounts = accounts;
        this.machines = machines;
        this.reservations = reservations;
        this.notifications = notifications;
        this.payments = payments;
    }

    public Page listForCustomer(String email) {
        Payment unpaid = new Payment(0, PRICING.currency(), PaymentStatus.UNPAID);
        return new Page(accounts.getName(email), reservations.findForCustomer(email, unpaid), PRICING);
    }

    @Transactional
    public Optional<Reservation> createReservation(String email, Request request) {
        int blocks = validateReservation(request);

        // Lock the account and machine until checkout finishes.
        CheckoutCustomer account = accounts.lockForCheckout(email);
        Details machine = machines.lockForCheckout(request.machineId())
                .orElseThrow(MachineService::machineNotFound);
        if (!machine.active() || !machine.status().equals("AVAILABLE")) {
            throw new ApplicationException(Reason.CONFLICT, "This machine is currently unavailable.");
        }

        int amountCents = blocks * PRICING.blockRatesCents().get(machine.type());
        String currency = PRICING.currency();

        // Claim the time slot before charging. The database rejects overlapping bookings.
        ReservedSlot slot = reservations.claimSlot(account.id(), machine.id(), machine.name(),
                        request.startsAt().atOffset(ZoneOffset.UTC), request.endsAt().atOffset(ZoneOffset.UTC))
                .orElseThrow(() -> new ApplicationException(
                        Reason.CONFLICT, "This machine is already booked for the selected time."));

        String stripeCustomerId = getStripeCustomerId(account);

        // Retrying the same checkout after a timeout reuses the same Stripe request.
        String checkout = "laundromat:" + account.id() + ":" + machine.id() + ":"
                + slot.startsAt().toInstant() + ":" + slot.endsAt().toInstant() + ":"
                + amountCents + ":" + currency + ":" + request.testPaymentMethod();
        PaymentGateway.Result result = payments.pay(
                request.testPaymentMethod(), amountCents, currency, idempotencyKey(checkout), stripeCustomerId);
        Payment payment = new Payment(amountCents, currency,
                result.paid() ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED);
        reservations.recordPayment(slot.id(), payment, result.intentId());
        if (!result.paid()) {
            reservations.markPaymentFailed(slot.id());
            // Return normally so the transaction commits the failed payment attempt.
            return Optional.empty();
        }

        notifications.save(account.id(), "Reservation confirmed: " + slot.machine()
                + " (reservation #" + slot.id() + ").");
        return Optional.of(new Reservation(
                slot.id(), slot.machine(), slot.startsAt(), slot.endsAt(), slot.status(), payment));
    }

    private static int validateReservation(Request request) {
        if (request.machineId() == null || request.startsAt() == null || request.endsAt() == null) {
            throw new ApplicationException(Reason.INVALID_INPUT, "Machine and reservation times are required.");
        }
        if (!request.startsAt().isAfter(Instant.now()) || !request.endsAt().isAfter(request.startsAt())) {
            throw new ApplicationException(Reason.INVALID_INPUT, "Choose a future start time and a valid end time.");
        }

        long blockSeconds = PRICING.blockMinutes() * 60L;
        Duration duration = Duration.between(request.startsAt(), request.endsAt());
        if (duration.getNano() != 0 || duration.getSeconds() % blockSeconds != 0) {
            throw new ApplicationException(Reason.INVALID_INPUT, "Choose a duration in 30-minute increments.");
        }
        long blocks = duration.getSeconds() / blockSeconds;
        if (blocks > PRICING.maxBlocks()) {
            throw new ApplicationException(Reason.INVALID_INPUT, "Reservations can be up to four hours.");
        }
        if (!"pm_card_visa".equals(request.testPaymentMethod())
                && !"pm_card_visa_chargeDeclined".equals(request.testPaymentMethod())) {
            throw new ApplicationException(Reason.INVALID_INPUT, "Choose a Stripe test payment method.");
        }
        return (int) blocks;
    }

    private String getStripeCustomerId(CheckoutCustomer account) {
        if (account.stripeTestCustomerId() != null) {
            return account.stripeTestCustomerId();
        }

        String customerKey = "laundromat-customer:" + account.id() + ":" + account.email();
        String stripeCustomerId = payments.createCustomer(account.name(), account.email(), idempotencyKey(customerKey));
        accounts.saveStripeCustomerId(account.id(), stripeCustomerId);
        return stripeCustomerId;
    }

    private static String idempotencyKey(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
