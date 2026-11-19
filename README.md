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

## Try the reservations page

A fresh account shows an empty list. Booking creation is outside this feature. To see an example entry, register first, then run this SQL in your local PostgreSQL client. Replace the example email with your registered email in lowercase.

```sql
INSERT INTO reservations (customer_id, machine, starts_at, ends_at)
SELECT id, 'Washer 03', now() + interval '1 day', now() + interval '1 day 45 minutes'
FROM customers
WHERE email = 'you@example.com';
```

Refresh the page to see the booking. Sign in with another account to confirm that it has its own reservation list.

## How it works

- The frontend files are in `backend/src/main/resources/static`. `index.html` contains the forms and reservation layout, `styles.css` sets the appearance, and `app.js` connects the forms to the API.
- Spring Boot creates the `customers` and `reservations` tables on startup. A reservation belongs to one customer through `customer_id`.
- Spring Security hashes passwords with PBKDF2 and keeps the signed-in customer in a server-side session. The browser sends the session cookie with each request.
- The reservations endpoint reads the customer email from the signed-in session and uses it to filter the database query. Signing out ends the session.
