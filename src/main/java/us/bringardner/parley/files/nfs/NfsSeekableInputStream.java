/**
 * <PRE>
 *
 * Copyright Tony Bringardner 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 *
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *
 *
 *	@author Tony Bringardner
 *
 *
 */
package us.bringardner.parley.files.nfs;

import java.io.IOException;
import java.io.InputStream;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.ISeekableInputStream;
import us.bringardner.parley.nfs.client.NfsOpenFile;

/**
 * A read-only stream of a file that can seek. It holds the file open on the server and reads a
 * buffer's worth at a time; a seek inside the buffer costs nothing.
 *
 * @since 2.0.0
 */
class NfsSeekableInputStream implements ISeekableInputStream {

	static final int BUFFER_SIZE = 64*1024;

	private final NfsFileSource file;
	private final NfsOpenFile handle;
	private byte[] buffer = new byte[0];
	private long bufferStart;
	private long pointer;
	private long length = -1;
	private boolean closed;

	NfsSeekableInputStream(NfsFileSource file, NfsOpenFile handle) {
		this.file = file;
		this.handle = handle;
	}

	private void checkOpen() throws IOException {
		if( closed ) {
			throw new IOException("Stream closed");
		}
	}

	private boolean buffered() {
		return pointer >= bufferStart && pointer < bufferStart + buffer.length;
	}

	private boolean fill() throws IOException {
		byte[] data = handle.read(pointer, BUFFER_SIZE);
		if( data == null || data.length == 0 ) {
			buffer = new byte[0];
			return false;
		}
		buffer = data;
		bufferStart = pointer;
		return true;
	}

	@Override
	public synchronized int read() throws IOException {
		checkOpen();
		if( !buffered() && !fill() ) {
			return -1;
		}
		return buffer[(int) (pointer++ - bufferStart)] & 0xFF;
	}

	@Override
	public synchronized int read(byte[] data, int off, int len) throws IOException {
		checkOpen();
		java.util.Objects.checkFromIndexSize(off, len, data.length);
		if( len == 0 ) {
			return 0;
		}
		if( !buffered() ) {
			if( len >= BUFFER_SIZE ) {
				byte[] got = handle.read(pointer, len);
				if( got == null || got.length == 0 ) {
					return -1;
				}
				System.arraycopy(got, 0, data, off, got.length);
				pointer += got.length;
				return got.length;
			}
			if( !fill() ) {
				return -1;
			}
		}
		int n = (int) Math.min(len, bufferStart + buffer.length - pointer);
		System.arraycopy(buffer, (int) (pointer - bufferStart), data, off, n);
		pointer += n;
		return n;
	}

	@Override
	public int read(byte[] data) throws IOException {
		return read(data, 0, data.length);
	}

	@Override
	public synchronized void seek(long pos) throws IOException {
		checkOpen();
		if( pos < 0 ) {
			throw new IOException("Negative seek offset");
		}
		pointer = pos;
	}

	@Override
	public synchronized long getFilePointer() throws IOException {
		checkOpen();
		return pointer;
	}

	@Override
	public synchronized long length() throws IOException {
		checkOpen();
		length = handle.size();
		return length;
	}

	@Override
	public FileSource getFile() {
		return file;
	}

	@Override
	public InputStream getInputStream() {
		return new InputStream() {

			@Override
			public int read() throws IOException {
				return NfsSeekableInputStream.this.read();
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				return NfsSeekableInputStream.this.read(b, off, len);
			}

			@Override
			public long skip(long n) throws IOException {
				synchronized (NfsSeekableInputStream.this) {
					checkOpen();
					if( n <= 0 ) {
						return 0;
					}
					// stop at the end of the file, like FileInputStream
					long end = Math.max(handle.size(), pointer);
					long newPos = Math.min(end, pointer + n);
					long skipped = newPos - pointer;
					pointer = newPos;
					return skipped;
				}
			}

			@Override
			public int available() throws IOException {
				synchronized (NfsSeekableInputStream.this) {
					checkOpen();
					if( buffered() ) {
						return (int) (bufferStart + buffer.length - pointer);
					}
					return 0;
				}
			}

			@Override
			public void close() throws IOException {
				NfsSeekableInputStream.this.close();
			}
		};
	}

	@Override
	public synchronized void close() throws IOException {
		if( closed ) {
			return;
		}
		closed = true;
		handle.close();
	}
}
