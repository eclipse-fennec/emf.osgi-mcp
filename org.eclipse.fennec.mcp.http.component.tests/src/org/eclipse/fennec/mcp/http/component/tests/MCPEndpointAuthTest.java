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
package org.eclipse.fennec.mcp.http.component.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.fennec.mcp.api.MCPServer;
import org.eclipse.fennec.mcp.api.auth.McpPrincipal;
import org.eclipse.fennec.mcp.api.auth.McpTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.common.annotation.InjectService;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.osgi.test.junit5.service.ServiceExtension;

import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Checks that the authentication filter actually guards the MCP endpoint over HTTP.
 * <p>
 * {@code McpAuthenticationFilterTest} in {@code mcp.api} covers the filter's decisions
 * with a mocked request; what no unit test can see is whether the filter was registered on
 * <em>this</em> endpoint's pattern and HTTP runtime, and therefore whether it runs at all.
 * A pattern or target mismatch leaves the filter perfectly registered and the endpoint
 * wide open, and every service-level assertion still passes. So these tests go through the
 * socket and read the status code.
 */
@ExtendWith(BundleContextExtension.class)
@ExtendWith(ServiceExtension.class)
public class MCPEndpointAuthTest extends AbstractMCPServerTest {

	private static final String GROUP = "auth";
	private static final String PROVIDER_NAME = "auth-test-provider";
	private static final String SERVER_NAME = "auth-test-server";
	private static final String SERVLET_PATTERN = "/test/auth/mcp";
	private static final String TOKEN = "s3cret-test-token";
	/** Selects this test's verifier only, so no other server binds it. */
	private static final String VERIFIER_ID = "auth-test-verifier";

	@BeforeEach
	void startServer(@InjectBundleContext BundleContext context,
			@InjectService(timeout = TIMEOUT_MS) ConfigurationAdmin cm) throws IOException {
		registerTool(context, TestMCPTool.echo("auth_echo"), GROUP);
		createToolProvider(cm, "auth", PROVIDER_NAME, GROUP, 1);
		Dictionary<String, Object> properties = serverProperties(SERVER_NAME, SERVLET_PATTERN, PROVIDER_NAME);
		properties.put("auth.token", TOKEN);
		properties.put("verifier.target", "(verifier.id=" + VERIFIER_ID + ")");
		createServer(cm, "auth", properties);
		awaitService(context, MCPServer.class, "(server.name=" + SERVER_NAME + ")");
	}

	@Test
	@DisplayName("a request without a bearer token is rejected although it comes from loopback")
	public void requestWithoutTokenIsRejected(@InjectBundleContext BundleContext context) throws Exception {
		HttpResponse<String> response = postInitialize(context, SERVLET_PATTERN, null);

		assertThat(response.statusCode())
				.as("a configured auth.token must override the loopback allowance")
				.isEqualTo(401);
		assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
	}

	@Test
	@DisplayName("a request with the wrong bearer token is rejected")
	public void requestWithWrongTokenIsRejected(@InjectBundleContext BundleContext context) throws Exception {
		HttpResponse<String> response = postInitialize(context, SERVLET_PATTERN, TOKEN + "-not");

		assertThat(response.statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("a request with the configured bearer token reaches the MCP transport")
	public void requestWithTheRightTokenIsLetThrough(@InjectBundleContext BundleContext context) throws Exception {
		HttpResponse<String> response = postInitialize(context, SERVLET_PATTERN, TOKEN);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("mcp-session-id"))
				.as("the transport answered, so the filter passed the request on rather than swallowing it")
				.isPresent();
		assertThat(response.body()).contains(SERVER_NAME);
	}

	@Test
	@DisplayName("a whole authenticated session works through the filter chain")
	public void anAuthenticatedClientCanUseTheServer(@InjectBundleContext BundleContext context) {
		McpSyncClient client = mcpClient(context, SERVLET_PATTERN, TOKEN);
		client.initialize();

		assertThat(client.listTools().tools())
				.as("every request of the session - not just initialize - has to pass the filter, "
						+ "including the SSE ones the no-buffering filter also sees")
				.hasSize(1);
	}

	@Test
	@DisplayName("a wired token verifier takes over from the static token")
	public void aWiredVerifierReplacesTheStaticToken(@InjectBundleContext BundleContext context) throws Exception {
		AtomicInteger calls = new AtomicInteger();
		Dictionary<String, Object> properties = new Hashtable<>();
		properties.put("verifier.id", VERIFIER_ID);
		// Accepts a token the static configuration would reject, and rejects the one it
		// would accept: only a verifier that really replaced the static comparison can
		// produce both outcomes.
		ServiceRegistration<McpTokenVerifier> verifier = registerService(context, McpTokenVerifier.class,
				new CountingVerifier(calls), properties);

		awaitCondition("The verifier never took over the endpoint",
				() -> statusOf(context, "verifier-approved") == 200);

		assertThat(postInitialize(context, SERVLET_PATTERN, TOKEN).statusCode())
				.as("the static auth.token is ignored while a verifier is wired")
				.isEqualTo(401);
		assertThat(calls.get()).isPositive();

		verifier.unregister();
		forget(verifier);

		// The reference is DYNAMIC and GREEDY: unbinding must not deactivate the server,
		// it must fall back to the static token.
		awaitCondition("The endpoint did not fall back to the static token after the verifier went away",
				() -> statusOf(context, TOKEN) == 200);
		assertThat(statusOf(context, "verifier-approved"))
				.as("the verifier is gone, so its token must no longer be accepted")
				.isEqualTo(401);
	}

	private static int statusOf(BundleContext context, String bearerToken) {
		try {
			return postInitialize(context, SERVLET_PATTERN, bearerToken).statusCode();
		} catch (IOException e) {
			throw new IllegalStateException("Could not reach the MCP endpoint", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while reaching the MCP endpoint", e);
		}
	}

	/** Accepts exactly one token, and counts how often it was consulted. */
	private static final class CountingVerifier implements McpTokenVerifier {

		private final AtomicInteger calls;

		CountingVerifier(AtomicInteger calls) {
			this.calls = calls;
		}

		@Override
		public Optional<McpPrincipal> verify(String bearerToken, HttpServletRequest request) {
			calls.incrementAndGet();
			return "verifier-approved".equals(bearerToken) ? Optional.of(McpPrincipal.of("test-client"))
					: Optional.empty();
		}
	}
}
