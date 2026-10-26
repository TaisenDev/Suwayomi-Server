# Minimal ARM64-friendly runtime; GitHub Actions builds and supplies server.jar.
FROM eclipse-temurin:25-jre-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates tini \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system suwayomi \
    && useradd --system --gid suwayomi --create-home --home-dir /home/suwayomi --shell /usr/sbin/nologin suwayomi \
    && mkdir -p /home/suwayomi/.local/share/Tachidesk \
    && chown -R suwayomi:suwayomi /home/suwayomi

ENV HOME=/home/suwayomi \
    SUWAYOMI_CLOUDFLARE_DOH=true
WORKDIR /home/suwayomi

COPY --chown=suwayomi:suwayomi server.jar /home/suwayomi/server.jar

USER suwayomi
EXPOSE 4567
VOLUME ["/home/suwayomi/.local/share/Tachidesk"]

ENTRYPOINT ["tini", "--"]
CMD ["java", "-Djava.awt.headless=true", "-Dsuwayomi.tachidesk.config.server.systemTrayEnabled=false", "-Dsuwayomi.tachidesk.config.server.initialOpenInBrowserEnabled=false", "-jar", "/home/suwayomi/server.jar"]
