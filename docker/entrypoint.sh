#!/bin/sh
# Activates PKI Express, if needed, and starts the application.
#
# PKI Express binds its activation to the hostname and MAC addresses, which Docker changes whenever it re-creates a
# container. compose.yaml pins both and keeps /etc/pkie in a volume, so the activation is only redone when they change,
# or when the license file holds another license (a renewed one replacing a license about to expire).
set -eu

# The license file (see compose.yaml). pkie takes it as a file only when the name ends in .config.
license=/run/secrets/pkie_license.config

# Whether PKI Express is activated, with the license in the license file when there is one. Licenses are told apart by
# their signature, which is both in the file and in what --check prints.
is_activated() {
    signature=$(pkie activate --check 2>/dev/null | sed -n 's/.*"Signature": "\(.*\)".*/\1/p')
    [ -n "$signature" ] && { [ ! -e "$license" ] || grep -qF "<Signature>$signature</Signature>" "$license"; }
}

if ! is_activated; then
    echo "Activating PKI Express ..."
    if [ -e "$license" ]; then
        output=$(pkie activate "$license" 2>&1) || true
    else
        # Re-activates with the license stored by the last activation; fails when there was none.
        output=$(pkie activate 2>&1) || true
    fi
    # pkie activate exits with 0 even when it fails (it falls back to printing a manual activation request), and it
    # prints the activated license, signature included. So check the outcome, and show the output (sans the license
    # signature) only on failure.
    if ! is_activated; then
        echo "$output" | grep -v '"Signature":' >&2
        exit 1
    fi
    echo "PKI Express activated."
fi

pkie activate --check | sed -n 's/.*"Expiration": "\(.*\)".*/PKI Express license expires at \1/p'

exec java -jar /application/application.jar "$@"
