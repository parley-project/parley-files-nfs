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
import java.io.OutputStream;

import us.bringardner.parley.nfs.client.NfsOpenFile;

/**
 * Writes a file in buffer-sized pieces. The file is cut to nothing when the stream is opened,
 * unless it appends. What is written is on the server when close() or flush() returns.
 *
 * @since 2.0.0
 */
class NfsOutputStream extends OutputStream {

	static final int BUFFER_SIZE = 64*1024;

	private final NfsFileSource file;
	private final NfsOpenFile handle;
	private final byte[] buffer = new byte[BUFFER_SIZE];
	private int used;
	private long position;
	private boolean closed;

	NfsOutputStream(NfsFileSource file, NfsOpenFile handle, boolean append) throws IOException {
		this.file = file;
		this.handle = handle;
		try {
			if( append ) {
				position = handle.size();
			} else {
				handle.truncate(0);
			}
		} catch (IOException | RuntimeException e) {
			try {
				handle.close();
			} catch (IOException e2) {
				e.addSuppressed(e2);
			}
			throw NfsFileSource.openError(file.getAbsolutePath(), e instanceof IOException ? (IOException) e : new IOException(e));
		}
		file.clearAttr();
	}

	private void checkOpen() throws IOException {
		if( closed ) {
			throw new IOException("Stream closed");
		}
	}

	@Override
	public synchronized void write(int b) throws IOException {
		checkOpen();
		if( used == buffer.length ) {
			drain();
		}
		buffer[used++] = (byte) b;
	}

	@Override
	public synchronized void write(byte[] b, int off, int len) throws IOException {
		checkOpen();
		java.util.Objects.checkFromIndexSize(off, len, b.length);
		if( len >= buffer.length ) {
			drain();
			handle.write(position, b, off, len);
			position += len;
			return;
		}
		if( len > buffer.length - used ) {
			drain();
		}
		System.arraycopy(b, off, buffer, used, len);
		used += len;
	}

	private void drain() throws IOException {
		if( used > 0 ) {
			handle.write(position, buffer, 0, used);
			position += used;
			used = 0;
		}
	}

	@Override
	public synchronized void flush() throws IOException {
		if( !closed ) {
			drain();
		}
	}

	@Override
	public synchronized void close() throws IOException {
		if( closed ) {
			return;
		}
		closed = true;
		try {
			drain();
		} finally {
			try {
				handle.close();
			} finally {
				((us.bringardner.parley.files.FileSourceFactory) file.getFileSourceFactory()).noteChange();
				file.clearAttr();
			}
		}
	}
}
