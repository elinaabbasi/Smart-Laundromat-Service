package com.smartlaundromat.presentation;

import java.security.Principal;
import java.util.Optional;

import com.smartlaundromat.business.ReservationService;
import com.smartlaundromat.model.Reservation;
import com.smartlaundromat.model.Reservation.Page;
import com.smartlaundromat.model.Reservation.Request;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reservations")
public class CustomerReservations {
    private final ReservationService reservations;

    public CustomerReservations(ReservationService reservations) {
        this.reservations = reservations;
    }

    @GetMapping
    public Page reservations(Principal customer) {
        return reservations.listForCustomer(customer.getName());
    }

    @PostMapping
    public ResponseEntity<?> createReservation(@RequestBody Request request, Principal customer) {
        Optional<Reservation> reservation = reservations.createReservation(customer.getName(), request);
        if (reservation.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.PAYMENT_REQUIRED, "Payment was declined. Try another payment method.");
            return ResponseEntity.of(problem).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(reservation.get());
    }
}
