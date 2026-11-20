# Smart Laundromat

A small customer portal built with HTML, CSS, and JavaScript, with a Spring Boot API and a PostgreSQL database. Customers register, sign in with their email and password, view their own reservations, and sign out.

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

## Try the admin role

Both roles use the same sign-in form. Public registration creates a Customer account. To try the Admin dashboard, register a separate account and run this in your local PostgreSQL client. Replace the example email with that account’s email in lowercase.

```sql
UPDATE customers
SET role = 'ADMIN'
WHERE email = 'admin@example.com';
```

Sign in with that account to open the Admin dashboard. If it was already signed in, sign out and back in to load the new role.

## How it works

- The frontend files are in `backend/src/main/resources/static`. `index.html` contains the forms and reservation layout, `styles.css` sets the appearance, and `app.js` connects the forms to the API.
- Spring Boot creates the `customers` and `reservations` tables on startup. A reservation belongs to one customer through `customer_id`.
- Spring Security hashes passwords with PBKDF2 and loads the saved role into the session at sign-in. The browser sends the session cookie with each request.
- The Admin endpoint requires the Admin role. The reservations endpoint requires the Customer role and uses the signed-in email to return that customer’s reservations. Signing out ends the session.
