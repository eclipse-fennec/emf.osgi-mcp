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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link CappedOutputStream} bounds its buffer and flags truncation
 * without ever throwing, so unbounded Gogo command output cannot OOM the runtime.
 */
class CappedOutputStreamTest {

	@Test
	void belowCap_keepsEverything_notTruncated() throws Exception {
		try (CappedOutputStream out = new CappedOutputStream(10)) {
			out.write("hello".getBytes(StandardCharsets.UTF_8), 0, 5);

			assertThat(out.toUtf8()).isEqualTo("hello");
			assertThat(out.isTruncated()).isFalse();
		}
	}

	@Test
	void atCap_keepsExactly_notTruncated() throws Exception {
		try (CappedOutputStream out = new CappedOutputStream(5)) {
			out.write("hello".getBytes(StandardCharsets.UTF_8), 0, 5);

			assertThat(out.toUtf8()).isEqualTo("hello");
			assertThat(out.isTruncated()).isFalse();
		}
	}

	@Test
	void aboveCap_bulkWrite_truncatesAndFlags() throws Exception {
		try (CappedOutputStream out = new CappedOutputStream(5)) {
			out.write("hello world".getBytes(StandardCharsets.UTF_8), 0, 11);

			assertThat(out.toUtf8()).isEqualTo("hello");
			assertThat(out.isTruncated()).isTrue();
		}
	}

	@Test
	void aboveCap_singleByteWrites_truncatesAndFlags() throws Exception {
		try (CappedOutputStream out = new CappedOutputStream(3)) {
			for (byte b : "abcdef".getBytes(StandardCharsets.UTF_8)) {
				out.write(b);
			}

			assertThat(out.toUtf8()).isEqualTo("abc");
			assertThat(out.isTruncated()).isTrue();
		}
	}

	@Test
	void throughPrintStream_neverThrows_andCaps() {
		CappedOutputStream out = new CappedOutputStream(4);
		try (PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8)) {
			ps.print("aaaaaaaaaa");
		}

		assertThat(out.toUtf8()).isEqualTo("aaaa");
		assertThat(out.isTruncated()).isTrue();
	}
}
