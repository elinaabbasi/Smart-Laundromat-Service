const get = (id) => document.getElementById(id);

const signInForm = get('sign-in-form');
const registerForm = get('register-form');

function formatLabel(value) {
  if (value === 'OUT_OF_SERVICE') {
    return 'Out of Service';
  }

  return value.replaceAll('_', ' ').toLowerCase()
    .replace(/\b[a-z]/g, (letter) => letter.toUpperCase());
}

function showMessage(id, message = '') {
  setMessage(get(id), message);
}

function setMessage(element, message = '') {
  element.textContent = message;
  element.hidden = !message;
}

function showSignIn(message = '') {
  get('loading').hidden = true;
  get('dashboard').hidden = true;
  get('account').hidden = false;
  get('register-panel').hidden = true;
  get('sign-in-panel').hidden = false;
  showMessage('sign-in-error');
  showMessage('sign-in-notice', message);
}

function formatPaymentAmount(payment) {
  return new Intl.NumberFormat('en-CA', {
    style: 'currency',
    currency: payment.currency
  }).format(payment.amountCents / 100);
}

function showReservationTime(row, reservation) {
  const start = new Date(reservation.startsAt);
  const end = new Date(reservation.endsAt);
  const startTime = row.querySelector('.start-time');
  const endTime = row.querySelector('.end-time');
  startTime.dateTime = reservation.startsAt;
  endTime.dateTime = reservation.endsAt;

  if (localDateValue(start) !== localDateValue(end)) {
    const dateOptions = { dateStyle: 'medium', timeStyle: 'short' };
    startTime.textContent = start.toLocaleString(undefined, dateOptions);
    endTime.textContent = end.toLocaleString(undefined, dateOptions);
    return;
  }

  const timeOptions = { hour: 'numeric', minute: '2-digit' };
  const sameOffset = start.getTimezoneOffset() === end.getTimezoneOffset();
  if (!sameOffset) timeOptions.timeZoneName = 'short';
  const startLabel = start.toLocaleTimeString('en-US', timeOptions).replace(/\s/g, ' ');
  const samePeriod = (start.getHours() < 12) === (end.getHours() < 12);
  startTime.textContent = sameOffset && samePeriod ? startLabel.replace(/ [AP]M$/, '') : startLabel;
  endTime.textContent = end.toLocaleTimeString('en-US', timeOptions).replace(/\s/g, ' ');

  const date = start.toLocaleDateString('en-US', { dateStyle: 'medium' });
  row.querySelector('.reservation-time').replaceChildren(date + ' • ', startTime, '–', endTime);
}

function showReservations(customer) {
  const list = get('reservation-list');
  const template = get('reservation-template');
  list.replaceChildren();

  for (const reservation of customer.reservations) {
    const row = template.content.cloneNode(true);
    row.querySelector('h3').textContent = reservation.machine;
    row.querySelector('.reservation-status').textContent = formatLabel(reservation.status);

    showReservationTime(row, reservation);
    if (reservation.payment?.status === 'SUCCEEDED') {
      const summary = row.querySelector('.payment-summary');
      summary.textContent = 'Payment completed · ' + formatPaymentAmount(reservation.payment);
      summary.hidden = false;
    }
    list.append(row);
  }

  get('empty-reservations').hidden = customer.reservations.length > 0;
}


// UC6: show customer's notifications
async function loadNotifications() {
  const list = get('notification-list');
  showMessage('notification-error');

  try {
    const response = await fetch('/api/notifications', { cache: 'no-store' });
    if (!response.ok) throw new Error();
    const notifications = await response.json();

    list.replaceChildren();
    for (const notification of notifications) {
      const item = document.createElement('li');

      const message = document.createElement('p');
      message.textContent = notification.message;

      const time = document.createElement('time');
      time.dateTime = notification.createdAt;
      time.textContent = new Date(notification.createdAt).toLocaleString(undefined, {
        dateStyle: 'medium',
        timeStyle: 'short'
      });

      item.append(message, time);
      list.append(item);
    }
    get('empty-notifications').hidden = notifications.length > 0;
  } catch {
    showMessage('notification-error', 'Could not load notifications. Please try again.');
  }
}



function showDashboard(account, isAdmin) {
  const heading = get('dashboard-title');
  heading.textContent = isAdmin
    ? 'Admin dashboard'
    : `${account.name}'s Dashboard`;
  get('customer-reservations').hidden = isAdmin;
  get('customer-machines').hidden = isAdmin;
  get('customer-notifications').hidden = isAdmin;
  get('admin-summary').hidden = !isAdmin;
  get('admin-machines').hidden = !isAdmin;
  get('admin-reservations').hidden = !isAdmin;
  get('admin-payments').hidden = !isAdmin;

  if (isAdmin) {
    showAdminDashboard(account);
  } else {
    reservationPricing = account.pricing ?? defaultReservationPricing;
    showReservations(account);
    loadMachines();
    loadNotifications();
  }
  showMessage('sign-out-error');
  get('loading').hidden = true;
  get('account').hidden = true;
  get('dashboard').hidden = false;
  heading.focus();
}

async function loadAccount() {
  get('account').hidden = true;
  get('loading').hidden = false;
  get('retry').hidden = true;
  showMessage('loading-error');
  showMessage('loading-message', 'Loading your account…');

  try {
    const session = await fetch('/api/session', { cache: 'no-store' });
    if (session.status === 401) {
      showSignIn();
      return;
    }
    if (!session.ok) throw new Error();
    const { role } = await session.json();
    const isAdmin = role === 'ADMIN';
    const page = isAdmin ? '/api/admin' : '/api/reservations';
    const response = await fetch(page, { cache: 'no-store' });
    if (response.status === 401) {
      showSignIn();
      return;
    }
    if (!response.ok) throw new Error();
    showDashboard(await response.json(), isAdmin);
  } catch {
    showMessage('loading-message');
    showMessage('loading-error', 'We could not load your account. Please try again.');
    get('retry').hidden = false;
  }
}

// Spring Security requires a CSRF token for write requests.
async function submitWithCsrf(url, body, contentType, method = 'POST') {
  const response = await fetch('/api/csrf', { cache: 'no-store' });
  if (!response.ok) throw new Error('Could not connect. Please try again.');

  const { headerName, token } = await response.json();
  const headers = { [headerName]: token };
  if (contentType) headers['Content-Type'] = contentType;
  return fetch(url, { method, headers, body });
}

function adminMachineCard(machine) {
  const card = get('admin-machine-template').content.firstElementChild.cloneNode(true);
  const title = card.querySelector('h3');
  const form = card.querySelector('form');
  const fields = form.elements;
  const fieldset = card.querySelector('fieldset');
  const saveButton = card.querySelector('[type="submit"]');
  const activationButton = card.querySelector('.machine-activation');
  const inactiveLabel = card.querySelector('.machine-inactive');
  const notice = card.querySelector('.notice');
  const error = card.querySelector('.error');
  const url = machine ? `/api/admin/machines/${machine.id}` : '/api/admin/machines';

  function showActivation(active) {
    inactiveLabel.hidden = active;
    activationButton.value = active ? 'deactivate' : 'reactivate';
    activationButton.textContent = active ? 'Deactivate machine' : 'Reactivate machine';
    activationButton.hidden = false;
  }

  function showMachine(saved) {
    title.textContent = saved.name;
    fields.name.value = saved.name;
    fields.type.value = saved.type;
    fields.status.value = saved.status;
    showActivation(saved.active);
  }

  async function save(action) {
    const path = action ? `${url}/${action}` : url;
    const method = machine && !action ? 'PUT' : 'POST';
    const body = action ? undefined : JSON.stringify(Object.fromEntries(new FormData(form)));
    let focusTarget = action ? activationButton : (machine ? saveButton : fields.name);
    setMessage(notice);
    setMessage(error);
    fieldset.disabled = true;

    try {
      const response = await submitWithCsrf(path, body, action ? undefined : 'application/json', method);
      if (response.status === 409) throw new Error('A machine already uses that name.');
      if (response.status === 400) throw new Error('Enter a machine name and choose a valid type and status.');
      if (response.status === 404) throw new Error('Machine not found. Reload the dashboard and try again.');
      if (action === 'deactivate' && (response.status === 502 || response.status === 503)) {
        throw new Error('Stripe could not refund this machine’s reservations, so it is still active. Please try again.');
      }
      if (response.status === 401 || response.status === 403) {
        throw new Error('Your session may have expired. Sign in again to manage machines.');
      }
      if (!response.ok) throw new Error('Could not save the machine. Please try again.');
      const saved = await response.json();

      if (action) {
        showActivation(saved.active);
        setMessage(notice, saved.active
          ? 'Machine reactivated.'
          : 'Machine deactivated. Any upcoming reservations were cancelled, refunded and their customers notified.');
      } else if (machine) {
        showMachine(saved);
        setMessage(notice, 'Changes saved.');
      } else {
        const savedCard = adminMachineCard(saved);
        setMessage(savedCard.querySelector('.notice'), saved.name + ' added.');
        card.replaceWith(savedCard);
        focusTarget = savedCard.querySelector('input[name="name"]');
        loadSummary();
      }
    } catch (failure) {
      setMessage(error, failure instanceof TypeError ? 'Could not connect. Please try again.' : failure.message);
    } finally {
      fieldset.disabled = false;
      if (document.activeElement === document.body) {
        focusTarget.focus();
      }
    }
  }

  saveButton.textContent = machine ? 'Save changes' : 'Add machine';
  if (machine) {
    showMachine(machine);
  } else {
    card.id = 'admin-machine-draft';
    title.textContent = 'New machine';
  }
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    save();
  });
  activationButton.addEventListener('click', () => {
    save(activationButton.value);
  });
  return card;
}

get('admin-add-machine').addEventListener('click', () => {
  let draft = get('admin-machine-draft');
  if (!draft) {
    draft = adminMachineCard();
    get('admin-machine-list').append(draft);
  }
  draft.querySelector('input[name="name"]').focus();
});

async function loadSummary() {
  const stats = get('admin-summary-stats');
  showMessage('admin-summary-error');

  try {
    const response = await fetch('/api/admin/summary', { cache: 'no-store' });
    if (response.status === 401 || response.status === 403) {
      throw new Error('Your session may have expired. Sign in again to view the summary.');
    }
    if (!response.ok) throw new Error('The analytics summary could not be retrieved. Please try again.');
    const summary = await response.json();

    get('summary-machines').textContent = summary.machines;
    get('summary-reservations').textContent = summary.reservations;
    get('summary-completed-payments').textContent = summary.completedPayments;
    stats.hidden = false;
  } catch (error) {
    stats.hidden = true;
    showMessage('admin-summary-error', error instanceof TypeError ? 'Could not connect. Please try again.' : error.message);
  }
}

function showAdminDashboard(account) {
  loadSummary();

  const machines = get('admin-machine-list');
  machines.replaceChildren();
  for (const machine of account.machines) machines.append(adminMachineCard(machine));

  const reservations = get('admin-reservation-list');
  reservations.replaceChildren();
  for (const reservation of account.reservations) {
    const row = get('admin-reservation-template').content.cloneNode(true);
    row.querySelector('.reservation-id').textContent = reservation.id;
    row.querySelector('.reservation-machine').textContent = reservation.machine;
    row.querySelector('.customer-name').textContent = reservation.customerName;
    row.querySelector('.customer-email').textContent = reservation.customerEmail;
    row.querySelector('.admin-reservation-status').textContent = formatLabel(reservation.status);
    showReservationTime(row, reservation);
    reservations.append(row);
  }
  get('admin-reservations-empty').hidden = account.reservations.length > 0;
  get('admin-reservations-table').hidden = account.reservations.length === 0;

  const payments = get('admin-payment-list');
  payments.replaceChildren();
  for (const payment of account.payments) {
    const row = payments.insertRow();
    const time = document.createElement('time');
    time.dateTime = payment.attemptedAt;
    time.textContent = new Date(payment.attemptedAt).toLocaleString(undefined, {
      dateStyle: 'medium',
      timeStyle: 'short'
    });
    row.insertCell().textContent = payment.id;
    row.insertCell().textContent = payment.reservationId;
    row.insertCell().textContent = formatPaymentAmount(payment) + ' ' + payment.currency;
    row.insertCell().textContent = formatLabel(payment.status);
    row.insertCell().append(time);
  }
  get('admin-payments-empty').hidden = account.payments.length > 0;
  get('admin-payments-table').hidden = account.payments.length === 0;
}

registerForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = get('register-button');
  const switchButton = get('show-sign-in');
  button.disabled = true;
  switchButton.disabled = true;
  showMessage('register-error');

  const fields = Object.fromEntries(new FormData(registerForm));
  fields.email = fields.email.trim();

  try {
    const response = await submitWithCsrf('/api/register', JSON.stringify(fields), 'application/json');
    if (response.status === 409) throw new Error('An account with this email already exists.');
    if (response.status === 400) throw new Error('Check your name and email and use a password with 8 to 128 characters.');
    if (!response.ok) throw new Error('We could not create your account. Please try again.');

    registerForm.reset();
    signInForm.reset();
    get('sign-in-email').value = fields.email;
    showSignIn('Account created. Sign in with your email and password.');
    signInForm.elements.password.focus();
  } catch (error) {
    showMessage('register-error', error instanceof TypeError ? 'Could not connect. Please try again.' : error.message);
  } finally {
    button.disabled = false;
    switchButton.disabled = false;
  }
});

signInForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = get('sign-in-button');
  const switchButton = get('show-register');
  button.disabled = true;
  switchButton.disabled = true;
  showMessage('sign-in-error');
  showMessage('sign-in-notice');

  try {
    const fields = new URLSearchParams(new FormData(signInForm));
    const response = await submitWithCsrf('/api/login', fields);
    if (response.status === 401) throw new Error('Your email or password is incorrect.');
    if (!response.ok) throw new Error('We could not sign you in. Please try again.');

    signInForm.reset();
    await loadAccount();
  } catch (error) {
    showMessage('sign-in-error', error instanceof TypeError ? 'Could not connect. Please try again.' : error.message);
  } finally {
    button.disabled = false;
    switchButton.disabled = false;
  }
});

get('sign-out').addEventListener('click', async (event) => {
  const button = event.currentTarget;
  button.disabled = true;
  showMessage('sign-out-error');

  try {
    const response = await submitWithCsrf('/api/logout');
    if (!response.ok) throw new Error();

    showMessage('account-name');
    get('reservation-list').replaceChildren();
    get('notification-list').replaceChildren();
    get('reservation-form').reset();
    get('admin-machine-list').replaceChildren();
    get('admin-reservation-list').replaceChildren();
    get('admin-payment-list').replaceChildren();
    showSignIn('You have signed out.');
    get('sign-in-email').focus();
  } catch {
    showMessage('sign-out-error', 'We could not sign you out. Please try again.');
  } finally {
    button.disabled = false;
  }
});

get('show-register').addEventListener('click', () => {
  signInForm.reset();
  get('sign-in-panel').hidden = true;
  get('register-panel').hidden = false;
  showMessage('register-error');
  get('register-name').focus();
});

get('show-sign-in').addEventListener('click', () => {
  registerForm.reset();
  showSignIn();
  get('sign-in-email').focus();
});

get('retry').addEventListener('click', loadAccount);


// UC2: Browse and select machines.

const reservationDate = get('reservation-date');
const reservationStart = get('reservation-start');
const reservationStartOccurrence = get('reservation-start-occurrence');
const reservationForm = get('reservation-form');
const defaultReservationPricing = {
  currency: 'CAD',
  blockMinutes: 30,
  blockRatesCents: { WASHER: 500, DRYER: 300 },
  maxBlocks: 8
};
let selectedMachine = null;
let reservationPricing;

async function loadMachines() {
  const list = get('machine-list');
  const type = get('machine-type').value;
  const availableOnly = get('available-only').checked;

  showMessage('machine-error');
  selectedMachine = null;
  get('reservation-form-panel').hidden = true;
  get('reservation-form').reset();
  showMessage('reservation-error');
  showMessage('reservation-success');
  get('machine-loading').hidden = false;
  get('machine-empty').hidden = true;
  list.replaceChildren();

  const params = new URLSearchParams();

  if (type) {
    params.set('type', type);
  }

  if (availableOnly) {
    params.set('availableOnly', 'true');
  }

  try {
    const response = await fetch(
      '/api/machines?' + params.toString(),
      { cache: 'no-store' }
    );

    if (!response.ok) {
      throw new Error('Could not load machines.');
    }

    const machines = await response.json();

    get('machine-empty').hidden = machines.length > 0;

    for (const machine of machines) {
      const card = document.createElement('div');
      card.className = 'machine-card';

      const title = document.createElement('h3');
      title.textContent = machine.name;

      const typeText = document.createElement('p');
      typeText.textContent = 'Type: ' + formatLabel(machine.type);

      const status = document.createElement('p');
      status.textContent = 'Status: ' + formatLabel(machine.status);

      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'primary';
      button.textContent = 'Select';

      const available = machine.status === 'AVAILABLE';
      button.disabled = !available;

      if (!available) {
        button.textContent = 'Unavailable';
      }


      button.addEventListener('click', () => {
        selectedMachine = machine;
        const heading = get('selected-machine');
        heading.textContent = 'Reserve ' + machine.name;
        get('reservation-form-panel').hidden = false;
        prepareReservationSchedule();
        heading.focus();

        showMessage('reservation-error');
        showMessage('reservation-success');
      });


      card.append(title, typeText, status, button);
      list.append(card);
    }

  } catch (error) {
    showMessage('machine-error', error.message);
  } finally {
    get('machine-loading').hidden = true;
  }
}

get('machine-type').addEventListener(
  'change',
  loadMachines
);

get('available-only').addEventListener(
  'change',
  loadMachines
);

/* UC3: Create a machine reservation. */

function selectedReservationBlocks() {
  const minutes = Number(reservationForm.elements.durationMinutes.value);
  return minutes / reservationPricing.blockMinutes;
}

function localDateTimeValue(date) {
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}

function localDateValue(date) {
  return localDateTimeValue(date).slice(0, 10);
}

function prepareReservationSchedule() {
  const now = Date.now();
  reservationDate.min = localDateValue(new Date(now));
  if (!reservationDate.value || reservationDate.value < reservationDate.min) {
    const nextMinute = new Date((Math.floor(now / 60_000) + 1) * 60_000);
    reservationDate.value = localDateValue(nextMinute);
  }
  updateReservationSummary();
}

function reservationStartTimes() {
  const localStart = `${reservationDate.value}T${reservationStart.value}`;
  const start = new Date(localStart);
  // A nonexistent local time can be shifted forward by the Date constructor.
  if (!Number.isFinite(start.getTime()) || localDateTimeValue(start) !== localStart) return [];

  // A backward clock change can give the same local time two UTC offsets.
  const followingDay = new Date(start);
  followingDay.setDate(followingDay.getDate() + 1);
  const offsetChange = followingDay.getTimezoneOffset() - start.getTimezoneOffset();
  if (offsetChange > 0) {
    const laterStart = new Date(start.getTime() + offsetChange * 60_000);
    if (localDateTimeValue(laterStart) === localStart) return [start, laterStart];
  }
  return [start];
}

function reservationEndTime(start, blocks) {
  return new Date(start.getTime() + blocks * reservationPricing.blockMinutes * 60_000);
}

function updateReservationSummary() {
  if (!selectedMachine) return;
  const { currency, blockMinutes, blockRatesCents } = reservationPricing;
  const rate = blockRatesCents[selectedMachine.type];
  const blockPrice = formatPaymentAmount({ amountCents: rate, currency });
  const blocks = selectedReservationBlocks();
  const [firstStart, laterStart] = reservationStartTimes();
  get('reservation-start-occurrence-field').hidden = !laterStart;
  if (!laterStart) reservationStartOccurrence.value = 'first';
  const start = reservationStartOccurrence.value === 'second' ? laterStart : firstStart;
  let endLabel = '—';
  if (start && start > new Date() && blocks > 0) {
    const end = reservationEndTime(start, blocks);
    const endFormat = { hour: 'numeric', minute: '2-digit' };
    if (localDateValue(end) !== localDateValue(start)) {
      endFormat.month = 'short';
      endFormat.day = 'numeric';
    }
    if (start.getTimezoneOffset() !== end.getTimezoneOffset()) {
      endFormat.timeZoneName = 'short';
    }
    endLabel = end.toLocaleString(undefined, endFormat);
  }
  get('reservation-end').value = endLabel;
  get('reservation-summary').textContent = `${blockPrice} per ${blockMinutes} min`;
  get('reservation-total').textContent = formatPaymentAmount({ amountCents: blocks * rate, currency });
}

reservationDate.addEventListener('input', updateReservationSummary);
reservationStart.addEventListener('input', updateReservationSummary);
reservationStartOccurrence.addEventListener('change', updateReservationSummary);
get('reservation-duration').addEventListener('change', updateReservationSummary);

reservationForm.addEventListener('submit', async (event) => {
  event.preventDefault();

  if (!selectedMachine) {
    showMessage('reservation-error', 'Please select a machine first.');
    return;
  }

  const button = get('reserve-button');
  if (button.disabled) return;

  showMessage('reservation-error');
  showMessage('reservation-success');

  const [firstStart, laterStart] = reservationStartTimes();
  const start = reservationStartOccurrence.value === 'second' ? laterStart : firstStart;
  if (!start || start <= new Date()) {
    updateReservationSummary();
    showMessage('reservation-error', 'Choose a valid future start time.');
    return;
  }

  const blocks = selectedReservationBlocks();
  if (!Number.isInteger(blocks) || blocks < 1 || blocks > reservationPricing.maxBlocks) {
    showMessage('reservation-error', 'Choose a duration of 30 minutes to four hours.');
    return;
  }

  const dummyFields = get('reservation-form').querySelectorAll('.test-card input');
  const blankField = Array.from(dummyFields).find((field) => !field.value.trim());
  if (blankField) {
    showMessage('reservation-error', 'Complete all four payment fields.');
    blankField.focus();
    return;
  }

  // The text fields stay in the browser. Only a fixed test method goes to the server.
  const reservation = {
    machineId: selectedMachine.id,
    startsAt: start.toISOString(),
    endsAt: reservationEndTime(start, blocks).toISOString(),
    testPaymentMethod: get('test-payment-decline').checked ? 'pm_card_visa_chargeDeclined' : 'pm_card_visa'
  };

  button.disabled = true;
  try {
    const response = await submitWithCsrf(
      '/api/reservations',
      JSON.stringify(reservation),
      'application/json'
    );

    if (response.status === 402) {
      throw new Error('Payment declined. Please try again.');
    }

    if (response.status === 503) {
      throw new Error('Stripe test payments are unavailable. Check the server’s test key.');
    }

    if (response.status === 502) {
      throw new Error('Stripe did not confirm the test payment. Please try again.');
    }

    if (response.status === 404 || response.status === 409) {
      throw new Error('This machine is unavailable or already reserved for the selected time.');
    }

    if (response.status === 400) {
      throw new Error('Please check your reservation details.');
    }

    if (!response.ok) {
      throw new Error('Could not create reservation.');
    }

    showMessage(
      'reservation-success',
      'Payment accepted. Your reservation is confirmed.'
    );

    get('reservation-form').reset();
    if (selectedMachine) prepareReservationSchedule();

    const reservationsResponse = await fetch(
      '/api/reservations',
      { cache: 'no-store' }
    );

    if (reservationsResponse.ok) {
      showReservations(await reservationsResponse.json());
    }

    loadNotifications();

  } catch (error) {
    showMessage(
      'reservation-error',
      error.message || 'Could not create reservation.'
    );
  } finally {
    button.disabled = false;
  }
});

loadAccount();
