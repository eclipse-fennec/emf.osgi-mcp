/*
 * ******************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Data In Motion Consulting - initial implementation
 * ******************************************************************
 */
package org.eclipse.fennec.mcp.api;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Disables reverse-proxy response buffering for the MCP SSE endpoint.
 * <p>
 * Reverse proxies such as nginx and APISIX buffer upstream responses by default. For a
 * Server-Sent-Events stream this delays (or withholds) keep-alive pings and streamed events,
 * which makes the client tear down its listening stream and leaves the server's keep-alive
 * scheduler reporting the session as unavailable. The {@code X-Accel-Buffering: no} header is
 * evaluated by nginx core (and therefore honoured by APISIX) on the upstream response and turns
 * buffering off for that response only, so the stream is flushed to the client immediately.
 *
 * @author ilenia
 * @since Dec 2, 2025
 */
public class SseNoBufferingFilter implements Filter {

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (response instanceof HttpServletResponse httpResponse) {
			httpResponse.setHeader("X-Accel-Buffering", "no");
			httpResponse.setHeader("Cache-Control", "no-cache");
		}
		chain.doFilter(request, response);
	}
}
