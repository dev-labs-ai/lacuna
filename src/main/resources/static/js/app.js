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

// File inputs marked with data-max-file-size (bytes) leave out the files larger than that, which the server would
// refuse along with the whole request, and say which ones; data-max-files caps how many files may remain.
const megabytes = new Intl.NumberFormat('pt-BR', {maximumFractionDigits: 1});
const fileNames = new Intl.ListFormat('pt-BR');
const formatSize = (bytes) => `${megabytes.format(bytes / (1024 * 1024))} MB`;
for (const input of document.querySelectorAll('input[type="file"][data-max-file-size]')) {
    const maxSize = Number(input.dataset.maxFileSize);
    const maxFiles = Number(input.dataset.maxFiles) || Infinity;
    const leftOut = document.createElement('p');
    leftOut.id = `${input.id}-left-out`;
    leftOut.className = 'status';
    leftOut.dataset.kind = 'warning';
    leftOut.setAttribute('role', 'status');
    input.parentElement.append(leftOut);
    input.setAttribute('aria-describedby', `${input.getAttribute('aria-describedby') ?? ''} ${leftOut.id}`.trim());

    input.addEventListener('change', () => {
        const kept = new DataTransfer();
        const tooLarge = [];
        for (const file of input.files) {
            if (file.size > maxSize) {
                tooLarge.push(`${file.name} (${formatSize(file.size)})`);
            } else {
                kept.items.add(file);
            }
        }
        if (tooLarge.length > 0) {
            input.files = kept.files;
        }
        const limit = formatSize(maxSize);
        leftOut.textContent = tooLarge.length === 0 ? ''
            : tooLarge.length === 1 ? `${tooLarge[0]} não será enviado: passa do limite de ${limit} por arquivo.`
            : `Não serão enviados, por passarem do limite de ${limit} por arquivo: ${fileNames.format(tooLarge)}.`;

        input.setCustomValidity(input.files.length > maxFiles ? `Selecione no máximo ${maxFiles} arquivos por vez.` : '');
        if (input.files.length > maxFiles) {
            input.reportValidity();
        }
    });
}

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
