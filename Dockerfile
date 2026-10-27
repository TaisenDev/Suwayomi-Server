# Minimal ARM64-friendly runtime; GitHub Actions builds and supplies server.jar.
FROM eclipse-temurin:25-jre-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates tini \
        libx11-6 libxss1 libxext6 libxrender1 libxcomposite1 libxdamage1 \
        libxkbcommon0 libxtst6 libxcursor1 libglib2.0-0t64 libnss3 libdbus-1-3 \
        libpango-1.0-0 libcairo2 libasound2t64 libatk-bridge2.0-0t64 \
        libcups2t64 libdrm2 libgbm1 libegl1 xvfb xauth \
    && mkdir -p /tmp/.X11-unix \
    && chmod 1777 /tmp/.X11-unix \
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
CMD ["xvfb-run", "--auto-servernum", "--server-args=-screen 0 1280x800x24 -nolisten tcp", "java", "-Dsuwayomi.tachidesk.config.server.systemTrayEnabled=false", "-Dsuwayomi.tachidesk.config.server.initialOpenInBrowserEnabled=false", "-jar", "/home/suwayomi/server.jar"]
