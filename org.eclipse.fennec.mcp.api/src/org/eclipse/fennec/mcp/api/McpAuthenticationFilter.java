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
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authentication gate for the MCP HTTP transport servlet. Registered alongside
 * the servlet on the OSGi HTTP Whiteboard so every request to the MCP endpoint
 * is checked before it reaches the {@code CommandSession} / tool dispatch.
 * <p>
 * The gate is intentionally fail-closed for remote callers:
 * <ul>
 *   <li>If a bearer token is configured (non-blank), every request must carry a
 *       matching {@code Authorization: Bearer <token>} header. Comparison is
 *       constant-time.</li>
 *   <li>If no token is configured, only requests originating from a loopback
 *       address are allowed; any non-loopback caller is rejected. This keeps the
 *       shipped default safe even if an operator binds the listener to all
 *       interfaces without setting a token.</li>
 * </ul>
 * The token is read through a {@link Supplier} on every request so configuration
 * updates take effect without re-registering the filter.
 */
public class McpAuthenticationFilter implements Filter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final Supplier<String> tokenSupplier;

	/**
	 * @param tokenSupplier supplies the currently configured bearer token, or
	 *            {@code null}/blank when no token is configured
	 */
	public McpAuthenticationFilter(Supplier<String> tokenSupplier) {
		this.tokenSupplier = tokenSupplier;
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {

		if (!(request instanceof HttpServletRequest httpRequest)
				|| !(response instanceof HttpServletResponse httpResponse)) {
			// Non-HTTP requests can never be authenticated here; deny.
			if (response instanceof HttpServletResponse httpResponse) {
				httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN);
			}
			return;
		}

		String token = tokenSupplier.get();
		boolean tokenConfigured = token != null && !token.isBlank();

		if (tokenConfigured) {
			if (hasValidBearerToken(httpRequest, token)) {
				chain.doFilter(request, response);
			} else {
				reject(httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid bearer token");
			}
			return;
		}

		// No token configured: only trust loopback callers.
		if (isLoopback(httpRequest.getRemoteAddr())) {
			chain.doFilter(request, response);
		} else {
			reject(httpResponse, HttpServletResponse.SC_FORBIDDEN,
					"Remote access requires a configured authentication token");
		}
	}

	private static boolean hasValidBearerToken(HttpServletRequest request, String expected) {
		String header = request.getHeader("Authorization");
		if (header == null || !header.startsWith(BEARER_PREFIX)) {
			return false;
		}
		String presented = header.substring(BEARER_PREFIX.length()).trim();
		return MessageDigest.isEqual(
				presented.getBytes(StandardCharsets.UTF_8),
				expected.getBytes(StandardCharsets.UTF_8));
	}

	private static boolean isLoopback(String remoteAddr) {
		if (remoteAddr == null || remoteAddr.isBlank()) {
			return false;
		}
		try {
			return InetAddress.getByName(remoteAddr).isLoopbackAddress();
		} catch (UnknownHostException e) {
			return false;
		}
	}

	private static void reject(HttpServletResponse response, int status, String message) throws IOException {
		if (status == HttpServletResponse.SC_UNAUTHORIZED) {
			response.setHeader("WWW-Authenticate", "Bearer");
		}
		response.sendError(status, message);
	}
}
