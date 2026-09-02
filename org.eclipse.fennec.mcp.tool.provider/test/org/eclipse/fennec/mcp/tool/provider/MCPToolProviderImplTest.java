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
package org.eclipse.fennec.mcp.tool.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.fennec.mcp.api.MCPTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The conversion and dispatch every MCP tool in this framework goes through.
 * <p>
 * {@code MCPToolProviderImpl} is what turns an {@link MCPTool} service into the SDK's
 * {@link AsyncToolSpecification}: the schemas a client is shown, the timeout a hanging
 * tool is cut off at, and the change notification a bound server needs to stay current all
 * come from here. Exercised directly rather than through a running server, so the timeout
 * can be driven in virtual time and a failure points at this class rather than at the
 * transport in front of it.
 */
class MCPToolProviderImplTest {

	private static final String INPUT_SCHEMA = """
			{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""";
	private static final String OUTPUT_SCHEMA = """
			{"type":"object","properties":{"echoed":{"type":"string"}}}""";

	private final McpAsyncServerExchange exchange = mock(McpAsyncServerExchange.class);

	@Test
	@DisplayName("a tool's name, description and input schema reach the specification")
	void toolIdentityIsConverted() {
		MCPToolProviderImpl provider = provider();
		provider.addTool(new StubTool("echo", "Echoes back", INPUT_SCHEMA, null,
				arguments -> Mono.just(text("ok"))));

		Tool tool = provider.getMCPTools().get(0).tool();

		assertThat(tool.name()).isEqualTo("echo");
		assertThat(tool.description()).isEqualTo("Echoes back");
		assertThat(tool.inputSchema())
				.containsEntry("required", List.of("text"))
				.containsKey("properties");
	}

	@Test
	@DisplayName("a declared output schema is carried over, and a missing one stays absent")
	void outputSchemaIsOptional() {
		MCPToolProviderImpl provider = provider();
		provider.addTool(new StubTool("with_output", "Has an output schema", INPUT_SCHEMA, OUTPUT_SCHEMA,
				arguments -> Mono.just(text("ok"))));
		provider.addTool(new StubTool("without_output", "Has none", INPUT_SCHEMA, null,
				arguments -> Mono.just(text("ok"))));

		assertThat(specification(provider, "with_output").tool().outputSchema())
				.as("a tool that declares an output schema has to advertise it, or a client "
						+ "never knows its results are structured")
				.isNotNull()
				.extracting(schema -> schema.get("properties"))
				.isNotNull();
		assertThat(specification(provider, "without_output").tool().outputSchema())
				.as("getOutputSchema() == null means the tool has none - not an empty one")
				.isNull();
	}

	@Test
	@DisplayName("calling a tool through the specification runs it and returns its result")
	void callHandlerRunsTheTool() {
		MCPToolProviderImpl provider = provider();
		provider.addTool(new StubTool("echo", "Echoes back", INPUT_SCHEMA, null,
				arguments -> Mono.just(text("echo: " + arguments.get("text")))));

		Mono<CallToolResult> result = specification(provider, "echo").callHandler()
				.apply(exchange, request("echo", Map.of("text", "hello")));

		StepVerifier.create(result)
				.assertNext(called -> assertThat(textOf(called)).isEqualTo("echo: hello"))
				.verifyComplete();
	}

	@Test
	@DisplayName("a tool that fails fails the call rather than being swallowed")
	void callHandlerPropagatesFailure() {
		MCPToolProviderImpl provider = provider();
		provider.addTool(new StubTool("boom", "Fails", INPUT_SCHEMA, null,
				arguments -> Mono.error(new IllegalStateException("tool said no"))));

		StepVerifier.create(specification(provider, "boom").callHandler().apply(exchange, request("boom", Map.of())))
				.verifyErrorSatisfies(error -> assertThat(error).isInstanceOf(IllegalStateException.class)
						.hasMessage("tool said no"));
	}

	@Test
	@DisplayName("a tool that never completes is cut off after a minute")
	void callHandlerTimesOut() {
		MCPToolProviderImpl provider = provider();
		provider.addTool(new StubTool("hangs", "Never completes", INPUT_SCHEMA, null, arguments -> Mono.never()));

		// Virtual time: the timeout is a minute, and the point is that it exists at all -
		// without it a hung tool would hold its session until the transport's own timeout.
		// The wall-clock cap on verify() is deliberate: if virtual time ever stops driving
		// the operator's scheduler, this fails instead of blocking the build forever.
		StepVerifier
				.withVirtualTime(() -> specification(provider, "hangs").callHandler()
						.apply(exchange, request("hangs", Map.of())))
				.expectSubscription()
				.expectNoEvent(Duration.ofSeconds(59))
				.thenAwait(Duration.ofSeconds(2))
				.expectError(TimeoutException.class)
				.verify(Duration.ofSeconds(30));
	}

	@Test
	@DisplayName("a tool is only converted while it is registered")
	void toolsFollowTheRegistrations() {
		MCPToolProviderImpl provider = provider();
		StubTool tool = new StubTool("echo", "Echoes back", INPUT_SCHEMA, null, arguments -> Mono.just(text("ok")));
		provider.addTool(tool);
		assertThat(provider.getMCPTools()).hasSize(1);

		provider.removeTool(tool);

		assertThat(provider.getMCPTools()).isEmpty();
	}

	@Test
	@DisplayName("every listener is notified, and one that throws does not silence the others")
	void allListenersAreNotified() {
		MCPToolProviderImpl provider = provider();
		AtomicInteger first = new AtomicInteger();
		AtomicInteger second = new AtomicInteger();
		// A provider bound by two servers has two listeners; keeping only the last would
		// leave the other server serving its activation-time tool list for good.
		provider.onToolsChanged(() -> {
			first.incrementAndGet();
			throw new IllegalStateException("this listener is broken");
		});
		provider.onToolsChanged(second::incrementAndGet);

		StubTool tool = new StubTool("echo", "Echoes back", INPUT_SCHEMA, null, arguments -> Mono.just(text("ok")));
		provider.addTool(tool);
		provider.removeTool(tool);

		assertThat(first.get()).isEqualTo(2);
		assertThat(second.get()).as("the broken listener must not keep this one from being told").isEqualTo(2);
	}

	@Test
	@DisplayName("a listener is added once and can be removed again")
	void listenersAreAddedOnceAndRemovable() {
		MCPToolProviderImpl provider = provider();
		AtomicInteger calls = new AtomicInteger();
		Runnable listener = calls::incrementAndGet;
		provider.onToolsChanged(listener);
		provider.onToolsChanged(listener);
		provider.onToolsChanged(null);

		provider.addTool(new StubTool("echo", "Echoes back", INPUT_SCHEMA, null, arguments -> Mono.just(text("ok"))));
		assertThat(calls.get()).as("registering the same listener twice must not notify it twice").isEqualTo(1);

		provider.removeToolsChangedListener(listener);
		provider.removeToolsChangedListener(listener);
		provider.removeToolsChangedListener(null);

		provider.addTool(new StubTool("other", "Also fine", INPUT_SCHEMA, null, arguments -> Mono.just(text("ok"))));
		assertThat(calls.get())
				.as("a deactivated server removes its listener; the provider must stop calling it")
				.isEqualTo(1);
	}

	private static MCPToolProviderImpl provider() {
		// The config is an annotation type; only description() is ever read.
		MCPToolProviderConfig config = mock(MCPToolProviderConfig.class);
		when(config.description()).thenReturn("Provider under test");
		return new MCPToolProviderImpl(config);
	}

	private static AsyncToolSpecification specification(MCPToolProviderImpl provider, String name) {
		return provider.getMCPTools().stream()
				.filter(specification -> name.equals(specification.tool().name()))
				.findFirst()
				.orElseThrow();
	}

	private static CallToolRequest request(String name, Map<String, Object> arguments) {
		return CallToolRequest.builder(name).arguments(arguments).build();
	}

	private static CallToolResult text(String text) {
		return CallToolResult.builder().content(List.of(TextContent.builder(text).build())).build();
	}

	private static String textOf(CallToolResult result) {
		List<String> texts = new ArrayList<>();
		result.content().stream()
				.filter(TextContent.class::isInstance)
				.forEach(content -> texts.add(((TextContent) content).text()));
		return String.join("\n", texts);
	}

	/** An {@link MCPTool} whose schemas and behaviour the test dictates. */
	private record StubTool(String name, String description, String inputSchema, String outputSchema,
			java.util.function.Function<Map<String, Object>, Mono<CallToolResult>> behaviour) implements MCPTool {

		@Override
		public String getName() {
			return name;
		}

		@Override
		public String getDescription() {
			return description;
		}

		@Override
		public String getInputSchema() {
			return inputSchema;
		}

		@Override
		public String getOutputSchema() {
			return outputSchema;
		}

		@Override
		public Mono<CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
			return behaviour.apply(arguments);
		}
	}
}
