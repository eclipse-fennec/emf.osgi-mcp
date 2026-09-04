# Copyright (c) 2026 Contributors to the Eclipse Foundation.
#
# This program and the accompanying materials are made
# available under the terms of the Eclipse Public License 2.0
# which is available at https://www.eclipse.org/legal/epl-2.0/
#
# SPDX-License-Identifier: EPL-2.0

# These paths are ARGs so the builder stage and the copies below cannot drift apart - NOT
# deployment knobs. An exec-form ENTRYPOINT does not expand variables, so the java command at
# the bottom repeats the values literally and a `--build-arg APP_HOME=...` would produce an
# image that builds cleanly and then fails to start. Change them in both places, or not at all.
ARG APP_HOME=/opt/fennec-mcp-inference
# The JVM working directory, which MUST be writable by the runtime user: the framework
# writes its bundle cache below `user.dir` unless told otherwise, and a read-only working
# directory kills the launch before a single bundle starts. Deliberately NOT ${APP_HOME}:
# the application files stay root-owned and read-only to the runtime user.
ARG WORK_DIR=${APP_HOME}/work
ARG TMPDIR=/tmp/fennec-mcp-inference
ARG RUNTIME_JAR=${APP_HOME}/inference.runtime_docker.jar

# Two stages, because the runtime image is distroless and carries no shell: mkdir/chown/chmod
# cannot run there, and a directory created by WORKDIR or by an implicit COPY parent is always
# root-owned. A directory writable by uid 65532 can therefore only be staged in from an image
# that has coreutils. This stage contributes no binaries - only correctly owned directories.
FROM eclipse-temurin:21-jre-alpine AS builder

ARG APP_HOME
ARG WORK_DIR
ARG TMPDIR
ARG RUNTIME_JAR

RUN set -eux; \
    mkdir -p "${APP_HOME}" "${WORK_DIR}" "${TMPDIR}"; \
    chown -R 65532:65532 "${APP_HOME}" "${TMPDIR}"; \
    chmod -R u+rwX,go-rwx "${WORK_DIR}" "${TMPDIR}"

# Copied AFTER that chown and deliberately not chowned afterwards: the runtime only ever reads
# the jar, so it stays root-owned - readable, not writable, for uid 65532.
COPY content/inference.runtime_docker.jar "${RUNTIME_JAR}"

FROM gcr.io/distroless/java21-debian12:nonroot

ARG APP_HOME
ARG WORK_DIR
ARG TMPDIR
ARG RUNTIME_JAR

ENV APP_HOME=${APP_HOME} \
    WORK_DIR=${WORK_DIR} \
    TMPDIR=${TMPDIR} \
    RUNTIME_JAR=${RUNTIME_JAR}

# 0.0.0.0, not the 127.0.0.1 default of a local run: bound to loopback inside a container the
# servlet is reachable only from inside it, and the published port answers nothing. Read by
# inference.config's runtime.json through the configadmin interpolation plugin.
#
# This is why MCP_INFERENCE_AUTH_TOKEN stops being optional here. With no token
# McpAuthenticationFilter trusts a direct loopback caller and refuses everything else, so a
# tokenless container is not open - it answers 401 to every request from outside, which is
# safe but useless. Set the token. It also refuses a loopback request carrying
# X-Forwarded-For / Forwarded, the shape any tunnel produces, so exposing the endpoint and
# setting its token are one step and not two.
ENV MCP_INFERENCE_HTTP_HOST=0.0.0.0

# `COPY --from` preserves the ownership of the copied CHILDREN but creates the destination
# directory itself as root. So the first copy already brings ${WORK_DIR} over as uid 65532
# (it is a child of ${APP_HOME}), while ${APP_HOME} itself stays root-owned - intentional,
# these are read-only application files. The ${WORK_DIR} copy restates that ownership rather
# than leaning on the inheritance, because a root-owned working directory kills the launch.
# The ${TMPDIR} copy genuinely needs its --chown: /tmp/fennec-mcp-inference is outside
# ${APP_HOME}, so that COPY is what creates the directory.
COPY --from=builder ${APP_HOME} ${APP_HOME}
COPY --from=builder --chown=65532:65532 ${WORK_DIR} ${WORK_DIR}
COPY --from=builder --chown=65532:65532 ${TMPDIR} ${TMPDIR}

# 8099 HTTP: the only listener. The whiteboard instance is named `inference` and serves the
# single servlet /mcp/inference; the framework's own HTTP service is not configured here, so
# nothing else binds.
EXPOSE 8099

# NOT ${APP_HOME}: that directory is root-owned while the container runs as uid 65532, and the
# framework needs a writable working directory for its bundle cache.
WORKDIR ${WORK_DIR}

# Literal paths, not the ARGs above: exec form does not expand variables (see the ARG block).
# `gosh.args=--nointeractive` is not passed here either - it is a -runproperty baked into the
# exported jar, so the shell stays non-interactive however the jar is started. Without it the
# Gogo shell blocks reading a stdin the container does not have, and holds the framework open
# at shutdown.
ENTRYPOINT ["java", \
    "-Djava.io.tmpdir=/tmp/fennec-mcp-inference", \
    "-jar", "/opt/fennec-mcp-inference/inference.runtime_docker.jar"]
