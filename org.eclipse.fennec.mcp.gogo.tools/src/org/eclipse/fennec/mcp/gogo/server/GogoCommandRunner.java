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
package org.eclipse.fennec.mcp.gogo.server;

import java.io.ByteArrayInputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.felix.service.command.CommandProcessor;
import org.apache.felix.service.command.CommandSession;

import reactor.core.publisher.Mono;

/**
 * Runs a single Gogo command in a cancellation-aware way.
 * <p>
 * Felix {@link CommandSession#execute(CharSequence)} is a blocking call that the
 * reactive timeout in the tool provider cannot interrupt on its own: cancelling
 * a {@code Mono.fromCallable} only unsubscribes, while the worker thread keeps
 * running (and buffering) the command to completion — a thread/memory leak for a
 * slow or runaway command. This runner instead executes the command on a
 * dedicated {@link ExecutorService} and, when the returned {@link Mono} is
 * cancelled (e.g. by the provider's request timeout), interrupts the worker and
 * closes the session so the command is actually torn down.
 */
final class GogoCommandRunner {

	/**
	 * Maximum number of Gogo commands allowed to run concurrently per tool.
	 * Bounds thread/memory growth: each in-flight command occupies one worker
	 * thread, so without a cap a burst of concurrent calls could exhaust threads
	 * and memory. Excess calls are rejected rather than queued (a queued command
	 * would already hold an open session), see {@link #run}.
	 */
	static final int MAX_CONCURRENT_COMMANDS = 8;

	/** Result of a completed command: captured streams plus the raw return value. */
	record Output(String stdout, String stderr, Object result, boolean truncated) {
	}

	private GogoCommandRunner() {
	}

	/**
	 * Creates the bounded, interruptible worker pool a tool uses to run its
	 * commands: at most {@link #MAX_CONCURRENT_COMMANDS} threads, direct hand-off
	 * (no queue), daemon threads that die after an idle period. Submissions beyond
	 * the cap are rejected with {@link RejectedExecutionException}.
	 *
	 * @param threadName base name for the worker threads
	 */
	static ExecutorService newBoundedExecutor(String threadName) {
		return new ThreadPoolExecutor(0, MAX_CONCURRENT_COMMANDS, 60L, TimeUnit.SECONDS,
				new SynchronousQueue<>(), runnable -> {
					Thread thread = new Thread(runnable, threadName);
					thread.setDaemon(true);
					return thread;
				});
	}

	/**
	 * Executes {@code command} and completes with its captured {@link Output}.
	 * The returned Mono interrupts the worker and closes the session if it is
	 * cancelled before the command finishes.
	 *
	 * @param processor the Gogo command processor
	 * @param executor  the executor the blocking command runs on
	 * @param command   the command to execute
	 * @param maxBytes  per-stream output cap guarding against unbounded output
	 */
	static Mono<Output> run(CommandProcessor processor, ExecutorService executor, String command, int maxBytes) {
		return Mono.create(sink -> {
			CappedOutputStream out = new CappedOutputStream(maxBytes);
			CappedOutputStream err = new CappedOutputStream(maxBytes);
			PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
			PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
			CommandSession session = processor.createSession(new ByteArrayInputStream(new byte[0]), outStream, errStream);

			Future<?> future;
			try {
				future = executor.submit(() -> {
					try {
						Object result = session.execute(command);
						sink.success(new Output(out.toUtf8(), err.toUtf8(), result, out.isTruncated() || err.isTruncated()));
					} catch (Throwable t) {
						sink.error(t);
					} finally {
						closeQuietly(session, outStream, errStream);
					}
				});
			} catch (RejectedExecutionException rejected) {
				// Concurrency cap reached: tear down the just-created session and fail fast
				// rather than queue (a queued command would hold an open session meanwhile).
				closeQuietly(session, outStream, errStream);
				sink.error(new RejectedExecutionException(
						"Too many concurrent Gogo commands are running (limit " + MAX_CONCURRENT_COMMANDS + "); retry shortly"));
				return;
			}

			// Fires on downstream cancellation, including the provider's request timeout:
			// interrupt the worker and tear down the session so the command stops.
			sink.onCancel(() -> {
				future.cancel(true);
				closeQuietly(session, outStream, errStream);
			});
		});
	}

	private static void closeQuietly(CommandSession session, PrintStream out, PrintStream err) {
		try {
			session.close();
		} catch (RuntimeException ignored) {
			// session teardown is best-effort
		}
		out.close();
		err.close();
	}
}
