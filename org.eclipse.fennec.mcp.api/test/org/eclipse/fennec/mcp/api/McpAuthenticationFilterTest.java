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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Optional;

import org.eclipse.fennec.mcp.api.auth.McpPrincipal;
import org.eclipse.fennec.mcp.api.auth.McpTokenVerifier;
import org.junit.jupiter.api.Test;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Verifies the fail-closed behaviour of {@link McpAuthenticationFilter}: bearer
 * token enforcement when a token is configured, and loopback-only access when it
 * is not.
 *
 * @author Mark Hoffmann
 */
class McpAuthenticationFilterTest {

	private HttpServletRequest request = mock(HttpServletRequest.class);
	private HttpServletResponse response = mock(HttpServletResponse.class);
	private FilterChain chain = mock(FilterChain.class);

	@Test
	void tokenConfigured_validBearer_passesThrough() throws IOException, ServletException {
		when(request.getHeader("Authorization")).thenReturn("Bearer s3cret");

		filterWithToken("s3cret").doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		verify(response, never()).sendError(org.mockito.ArgumentMatchers.anyInt(),
				org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void tokenConfigured_wrongToken_rejectedUnauthorized() throws IOException, ServletException {
		when(request.getHeader("Authorization")).thenReturn("Bearer wrong");

		filterWithToken("s3cret").doFilter(request, response, chain);

		verify(chain, never()).doFilter(request, response);
		verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid bearer token");
		verify(response).setHeader("WWW-Authenticate", "Bearer");
	}

	@Test
	void tokenConfigured_missingHeader_rejectedUnauthorized() throws IOException, ServletException {
		when(request.getHeader("Authorization")).thenReturn(null);

		filterWithToken("s3cret").doFilter(request, response, chain);

		verify(chain, never()).doFilter(request, response);
		verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid bearer token");
	}

	@Test
	void noToken_loopbackCaller_passesThrough() throws IOException, ServletException {
		when(request.getRemoteAddr()).thenReturn("127.0.0.1");

		filterWithToken("").doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		verify(response, never()).sendError(org.mockito.ArgumentMatchers.anyInt(),
				org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void noToken_ipv6LoopbackCaller_passesThrough() throws IOException, ServletException {
		when(request.getRemoteAddr()).thenReturn("::1");

		filterWithToken(null).doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
	}

	@Test
	void noToken_remoteCaller_rejectedForbidden() throws IOException, ServletException {
		when(request.getRemoteAddr()).thenReturn("203.0.113.7");

		filterWithToken("").doFilter(request, response, chain);

		verify(chain, never()).doFilter(request, response);
		verify(response).sendError(HttpServletResponse.SC_FORBIDDEN,
				"Remote access requires a configured authentication token");
	}

	@Test
	void noToken_loopbackButForwarded_rejectedForbidden() throws IOException, ServletException {
		// a loopback request carrying X-Forwarded-For was relayed by a local
		// reverse proxy for a remote client — the loopback exemption must not apply
		when(request.getRemoteAddr()).thenReturn("127.0.0.1");
		when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7");

		filterWithToken("").doFilter(request, response, chain);

		verify(chain, never()).doFilter(request, response);
		verify(response).sendError(HttpServletResponse.SC_FORBIDDEN,
				"Remote access requires a configured authentication token");
	}

	@Test
	void verifier_acceptedToken_passesThroughAndExposesPrincipal() throws IOException, ServletException {
		when(request.getHeader("Authorization")).thenReturn("Bearer jwt-token");
		McpPrincipal principal = McpPrincipal.of("client-a");
		McpTokenVerifier verifier = (token, req) -> "jwt-token".equals(token) ? Optional.of(principal) : Optional.empty();

		filterWithVerifier(verifier).doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		verify(request).setAttribute(McpTokenVerifier.PRINCIPAL_ATTRIBUTE, principal);
	}

	@Test
	void verifier_rejectedToken_rejectedUnauthorized() throws IOException, ServletException {
		when(request.getHeader("Authorization")).thenReturn("Bearer expired");
		McpTokenVerifier verifier = (token, req) -> Optional.empty();

		filterWithVerifier(verifier).doFilter(request, response, chain);

		verify(chain, never()).doFilter(request, response);
		verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid bearer token");
	}

	@Test
	void verifier_missingHeader_rejectedWithoutConsultingVerifier() throws IOException, ServletException {
		when(request.getHeader("Authorization")).thenReturn(null);
		McpTokenVerifier verifier = (token, req) -> {
			throw new AssertionError("verifier must not be consulted without a bearer token");
		};

		filterWithVerifier(verifier).doFilter(request, response, chain);

		verify(chain, never()).doFilter(request, response);
		verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid bearer token");
	}

	@Test
	void verifier_throwing_failsClosed() throws IOException, ServletException {
		when(request.getHeader("Authorization")).thenReturn("Bearer whatever");
		McpTokenVerifier verifier = (token, req) -> {
			throw new IllegalStateException("JWKS unreachable");
		};

		filterWithVerifier(verifier).doFilter(request, response, chain);

		verify(chain, never()).doFilter(request, response);
		verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid bearer token");
	}

	@Test
	void verifier_takesPrecedenceOverStaticToken() throws IOException, ServletException {
		// the static token would match, but the wired verifier decides — and rejects
		when(request.getHeader("Authorization")).thenReturn("Bearer s3cret");
		McpTokenVerifier verifier = (token, req) -> Optional.empty();

		new McpAuthenticationFilter(() -> "s3cret", () -> verifier).doFilter(request, response, chain);

		verify(chain, never()).doFilter(request, response);
		verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid bearer token");
	}

	private static McpAuthenticationFilter filterWithToken(String token) {
		return new McpAuthenticationFilter(() -> token);
	}

	private static McpAuthenticationFilter filterWithVerifier(McpTokenVerifier verifier) {
		return new McpAuthenticationFilter(() -> "", () -> verifier);
	}
}
