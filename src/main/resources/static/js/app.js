// Forms marked with data-busy-on-submit disable their buttons once submitted, preventing double posts.
document.addEventListener('submit', (event) => {
    const form = event.target;
    if (event.defaultPrevented || !form.matches('[data-busy-on-submit]')) {
        return;
    }
    form.setAttribute('aria-busy', 'true');
    for (const button of form.querySelectorAll('button')) {
        button.disabled = true;
    }
});

// Re-enable them when the page is restored from the back/forward cache.
window.addEventListener('pageshow', (event) => {
    if (!event.persisted) {
        return;
    }
    for (const form of document.querySelectorAll('form[data-busy-on-submit][aria-busy="true"]')) {
        form.removeAttribute('aria-busy');
        for (const button of form.querySelectorAll('button')) {
            button.disabled = false;
        }
    }
});

// Times marked with data-local-time come in UTC; show them in the reader's time zone, e.g. "26/09/2026 12:40:45
// GMT-04:00", keeping the UTC time as a tooltip.
const localTime = new Intl.DateTimeFormat('pt-BR', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
    timeZoneName: 'longOffset',
});
for (const time of document.querySelectorAll('time[data-local-time]')) {
    const parts = Object.fromEntries(
        localTime.formatToParts(new Date(time.dateTime)).map((part) => [part.type, part.value]));
    time.title = time.textContent;
    time.textContent = `${parts.day}/${parts.month}/${parts.year} ${parts.hour}:${parts.minute}:${parts.second} `
        + parts.timeZoneName;
}
