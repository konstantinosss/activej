/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.http;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufStrings;

import java.nio.charset.Charset;
import java.util.Objects;

import static io.activej.bytebuf.ByteBufStrings.SP;
import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static io.activej.http.ContentTypes.lookup;
import static io.activej.http.HttpUtils.*;

/**
 * This is a value class for the Content-Type header value.
 */
public final class ContentType {
	private static final byte[] CHARSET_KEY = encodeAscii("charset");

	final MediaType mime;
	final HttpCharset charset;

	ContentType(MediaType mime, HttpCharset charset) {
		this.mime = mime;
		this.charset = charset;
	}

	public static ContentType of(MediaType mime) {
		return new ContentType(mime, null);
	}

	public static ContentType of(MediaType mime, Charset charset) {
		return lookup(mime, HttpCharset.of(charset));
	}

	static ContentType decode(byte[] bytes, int pos, int length) throws MalformedHttpException {
		// parsing media type
		int end = pos + length;

		pos = skipSpaces(bytes, pos, end);
		int start = pos;
		int hashCodeCI = 0;
		while (pos < end && bytes[pos] != ';') {
			byte b = bytes[pos++];
			hashCodeCI += (b | 0x20);
		}
		MediaType type = MediaTypes.of(hashCodeCI, bytes, start, pos - start);
		pos++;

		// parsing parameters if any (interested in 'charset' only)
		HttpCharset charset = null;
		if (pos < end) {
			pos = skipSpaces(bytes, pos, end);
			start = pos;
			while (pos < end) {
				if (bytes[pos] == '=' && ByteBufStrings.equalsLowerCaseAscii(CHARSET_KEY, bytes, start, pos - start)) {
					pos++;
					start = pos;
					while (pos < end && bytes[pos] != ';') {
						pos++;
					}
					charset = HttpCharset.decode(bytes, start, pos - start);
				} else if (bytes[pos] == ';' && pos + 1 < end) {
					start = skipSpaces(bytes, pos + 1, end);
				}
				pos++;
			}
		}
		return lookup(type, charset);
	}

	static void render(ContentType type, ByteBuf buf) {
		int pos = render(type, buf.array(), buf.tail());
		buf.tail(pos);
	}

	static int render(ContentType type, byte[] container, int pos) {
		pos += MediaTypes.render(type.getMediaType(), container, pos);
		if (type.charset != null) {
			container[pos++] = SEMICOLON;
			container[pos++] = SP;
			for (byte b : CHARSET_KEY) {
				container[pos++] = b;
			}
			container[pos++] = EQUALS;
			pos += HttpCharset.render(type.charset, container, pos);
		}
		return pos;
	}

	public Charset getCharset() throws MalformedHttpException {
		return charset == null ? null : charset.toJavaCharset();
	}

	public MediaType getMediaType() {
		return mime;
	}

	int size() {
		int size = mime.size();
		if (charset != null) {
			size += charset.size();
			size += 10; // '; charset='
		}
		return size;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		ContentType that = (ContentType) o;
		return mime.equals(that.mime) &&
				Objects.equals(charset, that.charset);
	}

	@Override
	public int hashCode() {
		int result = mime.hashCode();
		result = 31 * result + (charset != null ? charset.hashCode() : 0);
		return result;
	}

	@Override
	public String toString() {
		return "ContentType{type=" + mime + ", charset=" + charset + '}';
	}
}
