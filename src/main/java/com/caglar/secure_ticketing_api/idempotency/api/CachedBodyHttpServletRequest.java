package com.caglar.secure_ticketing_api.idempotency.api;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Serves a request body that has already been consumed.
 */
class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

	private final byte[] body;

	CachedBodyHttpServletRequest(HttpServletRequest request, byte[] body) {
		super(request);
		this.body = body;
	}

	@Override
	public ServletInputStream getInputStream() {
		ByteArrayInputStream source = new ByteArrayInputStream(body);

		return new ServletInputStream() {

			@Override
			public int read() {
				return source.read();
			}

			@Override
			public int available() {
				return source.available();
			}

			@Override
			public boolean isFinished() {
				return source.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener listener) {
				throw new UnsupportedOperationException("This body is already buffered");
			}
		};
	}

	@Override
	public BufferedReader getReader() {
		return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
	}

	private Charset charset() {
		String encoding = getCharacterEncoding();
		return encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
	}
}
