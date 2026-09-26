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
