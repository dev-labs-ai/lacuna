# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Spring Boot 4.1 (Java 25) + Thymeleaf app that signs and validates files with Lacuna's
[PKI Express](https://docs.lacunasoftware.com/articles/pki-express/java) (server side, the `pkie` executable driven by
the `pki-express` Java library) and [Web PKI](https://docs.lacunasoftware.com/articles/web-pki/get-started) (browser
extension + native app that signs hashes with the user's private key). PDFs are signed in PAdES or CAdES, any other
file in CAdES (attached `.p7s`); there is also a validator (`/validate`) and a batch signature page (`/batch`).
Documents are objects in S3 (MinIO locally) described by rows in PostgreSQL.
User-facing text is Brazilian Portuguese; code, comments and commit messages are English. Configuration properties
(`lacuna.*`) are documented in `README.md` and `LacunaProperties`.

## Commands

```bash
docker compose up -d postgres minio                        # what spring-boot:run and the dev profile expect
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev     # http://localhost:8080
./mvnw test
./mvnw test -Dtest=SignatureFlowTest                      # one class, @Nested classes included
./mvnw test -Dtest='SignatureFlowTest#signsPdfWithPades'  # one method
./mvnw package
docker compose up --build                                 # needs the license in ./LacunaPkiLicense.config
```

There is no linter or formatter configured.

## What the app and the tests need

- `pkie` on the `PATH`, installed and license-activated (`pkie` with no arguments prints its version). Tests that
  drive it carry `@EnabledIf("com.lacuna.support.TestSigner#pkiExpressInstalled")` and are **skipped silently** when
  it is missing: check the skipped counts in `target/surefire-reports`, not just a green build.
- Docker, for every test that starts the application: `TestInfrastructure` runs PostgreSQL and MinIO with
  Testcontainers, shared across test classes. A new `@SpringBootTest` class needs
  `@ImportTestcontainers(TestInfrastructure.class)`.
- Network access: the default policies (PAdES with LTV, CAdES ICP-Brasil AD-RB) fetch CRLs from Lacuna's test CA.
- `TestSigner` stands in for Web PKI in tests, with Lacuna's public test certificate
  `src/test/resources/pierre-de-fermat.pfx` (password `1234`), which is only trusted with
  `lacuna.pki-express.trust-lacuna-test-root=true`.
- The `dev` profile trusts Lacuna's test root and turns off certificate validation on selection. Without it, Lacuna
  test certificates are rejected ("Lacuna Root Test v3 is not trusted"); that is the intended default.
- Web PKI cannot run in automated browsers (no extension), so `static/js/*.js` is not covered by tests; check those
  changes in a real browser on `localhost`, where Web PKI needs no license.

## Architecture

Every signature is PKI Express' three-step remote flow:

1. **start**: `PadesSignatureStarter` / `CadesSignatureStarter` take the file and the user's certificate (read by Web
   PKI) and return the hash to sign plus the id of a *transfer file* kept in `<storage>/pkie-transfer`.
2. **sign**: the browser signs the hash with Web PKI (`signHash`).
3. **complete**: `SignatureFinisher` embeds the signature value. Transfer files are single use:
   `SignatureService.complete` validates the id format (it comes from the client) and deletes the file whether the
   completion succeeds or not.

Packages under `com.lacuna`:

- `pkiexpress` — `PkiExpressOperators` is the only place that creates PKI Express operators and applies the
  configuration (trusted roots, policies, culture, time zone). Run operators through `execute(operator, operation)`,
  which disposes their temp files and turns the library's `RuntimeException`s (raw `pkie` console output) into
  `PkiExpressException`, whose message is shown to users (`details()` holds the technical report).
- `signature` — `SignatureService` (the flow above, plus `checkCertificate` to refuse a certificate before the user
  signs); `SignatureController` (server-rendered pages with form posts, as in Lacuna's samples);
  `SignatureApiController` (the same flow as JSON for the batch page, errors as RFC 9457 `ProblemDetail` through its
  own `@ExceptionHandler`s, which win over the HTML `GlobalExceptionHandler`); `BatchSignatureController`;
  `CertificateValidator` + `CertificateRejectedException`, which explains rejections by `ValidationItemTypes`, never by
  matching message text.
- `validation` — `ValidationService` opens and validates PAdES and CAdES signatures (detached CAdES needs the
  original file) and extracts the file inside a `.p7s`.
- `document` — `DocumentStorage` keeps each document (upload or signed file) as an S3 object under
  `yyyy/MM/dd/<id>.<extension>` (UTC storage date) plus a row in the `document` table (`DocumentRepository`,
  `JdbcClient`; schema in Flyway's `db/migration`). Ids are UUID v7 from `UuidV7`, generated before the object is
  written since its key contains them (Java 25 has no v7; Java 26's `UUID.ofEpochMillis` can replace it). The object
  is written first, with its SHA-256 (checked by the server) and `x-amz-meta-*` naming the document, and deleted if the
  insert fails. `storeSigned` takes a callback that runs in the transaction inserting the document's row:
  `SignatureService` records the `signature` row there (`StoredSignature`, `SignatureRepository`), linking the source
  document to the signed one, with the signer's certificate data. PKI Express needs local files: `copyToWorkFolder` /
  `newWorkFile` return a `WorkFile` in `<storage>/work`, deleted on close, so use try-with-resources. `signed_at` is
  read back from the signed file (newest signer's time), not the completion time. `DocumentFormat` tells PDF / CMS /
  other apart from the content bytes, never from the file name, and maps each to its MIME type (stored) and
  extension.
  `SignatureFormat` holds the rules: PDFs take PAdES or CAdES, anything else CAdES, and an existing `.p7s` is co-signed.
- `web.GlobalExceptionHandler` — HTML error page; picks the "back" link from the request path.

Front end: Thymeleaf pages wrap the layout fragment `layout :: page(pageTitle, pageMain, pageScripts)`; plain
JavaScript in `static/js`, no build step. `signature.js` drives the single-file pages (`data-step="start"` or
`"complete"`); `batch-signature.js` calls `preauthorizeSignatures` once (one PIN for the whole batch), then start,
`signHash` and complete for each file through the JSON API.

## Gotchas

- Check pki-express library behavior against the resolved jar (`javap -cp ~/.m2/repository/com/lacunasoftware/pkiexpress/pki-express/<version>/pki-express-<version>.jar ...`):
  its `-sources.jar` does not always match the bytecode. The `pkie` CLI is the quickest way to probe PKI Express
  itself, e.g. `pkie start-cades <file> <cert.cer> <transfer-file> --trust-test` or `pkie open-cades <file> --validate`.
- With `culture=pt-BR`, validation items come in Portuguese, but many failures (trust errors on completion, .NET
  exceptions) still come in English.
- A CAdES signing time has one-second resolution: the same signer co-signing the same content twice within a second
  produces an identical signature, which PKI Express does not add. Tests that co-sign use `TestSigner.awaitNextSecond()`.
- Thymeleaf: `th:replace`/`th:insert` are processed before `th:if` on the same element, so put the condition on a
  wrapping `th:block`. Don't name model attributes `pageTitle`, `pageMain` or `pageScripts`.
- The Web PKI script is loaded from Lacuna's CDN with an SRI hash (`templates/fragments.html`); a version bump needs a
  new hash (`openssl dgst -sha256 -binary lacuna-web-pki-<version>.min.js | base64`).
- Web PKI promises: `.fail(cb)` replaces the `defaultFail` callback given to `init`.
- Signing times: PKI Express runs in UTC (`PkiExpressOperators.TIME_ZONE`, also in the PDF stamp format in
  `SignatureService`), so the stamp prints UTC; pages render them in UTC inside `<time data-local-time>`, which
  `app.js` rewrites in the reader's time zone.
- Only PDFs are served inline; any other download is `attachment` + `application/octet-stream` + `nosniff`, because
  uploads can be any file.
- Batches are capped at 20 files because Tomcat accepts at most 50 multipart parts (`server.tomcat.max-part-count`).
- Docker: the image carries `pkie` but not the license; `docker/entrypoint.sh` activates it on start. The activation
  is bound to the hostname and MAC addresses (the only machine data in `pkie activate <license> --request` codes), so
  `compose.yaml` pins both and keeps `/etc/pkie` in a volume: without that, every re-created container activates
  again. The entrypoint also activates when the file holds another license (the trial license from
  `LacunaSoftware/PkiSuiteSamples` is renewed monthly), telling licenses apart by `<Signature>`, which `--check`
  prints too. `pkie activate` quirks: it exits 0 even when activation fails (it falls back to a manual activation
  request), so judge by `--check`; it prints the license signature, so keep its output out of the logs; it reads a
  license *file* only when the name ends in `.config` (otherwise: "not a valid Base64 string"). The image build skips
  the tests (they need an activated `pkie`); `pkie` crashes without `libicu`.
- Docker networks (compose, Testcontainers) add and remove interfaces on the host, whose MAC addresses are part of
  the *host's* `pkie` activation: tests may then fail with "Your hardware has changed since PKI Express was
  activated"; rerun them, or renew the host activation with `sudo pkie activate`.
- MinIO no longer publishes images (repository archived in April 2026): compose and tests use Chainguard's
  `cgr.dev/chainguard/minio:latest`, free only as `latest`. Its `/data` must be a volume (or tmpfs in tests), or
  MinIO logs "Rename across devices not allowed". PostgreSQL 18 images keep data under `/var/lib/postgresql`.
- Spring Boot 4: the app's JSON uses Jackson 3 (`tools.jackson.*`) while pki-express uses Jackson 2 internally;
  `@AutoConfigureMockMvc` lives in `org.springframework.boot.webmvc.test.autoconfigure`; use
  `org.jspecify.annotations.Nullable`.

## Conventions

Commits follow Conventional Commits in English (scopes in use: `signature`, `validation`), one logical change per
commit, each commit passing the tests on its own.

Flyway migrations are named `V<yyyyMMddHHmmss>__<description>.sql`, the timestamp in UTC (`date -u +%Y%m%d%H%M%S`)
and the description in English snake_case starting with a verb
(`V20260926184059__create_document_and_signature.sql`). Never rename or edit an applied one.
`spring.flyway.out-of-order` is on, so a migration from a parallel branch with an older timestamp still runs:
migrations must not depend on others written at the same time.
