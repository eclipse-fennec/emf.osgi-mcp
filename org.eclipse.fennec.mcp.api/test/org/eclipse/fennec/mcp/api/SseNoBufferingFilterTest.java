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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The two headers that keep a reverse proxy from buffering the MCP SSE stream.
 * <p>
 * Their absence has no local symptom at all - the endpoint works fine when nothing sits in
 * front of it - and shows up only behind nginx or APISIX, as clients tearing down their
 * listening stream and a keep-alive scheduler reporting sessions unavailable. The exact
 * header names are what nginx evaluates, so they are worth pinning by string.
 */
class SseNoBufferingFilterTest {

	private final SseNoBufferingFilter filter = new SseNoBufferingFilter();

	@Test
	@DisplayName("the no-buffering headers are set and the request continues")
	void headersAreSetOnAnHttpResponse() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(response).setHeader("X-Accel-Buffering", "no");
		verify(response).setHeader("Cache-Control", "no-cache");
		verify(chain).doFilter(request, response);
		verifyNoMoreInteractions(response);
	}

	@Test
	@DisplayName("a non-HTTP response is passed on untouched rather than rejected")
	void nonHttpResponseIsPassedOn() throws Exception {
		ServletRequest request = mock(ServletRequest.class);
		ServletResponse response = mock(ServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		// Nothing to set, and nothing to guard either - unlike the authentication filter,
		// this one carries no security decision, so it must not become a gate.
		verify(chain).doFilter(request, response);
		verifyNoMoreInteractions(response);
	}
}
