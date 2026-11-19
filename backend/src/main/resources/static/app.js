const get = (id) => document.getElementById(id);

const signInForm = get('sign-in-form');
const registerForm = get('register-form');

function showMessage(id, message = '') {
  const element = get(id);
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

function showReservations(customer) {
  const list = get('reservation-list');
  const template = get('reservation-template');
  list.replaceChildren();

  for (const reservation of customer.reservations) {
    const row = template.content.cloneNode(true);
    row.querySelector('h2').textContent = reservation.machine;
    row.querySelector('.reservation-status').textContent = reservation.status;

    const start = new Date(reservation.startsAt);
    const end = new Date(reservation.endsAt);
    const startTime = row.querySelector('.start-time');
    const endTime = row.querySelector('.end-time');
    const dateOptions = { dateStyle: 'medium', timeStyle: 'short' };

    startTime.dateTime = reservation.startsAt;
    startTime.textContent = start.toLocaleString(undefined, dateOptions);
    endTime.dateTime = reservation.endsAt;
    endTime.textContent = end.toLocaleString(undefined, dateOptions);
    list.append(row);
  }

  get('empty-reservations').hidden = customer.reservations.length > 0;
}


function showDashboard(account, isAdmin) {
  const heading = get('dashboard-title');
  heading.textContent = isAdmin
    ? 'Admin dashboard'
    : 'My reservations';
  get('account-name').textContent =
    'Signed in as ' + account.name;
  get('customer-reservations').hidden = isAdmin;
  get('customer-machines').hidden = isAdmin;

  if (!isAdmin) {
    showReservations(account);
    loadMachines();
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

// Spring Security requires a fresh CSRF token for account changes.
async function postAccount(url, body, contentType) {
  const response = await fetch('/api/csrf', { cache: 'no-store' });
  if (!response.ok) throw new Error('Could not connect. Please try again.');

  const { headerName, token } = await response.json();
  const headers = { [headerName]: token };
  if (contentType) headers['Content-Type'] = contentType;
  return fetch(url, { method: 'POST', headers, body });
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
    const response = await postAccount('/api/register', JSON.stringify(fields), 'application/json');
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
    const response = await postAccount('/api/login', fields);
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
    const response = await postAccount('/api/logout');
    if (!response.ok) throw new Error();

    get('account-name').textContent = '';
    get('reservation-list').replaceChildren();
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

let selectedMachineId = null;

async function loadMachines() {
  const list = get('machine-list');
  const type = get('machine-type').value;
  const availableOnly = get('available-only').checked;

  showMessage('machine-error');
  showMessage('selected-machine');
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
      typeText.textContent = 'Type: ' + machine.type;

      const status = document.createElement('p');
      status.textContent = 'Status: ' +
        machine.status.replaceAll('_', ' ');

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
        selectedMachineId = machine.id;

        showMessage(
          'selected-machine',
          machine.name +
          ' selected. Reservation scheduling will be added in UC3.'
        );
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

loadAccount();
