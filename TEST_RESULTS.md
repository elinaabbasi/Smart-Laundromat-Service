# Test Cases and Results

Automated tests for the Smart Laundromat API, designed to verify functional requirements, input validation, security, and system reliability.

**Overall result:** 98 tests, 98 passed, 0 failures, 0 errors, 0 skipped.

**Build status:** SUCCESS

**Execution time:** 11.081 seconds

## How the tests work

- The tests are in `backend/src/test/java/com/smartlaundromat`, one class per use case. They use JUnit 5, Spring Boot's test support and MockMvc, which simulates HTTP requests to the API without starting an HTTP server.

- Each test runs inside a database transaction that is rolled back when the test ends, so the tests leave no data behind.

- The mocked Stripe payment gateway approves `pm_card_visa` declines `pm_card_visa_chargeDeclined`, and accepts refunds, so the tests need no Stripe key or internet connection. The tests exercise application logic, security rules and SQL against PostgreSQL.

- Test environment: Java 21, Maven 3.10, PostgreSQL 15.

## Test Summary

| Use Case | Test Class | Tests | Passed | Failed |
|---|---|---:|---:|---:|
| UC1 – Customer Accounts | CustomerAccountsTest | 13 | 13 | 0 |
| UC2 – Search and Select a Machine | MachinesTest | 15 | 15 | 0 |
| UC3 – Reservations | ReservationsTest | 18 | 18 | 0 |
| UC4 – Payments | PaymentsTest | 14 | 14 | 0 |
| UC5 – Machine Status Simulation | MachineStatusTest | 20 | 20 | 0 |
| UC6 – Notifications | NotificationsTest | 4 | 4 | 0 |
| UC8 – Administration | AdminDashboardTest | 10 | 10 | 0 |
| UC9 – Analytics | AnalyticsSummaryTest | 4 | 4 | 0 |
| **Total** | | **98** | **98** | **0** |

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
| UC2-08 | Combined type and availability filtering: only available machines of the requested type are returned | Pass |
| UC2-09 | Availability filtering across all machine statuses: reserved, in-use, out-of-service and deactivated machines are excluded | Pass |
| UC2-10 | Display all machine statuses without availability filtering: all machines are returned with their correct statuses, including deactivated machines as UNAVAILABLE | Pass |
| UC2-11 | No machines match the search criteria: returns 200 OK with an empty array | Pass |
| UC2-12 | Case-insensitive and whitespace-tolerant type filtering: machine type is correctly normalized before filtering | Pass |
| UC2-13 | Missing, empty or blank machine type filter: machines of all types are returned | Pass |
| UC2-14 | Authentication required for individual machine selection: unauthenticated request returns 401; authenticated request returns machine details | Pass |
| UC2-15 | Invalid query parameters and machine ID formats: malformed inputs return 400; nonexistent numeric IDs return 404 | Pass |

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
| UC3-13 | Malformed reservation JSON: returns 400 with structured problem details | Pass |
| UC3-14 | Identical reservation interval: returns 409 Conflict | Pass |
| UC3-15 | Reservation overlaps the beginning of an existing reservation: returns 409 Conflict | Pass |
| UC3-16 | Reservation completely inside an existing reservation: returns 409 Conflict | Pass |
| UC3-17 | Reservation completely surrounds an existing reservation: returns 409 Conflict | Pass |
| UC3-18 | Reservation ends exactly when another reservation starts: returns 201 Created with confirmed status | Pass |

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
| UC4-08 | Unsupported payment method: returns 400 without triggering payment | Pass |
| UC4-09 | Missing, empty or whitespace-only payment method: returns 400 without triggering payment | Pass |
| UC4-10 | Overlapping reservation payment prevention: only the valid reservation triggers payment | Pass |
| UC4-11 | Invalid reservation payment prevention: invalid reservations do not trigger payment | Pass |
| UC4-12 | Stripe provider failure: returns 502 and no payment record is created | Pass |
| UC4-13 | Payment records after retry: both failed and successful payment attempts are preserved | Pass |
| UC4-14 | Maximum-duration pricing: washer costs $40 and dryer costs $24; API, database and gateway amounts match | Pass |

### UC5: Machine Status Simulation

| ID | Test case (expected behaviour) | Result |
|---|---|---|
| UC5-01 | An admin can change an available machine to reserved; the API and database reflect the new status | Pass |
| UC5-02 | An admin can change a reserved machine to in use | Pass |
| UC5-03 | An admin can return an in-use machine to available | Pass |
| UC5-04 | An admin can mark an available machine as out of service | Pass |
| UC5-05 | An admin can restore an out-of-service machine to available | Pass |
| UC5-06 | Invalid, empty, lowercase and whitespace-only statuses return 400 and leave the status unchanged | Pass |
| UC5-07 | Customers attempting to change machine status receive 403; status remains unchanged | Pass |
| UC5-08 | Signed-out users attempting to change machine status receive 401; status remains unchanged | Pass |
| UC5-09 | Booking a reserved machine returns 409 without triggering payment | Pass |
| UC5-10 | Booking an in-use machine returns 409 without triggering payment | Pass |
| UC5-11 | Booking an out-of-service machine returns 409 without triggering payment | Pass |
| UC5-12 | Restoring available status allows a previously rejected booking to succeed with 201 | Pass |
| UC5-13 | Updating a nonexistent machine returns 404 | Pass |
| UC5-14 | Repeatedly saving the same status succeeds without changing that status | Pass |
| UC5-15 | Missing and null statuses return 400 and leave the status unchanged | Pass |
| UC5-16 | Manual status changes preserve an existing confirmed reservation and successful payment without triggering a refund; the reservation remains visible to the customer | Pass |
| UC5-17 | Changing one machine status leaves other machines unchanged | Pass |
| UC5-18 | Availability filtering excludes reserved, in-use and out-of-service machines and includes them again when restored to available | Pass |
| UC5-19 | Updating machine status without a CSRF token returns 403 and leaves the status unchanged | Pass |
| UC5-20 | An admin can update a dryer status; the API and database reflect the change | Pass |

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

## Testing limitations

- Payment gateway responses are mocked; live Stripe sandbox transactions are not verified by this suite.

- Reservation overlap tests use sequential requests; simultaneous requests are not independently tested.

- Machine status changes are manual; the suite verifies reservation preservation but does not verify physical usage against reservation schedules.

- Browser-based end-to-end testing is outside the scope of this API test suite.

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
