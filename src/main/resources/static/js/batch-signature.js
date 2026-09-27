// Batch signature (batch-sign.html): the user picks a certificate and authorizes all the signatures once in Web PKI;
// each file is then signed with the JSON API (see SignatureApiController): start, signHash, complete.
// API: https://docs.lacunasoftware.com/articles/web-pki/get-started
(() => {
    'use strict';

    // Files signed at the same time; each one is a start, a signHash and a complete.
    const CONCURRENCY = 2;

    const form = document.getElementById('batch-form');
    const status = form.querySelector('[data-status]');
    const report = form.querySelector('[data-report]');
    const select = form.querySelector('#certificate');
    const submitButton = form.querySelector('button[type="submit"]');
    const installNotice = document.querySelector('[data-install-notice]');
    const items = [...document.querySelectorAll('[data-batch-item]')];
    const pdfFormatOptions = form.querySelectorAll('input[name="pdfFormat"]');
    const api = form.dataset.api;

    // Without a license the Web PKI only works on localhost.
    const license = form.dataset.webPkiLicense;
    const pki = new LacunaWebPKI(license && license.trim().startsWith('{') ? JSON.parse(license) : license);

    // Web PKI promises take success and fail callbacks; a fail callback given here replaces the default one.
    function webPki(promise) {
        return new Promise((resolve, reject) => promise.success(resolve).fail(reject));
    }

    // Rejects with the problem details (RFC 9457) the API answers on errors.
    async function post(path, body) {
        const response = await fetch(api + path, {
            method: 'POST',
            headers: {'Content-Type': 'application/json', Accept: 'application/json'},
            body: JSON.stringify(body),
        });
        if (response.ok) {
            return response.status === 204 ? null : response.json();
        }
        const problem = await response.json().catch(() => ({}));
        throw {message: problem.detail || `Erro ${response.status} no servidor.`, report: problem.report};
    }

    function files(count) {
        return count === 1 ? '1 arquivo' : `${count} arquivos`;
    }

    // Web PKI errors carry a userMessage; API errors, a message.
    function messageOf(error) {
        return error.userMessage || error.message || String(error);
    }

    function showStatus(message, kind = 'info', technicalReport = null) {
        status.textContent = message;
        status.dataset.kind = kind;
        report.hidden = !technicalReport;
        report.querySelector('pre').textContent = technicalReport || '';
    }

    function setBusy(busy) {
        form.setAttribute('aria-busy', String(busy));
        for (const button of form.querySelectorAll('button')) {
            button.disabled = busy;
        }
        for (const input of form.querySelectorAll('input, select')) {
            input.disabled = busy;
        }
        // The format of a signed file can no longer change.
        for (const item of items) {
            const format = item.querySelector('[data-item-format]');
            if (format) {
                format.disabled = busy || item.dataset.state === 'done';
            }
        }
    }

    function onWebPkiError(ex) {
        console.error(`Web PKI error from ${ex.origin} (${ex.code}): ${ex.error}`);
        setBusy(false);
        showStatus(messageOf(ex), 'error');
    }

    // The component is missing, outdated, or the browser is not supported.
    function onWebPkiNotInstalled(installationState, message) {
        showStatus('');
        installNotice.querySelector('[data-install-message]').textContent = message;
        installNotice.querySelector('[data-action="install"]').addEventListener('click', () => pki.redirectToInstallPage());
        installNotice.hidden = false;
    }

    function loadCertificates() {
        setBusy(true);
        showStatus('Carregando certificados…');
        pki.listCertificates({
            selectId: select.id,
            selectOptionFormatter: (cert) => {
                const label = `${cert.subjectName} (emitido por ${cert.issuerName})`;
                return new Date() > cert.validityEnd ? `[EXPIRADO] ${label}` : label;
            },
        }).success((certificates) => {
            setBusy(false);
            updateSubmitButton();
            if (certificates.length === 0) {
                showStatus('Nenhum certificado encontrado. Conecte seu token ou smartcard, ou instale um '
                    + 'certificado, e clique em "Atualizar lista".', 'warning');
            } else {
                showStatus('');
            }
        });
    }

    function pendingItems() {
        return items.filter((item) => item.dataset.state !== 'done');
    }

    function updateSubmitButton() {
        const pending = pendingItems().length;
        submitButton.disabled = pending === 0;
        if (pending > 0 && pending < items.length) {
            submitButton.textContent = pending === 1 ? 'Assinar o arquivo restante' : `Assinar os ${pending} restantes`;
        }
    }

    function setItemState(item, state, text) {
        item.dataset.state = state;
        item.querySelector('[data-item-status]').textContent = text;
    }

    // Each PDF has its own choice; any other file is signed in CAdES.
    function signatureFormatOf(item) {
        return item.querySelector('[data-item-format]')?.value ?? 'CADES';
    }

    function pendingPdfFormats() {
        return pendingItems().map((item) => item.querySelector('[data-item-format]')).filter(Boolean);
    }

    // The format for all PDFs sets the one of each PDF still to sign.
    function applyPdfFormat(event) {
        for (const format of pendingPdfFormats()) {
            format.value = event.target.value;
        }
    }

    // Checks the format shared by the PDFs still to sign, or neither option when they differ.
    function showPdfFormat() {
        const chosen = new Set(pendingPdfFormats().map((format) => format.value));
        if (chosen.size === 0) {
            return;
        }
        for (const option of pdfFormatOptions) {
            option.checked = chosen.size === 1 && chosen.has(option.value);
        }
    }

    async function signItem(item, thumbprint, certificate) {
        const documentPath = `/documents/${item.dataset.id}/signature`;
        try {
            setItemState(item, 'working', 'Preparando…');
            const start = await post(`${documentPath}/start`, {format: signatureFormatOf(item), certificate});

            setItemState(item, 'working', 'Assinando…');
            const signature = await webPki(pki.signHash({
                thumbprint,
                hash: start.toSignHash,
                digestAlgorithm: start.digestAlgorithm,
            }));

            setItemState(item, 'working', 'Finalizando…');
            const signed = await post(`${documentPath}/complete`,
                {transferFileId: start.transferFileId, signature, certificate});

            setItemState(item, 'done', 'Assinado');
            const link = item.querySelector('[data-item-link]');
            link.href = signed.url;
            link.textContent = signed.name;
            link.hidden = false;
            return true;
        } catch (error) {
            setItemState(item, 'failed', messageOf(error));
            return false;
        }
    }

    async function signPending(event) {
        event.preventDefault();
        const thumbprint = select.value;
        const batch = pendingItems();
        setBusy(true);
        try {
            showStatus('Lendo o certificado…');
            const certificate = await webPki(pki.readCertificate({thumbprint}));

            showStatus('Verificando o certificado…');
            await post('/certificates/check', {certificate});

            showStatus('Autorize as assinaturas no Web PKI…');
            await webPki(pki.preauthorizeSignatures({certificateThumbprint: thumbprint, signatureCount: batch.length}));

            showStatus(`Assinando ${files(batch.length)}…`);
            batch.forEach((item) => setItemState(item, 'queued', 'Na fila'));
            const queue = [...batch];
            let signedCount = 0;
            const worker = async () => {
                while (queue.length > 0) {
                    if (await signItem(queue.shift(), thumbprint, certificate)) {
                        signedCount++;
                    }
                }
            };
            await Promise.all(Array.from({length: Math.min(CONCURRENCY, batch.length)}, worker));

            if (signedCount === batch.length) {
                showStatus(batch.length === 1 ? 'Arquivo assinado.' : `Os ${batch.length} arquivos foram assinados.`, 'success');
            } else {
                showStatus(`Assinados: ${signedCount} de ${files(batch.length)}. Veja o motivo das falhas na lista `
                    + 'abaixo e tente assinar os restantes novamente.', 'warning');
            }
        } catch (error) {
            showStatus(messageOf(error), 'error', error.report);
        } finally {
            setBusy(false);
            updateSubmitButton();
            showPdfFormat();
        }
    }

    for (const option of pdfFormatOptions) {
        option.addEventListener('change', applyPdfFormat);
    }
    for (const format of document.querySelectorAll('[data-item-format]')) {
        format.addEventListener('change', showPdfFormat);
    }
    // Browsers may restore the choices of a reloaded page, each control on its own.
    showPdfFormat();

    form.addEventListener('submit', signPending);
    form.querySelector('[data-action="refresh"]').addEventListener('click', loadCertificates);
    pki.init({ready: loadCertificates, notInstalled: onWebPkiNotInstalled, defaultFail: onWebPkiError});
})();
