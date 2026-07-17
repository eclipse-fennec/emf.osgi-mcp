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

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * An {@link OutputStream} that buffers at most {@code cap} bytes and silently
 * discards everything beyond that, remembering that truncation happened. This
 * guards the Gogo tools against a single command whose output would otherwise
 * grow an unbounded {@link ByteArrayOutputStream} until the runtime runs out of
 * memory (e.g. {@code cat} of a huge file, or a command that never stops
 * producing output).
 * <p>
 * Writes never throw for exceeding the cap: the surrounding {@code PrintStream}
 * swallows {@code IOException}s anyway, so signalling would be lost. Callers
 * inspect {@link #isTruncated()} after execution instead.
 */
final class CappedOutputStream extends OutputStream {

	private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
	private final int cap;
	private boolean truncated;

	CappedOutputStream(int cap) {
		this.cap = cap;
	}

	@Override
	public void write(int b) {
		if (delegate.size() < cap) {
			delegate.write(b);
		} else {
			truncated = true;
		}
	}

	@Override
	public void write(byte[] b, int off, int len) {
		int remaining = cap - delegate.size();
		if (remaining <= 0) {
			truncated = true;
			return;
		}
		if (len > remaining) {
			delegate.write(b, off, remaining);
			truncated = true;
		} else {
			delegate.write(b, off, len);
		}
	}

	/**
	 * @return {@code true} if at least one byte was discarded because the cap was reached
	 */
	boolean isTruncated() {
		return truncated;
	}

	/**
	 * @return the buffered (possibly truncated) content decoded as UTF-8
	 */
	String toUtf8() {
		return delegate.toString(StandardCharsets.UTF_8);
	}
}
