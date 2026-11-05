#!/bin/sh
set -eu

: "${TLS_PASSWORD:?TLS_PASSWORD is required for the local HTTPS certificate}"
test -r /run/account-service/tls.p12 || {
    echo 'Mount the local PKCS12 certificate as /run/account-service/tls.p12.' >&2
    exit 1
}
# The readiness check trusts the public part of the mounted local certificate.
keytool -exportcert -rfc -alias accounts-local -storetype PKCS12 \
    -keystore /run/account-service/tls.p12 -storepass:env TLS_PASSWORD \
    -file /tmp/account-service-readiness.pem

exec java -jar /app/account-service.jar "$@"
