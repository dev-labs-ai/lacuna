# syntax=docker/dockerfile:1

# PKI Express needs glibc, and its bundled .NET runtime needs ICU: an Ubuntu (noble) JRE, not Alpine.
ARG JAVA_VERSION=25

FROM eclipse-temurin:${JAVA_VERSION}-jdk-noble AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
# The tests need an activated pkie, so they run outside the image build (./mvnw test) and are not in its context.
RUN --mount=type=cache,target=/root/.m2 ./mvnw --batch-mode --quiet package -Dmaven.test.skip=true \
    && mv target/lacuna-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:${JAVA_VERSION}-jre-noble AS pkie
# https://docs.lacunasoftware.com/en-us/articles/pki-express/setup/linux-ubuntu.html
# A version bump needs the new tarball's checksum: sha256sum pkie-<version>.tar.gz
ARG PKIE_VERSION=1.38.0
ARG PKIE_SHA256=3bf665039954bb1ea8d6757cda849dc99709d266fdaaa9a7ef52d2d8945cf1df
ADD --checksum=sha256:${PKIE_SHA256} https://cdn.lacunasoftware.com/pki-express/linux/pkie-${PKIE_VERSION}.tar.gz /tmp/pkie.tar.gz
RUN mkdir /usr/share/pkie \
    && tar xzf /tmp/pkie.tar.gz -C /usr/share/pkie \
    && chmod 755 /usr/share/pkie/pkie

FROM eclipse-temurin:${JAVA_VERSION}-jre-noble
RUN apt-get update \
    && apt-get install --yes --no-install-recommends libicu74 \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --system lacuna \
    && useradd --system --gid lacuna --create-home lacuna \
    && install --directory --owner lacuna --group lacuna /etc/pkie /var/log/pkie /var/lib/lacuna
COPY --from=pkie /usr/share/pkie/ /usr/share/pkie/
RUN ln -s /usr/share/pkie/pkie /usr/local/bin/pkie
USER lacuna
# pkie is a .NET single-file app that unpacks itself into ~/.net on its first run: do it now, not on the first
# signature of every new container. It exits with "not activated" (17), which is expected at this point.
RUN pkie version >/dev/null 2>&1 || test $? -eq 17 \
    && pkie config --set logDir=/var/log/pkie

WORKDIR /application
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
COPY --chmod=755 docker/entrypoint.sh /usr/local/bin/entrypoint.sh

ENV LACUNA_STORAGE_DIR=/var/lib/lacuna
EXPOSE 8080
ENTRYPOINT ["entrypoint.sh"]
