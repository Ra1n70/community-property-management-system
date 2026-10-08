// Browsers word built-in form validation and confirm() dialogs in the operating system's language.
// The app is English-only, so validation messages are replaced here and confirmations use our own dialog.

function message(el) {
  const v = el.validity;
  const length = (el.value || '').length;
  if (v.valueMissing) {
    if (el.type === 'checkbox') return 'Check this box to continue.';
    if (el.type === 'radio') return 'Select one of these options.';
    if (el.type === 'file') return 'Choose a file.';
    if (el.tagName === 'SELECT') return 'Select an option from the list.';
    return 'Fill in this field.';
  }
  if (v.typeMismatch) return el.type === 'email' ? 'Enter a valid email address, such as name@example.com.' : el.type === 'url' ? 'Enter a valid URL.' : 'Enter a valid value.';
  if (v.tooShort) return `Use at least ${el.minLength} characters (you entered ${length}).`;
  if (v.tooLong) return `Use no more than ${el.maxLength} characters (you entered ${length}).`;
  if (v.patternMismatch) {
    if (el.title) return el.title;
    const digits = /^\[0-9\]\{(\d+)\}$/.exec(el.getAttribute('pattern') || '');
    return digits ? `Enter exactly ${digits[1]} digits.` : 'Match the requested format.';
  }
  if (v.rangeUnderflow) return `Enter a value of ${el.min} or more.`;
  if (v.rangeOverflow) return `Enter a value of ${el.max} or less.`;
  if (v.stepMismatch) return 'Enter a valid value.';
  if (v.badInput) return 'Enter a valid value.';
  return '';
}

export function installEnglishValidation() {
  document.addEventListener('invalid', event => {
    const el = event.target;
    if (!el.setCustomValidity) return;
    el.setCustomValidity('');
    if (!el.validity.valid) el.setCustomValidity(message(el));
  }, true);
  // Clear the custom message as soon as the person edits the field, so the browser re-checks it.
  const reset = event => { if (event.target.setCustomValidity) event.target.setCustomValidity(''); };
  document.addEventListener('input', reset, true);
  document.addEventListener('change', reset, true);
}

/** English replacement for window.confirm(); resolves true when the person confirms. */
export function confirmAction({ title = 'Please confirm', message: text, confirmLabel = 'Confirm', cancelLabel = 'Cancel', danger = false }) {
  return new Promise(resolve => {
    const dialog = document.createElement('dialog');
    dialog.className = 'app-confirm';
    dialog.setAttribute('aria-labelledby', 'app-confirm-title');
    const heading = document.createElement('h3'); heading.id = 'app-confirm-title'; heading.textContent = title;
    const body = document.createElement('p'); body.textContent = text;
    const actions = document.createElement('div'); actions.className = 'actions';
    const ok = document.createElement('button'); ok.type = 'button'; ok.className = danger ? 'primary danger' : 'primary'; ok.textContent = confirmLabel;
    const cancel = document.createElement('button'); cancel.type = 'button'; cancel.textContent = cancelLabel;
    actions.append(ok, cancel);
    dialog.append(heading, body, actions);
    let answered = false;
    const finish = value => { if (answered) return; answered = true; dialog.close(); dialog.remove(); resolve(value); };
    ok.addEventListener('click', () => finish(true));
    cancel.addEventListener('click', () => finish(false));
    dialog.addEventListener('cancel', event => { event.preventDefault(); finish(false); });
    dialog.addEventListener('click', event => { if (event.target === dialog) finish(false); });
    document.body.append(dialog);
    dialog.showModal();
    cancel.focus();
  });
}
