# Copyright (c) 2026 Contributors to the Eclipse Foundation.
#
# This program and the accompanying materials are made
# available under the terms of the Eclipse Public License 2.0
# which is available at https://www.eclipse.org/legal/epl-2.0/
#
# SPDX-License-Identifier: EPL-2.0

ARG APP_HOME=/opt/fennec-mcp-inference
# The JVM working directory, which MUST be writable by the runtime user: the framework
# writes its bundle cache below `user.dir` unless told otherwise, and a read-only working
# directory kills the launch before a single bundle starts. Deliberately NOT ${APP_HOME}:
# the application files stay root-owned and read-only to the runtime user.
ARG WORK_DIR=${APP_HOME}/work
ARG TMPDIR=/tmp/fennec-mcp-inference
ARG RUNTIME_JAR=${APP_HOME}/inference.runtime_docker.jar

FROM eclipse-temurin:21-jre-alpine AS builder

ARG APP_HOME
ARG WORK_DIR
ARG TMPDIR
ARG RUNTIME_JAR

RUN set -eux; \
    mkdir -p "${APP_HOME}" "${WORK_DIR}" "${TMPDIR}"; \
    chown -R 65532:65532 "${APP_HOME}" "${TMPDIR}"; \
    chmod -R u+rwX,go-rwx "${WORK_DIR}" "${TMPDIR}"

COPY content/inference.runtime_docker.jar "${RUNTIME_JAR}"

RUN chown -R 65532:65532 "${APP_HOME}"

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
# directory itself as root - which is why ${WORK_DIR} is chowned again rather than relying on
# the builder's chown, and why ${APP_HOME} itself stays root-owned (intentional: read-only
# application files).
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

# --nointeractive: the Gogo shell would otherwise block reading a stdin the container does not
# have, and hold the framework open at shutdown.
ENTRYPOINT ["java", \
    "-Dgosh.args=--nointeractive", \
    "-Djava.io.tmpdir=/tmp/fennec-mcp-inference", \
    "-jar", "/opt/fennec-mcp-inference/inference.runtime_docker.jar"]
