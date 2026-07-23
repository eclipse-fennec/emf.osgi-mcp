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
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.fennec.mcp.api.auth.McpPrincipal;
import org.eclipse.fennec.mcp.api.auth.McpTokenVerifier;

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
 *   <li>If a {@link McpTokenVerifier} is wired, every request must carry an
 *       {@code Authorization: Bearer} header and the verifier must accept the
 *       token; the verified {@link McpPrincipal} is exposed as the
 *       {@link McpTokenVerifier#PRINCIPAL_ATTRIBUTE} request attribute. A
 *       verifier exception counts as rejection.</li>
 *   <li>Otherwise, if a bearer token is configured (non-blank), every request
 *       must carry a matching {@code Authorization: Bearer <token>} header.
 *       Comparison is constant-time.</li>
 *   <li>If neither is configured, only requests originating from a loopback
 *       address are allowed — and only when they carry no
 *       {@code X-Forwarded-For}/{@code Forwarded} header (a forwarded loopback
 *       request was relayed by a local reverse proxy on behalf of a remote
 *       client). This keeps the shipped default safe even if an operator binds
 *       the listener to all interfaces without setting a token.</li>
 * </ul>
 * Token and verifier are read through {@link Supplier}s on every request so
 * configuration updates take effect without re-registering the filter.
 */
public class McpAuthenticationFilter implements Filter {

	private static final Logger LOGGER = Logger.getLogger(McpAuthenticationFilter.class.getName());
	private static final String BEARER_PREFIX = "Bearer ";

	private final Supplier<String> tokenSupplier;
	private final Supplier<McpTokenVerifier> verifierSupplier;

	/**
	 * @param tokenSupplier supplies the currently configured bearer token, or
	 *            {@code null}/blank when no token is configured
	 */
	public McpAuthenticationFilter(Supplier<String> tokenSupplier) {
		this(tokenSupplier, () -> null);
	}

	/**
	 * @param tokenSupplier    supplies the currently configured bearer token, or
	 *            {@code null}/blank when no token is configured
	 * @param verifierSupplier supplies the currently wired token verifier, or
	 *            {@code null} when verification falls back to the static token
	 */
	public McpAuthenticationFilter(Supplier<String> tokenSupplier, Supplier<McpTokenVerifier> verifierSupplier) {
		this.tokenSupplier = tokenSupplier;
		this.verifierSupplier = verifierSupplier;
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

		McpTokenVerifier verifier = verifierSupplier.get();
		if (verifier != null) {
			verifyWithService(verifier, httpRequest, httpResponse, chain);
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

		// No token configured: only trust direct loopback callers. A loopback
		// request carrying a forwarding header was relayed by a local reverse
		// proxy for a remote client and does not qualify.
		if (isLoopback(httpRequest.getRemoteAddr()) && !isForwarded(httpRequest)) {
			chain.doFilter(request, response);
		} else {
			reject(httpResponse, HttpServletResponse.SC_FORBIDDEN,
					"Remote access requires a configured authentication token");
		}
	}

	private void verifyWithService(McpTokenVerifier verifier, HttpServletRequest request,
			HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
		String bearer = bearerToken(request);
		if (bearer == null || bearer.isBlank()) {
			reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid bearer token");
			return;
		}
		Optional<McpPrincipal> principal;
		try {
			principal = verifier.verify(bearer, request);
		} catch (RuntimeException e) {
			// fail closed: a broken verifier must never open the endpoint
			LOGGER.log(Level.WARNING, "Token verifier failed; rejecting the request", e);
			principal = Optional.empty();
		}
		if (principal != null && principal.isPresent()) {
			request.setAttribute(McpTokenVerifier.PRINCIPAL_ATTRIBUTE, principal.get());
			chain.doFilter(request, response);
		} else {
			reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid bearer token");
		}
	}

	private static String bearerToken(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		if (header == null || !header.startsWith(BEARER_PREFIX)) {
			return null;
		}
		return header.substring(BEARER_PREFIX.length()).trim();
	}

	private static boolean isForwarded(HttpServletRequest request) {
		return request.getHeader("X-Forwarded-For") != null || request.getHeader("Forwarded") != null;
	}

	private static boolean hasValidBearerToken(HttpServletRequest request, String expected) {
		String presented = bearerToken(request);
		if (presented == null) {
			return false;
		}
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
