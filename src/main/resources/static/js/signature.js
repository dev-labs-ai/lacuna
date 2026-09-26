// Web PKI side of PAdES and CAdES signatures (see SignatureController):
//  - "start" step (sign.html): list the user's certificates, then post the chosen one's content;
//  - "complete" step (sign-complete.html): sign the hash computed by PKI Express, then post the signature.
// API: https://docs.lacunasoftware.com/articles/web-pki/get-started
(() => {
    'use strict';

    const form = document.getElementById('sign-form');
    const status = form.querySelector('[data-status]');
    const installNotice = document.querySelector('[data-install-notice]');

    // Without a license the Web PKI only works on localhost.
    const license = form.dataset.webPkiLicense;
    const pki = new LacunaWebPKI(license && license.trim().startsWith('{') ? JSON.parse(license) : license);

    function showStatus(message, kind = 'info') {
        status.textContent = message;
        status.dataset.kind = kind;
    }

    function setBusy(busy) {
        form.setAttribute('aria-busy', String(busy));
        for (const button of form.querySelectorAll('button')) {
            button.disabled = busy;
        }
    }

    function onWebPkiError(ex) {
        console.error(`Web PKI error from ${ex.origin} (${ex.code}): ${ex.error}`);
        setBusy(false);
        showStatus(ex.userMessage || ex.message, 'error');
    }

    // The component is missing, outdated, or the browser is not supported.
    function onWebPkiNotInstalled(installationState, message) {
        showStatus('');
        installNotice.querySelector('[data-install-message]').textContent = message;
        installNotice.querySelector('[data-action="install"]').addEventListener('click', () => pki.redirectToInstallPage());
        installNotice.hidden = false;
    }

    function initWebPki(ready) {
        pki.init({ready, notInstalled: onWebPkiNotInstalled, defaultFail: onWebPkiError});
    }

    function startStep() {
        const select = form.querySelector('#certificate');

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
                if (certificates.length === 0) {
                    showStatus('Nenhum certificado encontrado. Conecte seu token ou smartcard, ou instale um '
                        + 'certificado, e clique em "Atualizar lista".', 'warning');
                } else {
                    showStatus('');
                }
            });
        }

        form.querySelector('[data-action="refresh"]').addEventListener('click', loadCertificates);

        // Only fires once the browser validated the form, i.e. a certificate is selected.
        form.addEventListener('submit', (event) => {
            event.preventDefault();
            const thumbprint = select.value;
            setBusy(true);
            showStatus('Lendo o certificado…');
            pki.readCertificate({thumbprint}).success((certificateContent) => {
                form.elements.certThumb.value = thumbprint;
                form.elements.certContent.value = certificateContent;
                showStatus('Preparando o documento…');
                form.submit();
            });
        });

        initWebPki(loadCertificates);
    }

    function completeStep() {
        setBusy(true);
        initWebPki(() => {
            showStatus('Assinando… Se solicitado, informe o PIN do certificado.');
            pki.signHash({
                thumbprint: form.dataset.certThumb,
                hash: form.dataset.toSignHash,
                digestAlgorithm: form.dataset.digestAlgorithm,
            }).success((signature) => {
                form.elements.signature.value = signature;
                showStatus('Finalizando a assinatura…');
                form.submit();
            });
        });
    }

    ({start: startStep, complete: completeStep})[form.dataset.step]();
})();
