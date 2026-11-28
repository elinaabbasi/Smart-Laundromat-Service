# Test Cases and Results

Automated tests for the Smart Laundromat API, covering every use case built so far (UC1, UC2, UC3, UC4, UC6, UC8 and UC9). UC5 (status simulation) and UC10 (AI search) aren't built yet, so they have no dedicated tests.

**Initial test run:** November 21, 2026 · branch `testing`

**Latest UC1 test run:** November 28, 2026

**UC1 result:** 13 tests, 13 passed, 0 failed

**Combined latest recorded results:** 57 tests, 57 passed, 0 failed

## How the tests work

- The tests are in `backend/src/test/java/com/smartlaundromat`, one class per use case. They use JUnit 5, Spring Boot's test support and MockMvc, which sends real HTTP requests to the API without starting a server.
- Each test runs inside a database transaction that is rolled back when the test ends, so the tests leave no data behind.
- Stripe is replaced with a fake that approves `pm_card_visa` and declines `pm_card_visa_chargeDeclined`, and accepts refunds, so the tests need no Stripe key or internet connection. Everything else, including security rules and SQL, is the real application code.
- Test environment: Java 21, Maven 3.10, PostgreSQL 15.

## Summary

| Use case | Tests | Passed | Failed | Last tested |
|---|---|---|---|---|
| UC1: Customer registration and sign-in | 13 | 13 | 0 | Nov 28, 2026 |
| UC2: Search and select a machine | 7 | 7 | 0 | Nov 21, 2026 |
| UC3: Schedule and reserve a machine | 12 | 12 | 0 | Nov 21, 2026 |
| UC4: Payment | 7 | 7 | 0 | Nov 21, 2026 |
| UC6: Notifications | 4 | 4 | 0 | Nov 21, 2026 |
| UC8: Administration | 10 | 10 | 0 | Nov 21, 2026 |
| UC9: Analytics | 4 | 4 | 0 | Nov 21, 2026 |
| **Total** | **57** | **57** | **0** | |

## Test cases

### UC1: Customer registration and sign-in

| ID | Test case (expected behaviour) | Result |
|---|---|---|
| UC1-01 | Registering with valid details creates a customer account with a hashed password | Pass |
| UC1-02 | Registering with an email that is already used returns 409 | Pass |
| UC1-03 | Registering with a blank name, invalid email or short password returns 400 | Pass |
| UC1-04 | Signing in with the correct email and password starts a session with the customer role | Pass |
| UC1-05 | Signing in with a wrong password or unknown email returns 401 | Pass |
| UC1-06 | Signing out ends the session | Pass |
| UC1-07 | Customer pages return 401 when nobody is signed in | Pass |
| UC1-08 | A signed-in customer can view their own confirmed reservations | Pass |
| UC1-09 | Customers can only view their own reservations and cannot see another customer's reservations | Pass |
| UC1-10 | Accessing protected pages after signing out returns 401 | Pass |
| UC1-11 | Passwords between 8 and 128 characters are accepted, while passwords outside these boundaries return 400 | Pass |
| UC1-12 | A newly registered customer can sign in immediately and access their empty reservation list | Pass |
| UC1-13 | Registering without a CSRF token returns 403 | Pass |

**Test execution results (November 28, 2026):**

- Tests run: 13
- Passed: 13
- Failures: 0
- Errors: 0
- Skipped: 0
- Execution time: 3.920 seconds
- Build result: SUCCESS

### UC2: Search and select a machine

| ID | Test case (expected behaviour) | Result |
|---|---|---|
| UC2-01 | Browsing machines requires signing in: 401 when signed out, 200 when signed in | Pass |
| UC2-02 | The machine list shows each machine's name, type and status | Pass |
| UC2-03 | Filtering by type only returns machines of that type | Pass |
| UC2-04 | Filtering by availability hides machines that are out of service or deactivated | Pass |
| UC2-05 | Filtering by a type that doesn't exist returns 400 | Pass |
| UC2-06 | Selecting a machine returns its details, and a deactivated machine shows as unavailable | Pass |
| UC2-07 | Selecting a machine that doesn't exist returns 404 | Pass |

### UC3: Schedule and reserve a machine

| ID | Test case (expected behaviour) | Result |
|---|---|---|
| UC3-01 | A valid one-hour reservation is confirmed and priced at two 30-minute blocks | Pass |
| UC3-02 | A reservation that starts in the past returns 400 | Pass |
| UC3-03 | A reservation can be made more than 14 days ahead | Pass |
| UC3-04 | A reservation whose length isn't a multiple of 30 minutes returns 400 | Pass |
| UC3-05 | A reservation can start at any minute (e.g. 10:10), as long as its length is in 30-minute blocks | Pass |
| UC3-06 | A reservation longer than four hours returns 400, and exactly four hours is allowed | Pass |
| UC3-07 | A reservation that ends before it starts, or is missing fields, returns 400 | Pass |
| UC3-08 | Booking a time that overlaps another reservation on the same machine returns 409 | Pass |
| UC3-09 | Booking right after another reservation ends, or the same time on another machine, is allowed | Pass |
| UC3-10 | Booking a machine that is out of service or deactivated returns 409 | Pass |
| UC3-11 | Booking a machine that doesn't exist returns 404 | Pass |
| UC3-12 | Only signed-in customers can reserve: 401 when signed out, 403 for admins | Pass |

### UC4: Payment

| ID | Test case (expected behaviour) | Result |
|---|---|---|
| UC4-01 | A successful payment is recorded as SUCCEEDED with its amount and Stripe reference | Pass |
| UC4-02 | The price depends on the machine type: washers cost $5.00 and dryers $3.00 per block | Pass |
| UC4-03 | A declined card returns 402 and is recorded as FAILED | Pass |
| UC4-04 | After a declined card, the customer can retry and book the same time slot | Pass |
| UC4-05 | A declined reservation doesn't appear in the customer's reservations | Pass |
| UC4-06 | If Stripe can't be reached, the customer gets an error instead of a booking | Pass |
| UC4-07 | Customers only see their own reservations and payments | Pass |

### UC6: Notifications

| ID | Test case (expected behaviour) | Result |
|---|---|---|
| UC6-01 | A paid and confirmed reservation creates a confirmation notification | Pass |
| UC6-02 | A declined payment doesn't create a notification | Pass |
| UC6-03 | Customers only see their own notifications | Pass |
| UC6-04 | Notifications return 401 when signed out and 403 for admins | Pass |

### UC8: Administration

| ID | Test case (expected behaviour) | Result |
|---|---|---|
| UC8-01 | The admin dashboard shows machines, reservations and payments | Pass |
| UC8-02 | Customers get 403 on admin pages, and signed-out users get 401 | Pass |
| UC8-03 | An admin can add a machine | Pass |
| UC8-04 | Adding a machine with a name that is already used returns 409 | Pass |
| UC8-05 | Adding a machine with a blank name, unknown type or unknown status returns 400 | Pass |
| UC8-06 | An admin can rename a machine and change its status | Pass |
| UC8-07 | Updating a machine that doesn't exist returns 404, and renaming to a used name returns 409 | Pass |
| UC8-08 | A deactivated machine can't be booked, and reactivating it makes it bookable again | Pass |
| UC8-09 | Deactivating a machine cancels and refunds its future reservations and notifies the customer | Pass |
| UC8-10 | Deactivating a machine keeps reservations that have already started | Pass |

### UC9: Analytics

| ID | Test case (expected behaviour) | Result |
|---|---|---|
| UC9-01 | The summary counts machines, reservations and completed payments | Pass |
| UC9-02 | A declined payment isn't counted as a reservation or a completed payment | Pass |
| UC9-03 | With no data, every count is 0 | Pass |
| UC9-04 | Customers get 403 on the summary, and signed-out users get 401 | Pass |

## Differences from the Milestone 1 design document

Three tests check behaviour that differs from what the Milestone 1 design document described. The application's behaviour is the intended one, and the design document will be updated to match.

| ID | Design document | Application |
|---|---|---|
| UC2-01 | Browsing machines is public | Customers sign in before browsing machines |
| UC3-03 | Machines are bookable up to 14 days ahead | There is no limit on how far ahead a machine can be booked |
| UC3-05 | Reservations are aligned to 30-minute blocks | A reservation can start at any minute; its length must be in 30-minute blocks |

## Running the tests

1. Start the database the same way as for the app (`docker compose up -d db`), or point the tests at another PostgreSQL database with `DB_URL`, `DB_USER` and `DB_PASSWORD`.

2. From the `backend` folder, run the full test suite:

   ```sh
   mvn test
   ```

3. To run the tests for a specific use case, specify its test class. For example, to run UC1:

   ```sh
   mvn -Dtest=CustomerAccountsTest test
   ```

4. To run an individual test method, specify the class and method name:

   ```sh
   mvn -Dtest=CustomerAccountsTest#customerCanViewOwnReservations test
   ```

Maven prints a pass/fail count for each test class. Detailed reports are written to `backend/target/surefire-reports`.