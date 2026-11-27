# Smart Laundromat

A laundromat portal built with HTML, CSS, and JavaScript, with a Spring Boot API and a PostgreSQL database. Customers can book machines and view their reservations. Administrators manage machines and review reservations and payments.

## Run locally

Use Java 17 or later, Maven, and PostgreSQL. If you use Docker for PostgreSQL, open Docker Desktop before starting.

1. Start PostgreSQL from the project root.

   ```sh
   docker compose up -d db
   ```

   If you already have PostgreSQL, create a database and pass its connection settings to Spring Boot with `DB_URL`, `DB_USER`, and `DB_PASSWORD`. The Compose database and Spring Boot defaults match.

2. Start Spring Boot and leave it running.

   ```sh
   cd backend
   mvn spring-boot:run
   ```

3. Open [localhost:8080](http://localhost:8080). Spring Boot serves both the frontend and the API.

After saving edits to HTML, CSS, or JavaScript, refresh the browser to see them. Restart Spring Boot after editing Java code or application settings.

## Try the reservations page

A fresh account shows an empty list. Sign in, select an available machine, then choose a date, start time, and duration. Reservations cover one to eight consecutive 30-minute blocks, up to four hours. The confirmed booking appears in My Reservations.

Try booking an overlapping time on the same machine. The form reports that the machine is already reserved for that time. You can book the same time on another available machine or book the original machine starting when the first reservation ends.

If an existing database already has overlapping confirmed reservations for a machine, resolve those conflicts before starting the updated app. PostgreSQL validates existing reservations when it installs the constraint.

## Try a simulated payment

Copy a secret key from your [Stripe sandbox API keys](https://dashboard.stripe.com/apikeys) into the `STRIPE_TEST_SECRET_KEY` environment variable before starting Spring Boot. Use an `sk_test_` key or an `rk_test_` key with Customers and PaymentIntents write access.

Select a machine, choose a start time and duration, and enter made-up values in the four test payment fields. The total updates as you change the duration. Each 30-minute washer block costs $5.00 CAD and each dryer block costs $3.00 CAD. Select **Confirm Reservation** to process the test payment and book the time slot.

To test a decline, select **Simulate a declined payment** before submitting. The app records the failed attempt without booking the time slot. Clear the option and submit again to confirm the reservation. The database records the amount and outcome against the reservation or declined attempt.

The server creates and confirms a [Stripe PaymentIntent](https://docs.stripe.com/api/payment_intents/create) using a [test PaymentMethod](https://docs.stripe.com/testing). The sandbox transaction is linked to a Stripe Customer with the name and email from registration. The values entered in the payment fields stay in the browser. The app saves the Stripe PaymentIntent ID with the local payment when Stripe provides one.

## Try notifications

Notifications are messages stored in the app, in the `notifications` table. After a successful payment, the server saves a "Reservation confirmed" message for that customer, and it appears in the **Notifications** list on the customer dashboard (newest first, up to 20) without refreshing. Declined payments do not create a notification. Admin accounts do not have a Notifications section. Customers can only view their own notifications.


## Try the admin role

Both roles use the same sign-in form. Public registration creates a Customer account. To try the Admin dashboard, register a separate account and run this in your local PostgreSQL client. Replace the example email with that account’s email in lowercase.

```sql
UPDATE customers
SET role = 'ADMIN'
WHERE email = 'admin@example.com';
```

Sign in with that account to open the Admin dashboard. If it was already signed in, sign out and back in to load the new role.

Select **Add Machine** at the top of **Manage machines** to open a form at the end of the machine list. Enter a name, choose a type and status, then select **Add machine** on the card. To edit an existing machine, change its details and select **Save changes**. Deactivated machines stay visible to customers as **Unavailable**, with booking disabled, and the machine remains marked **Inactive** in the dashboard. Deactivating a machine cancels its confirmed reservations that haven't started yet, refunds their Stripe test payments, and sends each customer a notification. Reservations already in progress or in the past are kept. If Stripe can't complete a refund, the machine stays active and nothing is cancelled, so you can try again.

Select **Reactivate machine** on an inactive machine to restore its saved status in the customer list. Customers can book it when that status is **Available**.

The **Reservations** table shows customers, scheduled times, and reservation statuses. The **Payments** table shows each recorded attempt with its reservation ID, amount, currency, outcome, and timestamp. Reload the dashboard to see new activity.

## Try the analytics summary

Sign in as an Admin. The **Summary** at the top of the dashboard shows the number of machines, reservations, and completed payments. Declined checkouts are not counted as reservations. With no data, each count shows 0. Adding a machine updates the summary, and reloading the dashboard picks up new reservations and payments. The summary is also available as JSON from `GET /api/admin/summary`, which returns 403 for customer accounts.

## Run the tests

The automated tests are in `backend/src/test`. With the database running, run them from the `backend` folder:

```sh
mvn test
```

Each test rolls back its changes, so your data is unchanged afterwards. Stripe is faked, so no Stripe key is needed. See [TEST_RESULTS.md](TEST_RESULTS.md) for every test case and the latest results.

## How it works

### Architecture

Smart Laundromat is a modular monolith. One Spring Boot application serves the browser UI and REST API, with PostgreSQL for persistence and Stripe’s sandbox for payments. A single deployment keeps the browser and API on the same origin for session authentication. It also lets checkout, admin cancellation, and notifications coordinate their local database writes in Spring-managed transactions.

| Layer | Responsibility |
| --- | --- |
| Presentation | **Browser UI (view).** Spring Boot serves `index.html`, `styles.css`, and `app.js` from `backend/src/main/resources/static`. HTML structures the page, CSS styles it, and JavaScript handles interactions and calls the API. **Server-side presentation.** Controllers in `com.smartlaundromat.presentation` handle HTTP requests and responses. Spring Security manages sessions, role checks, and CSRF protection. |
| Business logic | Services in `com.smartlaundromat.business` handle registration, machine search, checkout, admin actions, and notification retrieval. Checkout validates and prices bookings, and admin deactivation coordinates cancellations and refunds. Application services define the transaction boundaries. |
| Data access and persistence | Repositories in `com.smartlaundromat.dataaccess` contain the SQL and map PostgreSQL rows to Java records. `schema.sql` defines tables and constraints, including the rule against overlapping confirmed bookings. The repositories also implement the row locks used during checkout. |
| External service communication | `StripePayments` in `com.smartlaundromat.externalservices` implements the `PaymentGateway` interface. It owns the HTTP requests, Stripe authentication, and interpretation of provider responses for customer creation, charges, and refunds. |

For example, when a customer books a machine, the reservation controller passes the request and the signed-in email to `ReservationService`. The service validates and prices the booking, claims the slot through a repository, and calls `PaymentGateway`. It records the payment outcome and writes a confirmation notification on success. The controller turns the outcome into an HTTP response.

### Access and data

- Spring Security uses PBKDF2 password hashes and loads the saved role at sign-in. The browser sends the session cookie with each request. Signing out ends the session.
- The admin dashboard and machine management endpoints require the Admin role. Customer reservation and notification endpoints require the Customer role and use the signed-in email to scope the results.
- A reservation and its notifications are linked to the customer through `customer_id`. Checkout saves the confirmation notification in the same PostgreSQL transaction as the reservation and payment record.

