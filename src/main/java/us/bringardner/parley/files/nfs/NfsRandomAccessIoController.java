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

import java.io.FileNotFoundException;
import java.io.IOException;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.IRandomAccessIoController;
import us.bringardner.parley.nfs.client.NfsOpenFile;

/**
 * Random access to a file on the server: reads and writes at positions through one open file.
 * Reads of nearby bytes come from a buffer; single bytes written one after another are gathered
 * into one write. Nothing is held back past {@link #save()}, {@link #sync()} or {@link #close()}.
 *
 * @since 2.0.0
 */
public class NfsRandomAccessIoController implements IRandomAccessIoController {

	static final int BLOCK = 64*1024;

	private final NfsFileSource file;
	private final boolean readOnly;
	private NfsOpenFile handle;

	private byte[] readBuffer = new byte[0];
	private long readStart;

	private final byte[] writeBuffer = new byte[BLOCK];
	private long writeStart;
	private int writeUsed;

	/**
	 * @param mode as RandomAccessFile: "r", or "rw" ("rws" and "rwd" too) which makes the file when it isn't there
	 */
	public NfsRandomAccessIoController(FileSource file, String mode) throws IOException {
		if( !("r".equals(mode) || "rw".equals(mode) || "rws".equals(mode) || "rwd".equals(mode)) ) {
			throw new IllegalArgumentException("Illegal mode \""+mode+"\" must be one of \"r\", \"rw\", \"rws\", or \"rwd\"");
		}
		if( !(file instanceof NfsFileSource) ) {
			throw new IllegalArgumentException("Not an NFS file: "+file);
		}
		this.file = (NfsFileSource) file;
		this.readOnly = mode.equals("r");
		this.handle = this.file.open(!readOnly, !readOnly && !file.exists());
	}

	@Override
	public boolean checksAccessOnOpen() {
		return true;
	}

	@Override
	public FileSource getFile() {
		return file;
	}

	private NfsOpenFile handle() throws IOException {
		if( handle == null ) {
			throw new IOException("Already closed");
		}
		return handle;
	}

	private void flushWrites() throws IOException {
		if( writeUsed > 0 ) {
			int n = writeUsed;
			writeUsed = 0;
			handle().write(writeStart, writeBuffer, 0, n);
			file.clearAttr();
		}
	}

	private void forgetReads() {
		readBuffer = new byte[0];
	}

	private void needWritable() throws IOException {
		if( readOnly ) {
			throw new IOException("Opened read-only (mode \"r\")");
		}
	}

	@Override
	public synchronized int read(long position) throws IOException {
		if( position < 0 ) {
			throw new IOException("Negative position");
		}
		flushWrites();
		if( position < readStart || position >= readStart + readBuffer.length ) {
			byte[] data = handle().read(position, BLOCK);
			if( data == null || data.length == 0 ) {
				return -1;
			}
			readBuffer = data;
			readStart = position;
		}
		return readBuffer[(int) (position - readStart)] & 0xFF;
	}

	@Override
	public synchronized int read(long position, byte[] b, int off, int len) throws IOException {
		java.util.Objects.checkFromIndexSize(off, len, b.length);
		if( position < 0 ) {
			throw new IOException("Negative position");
		}
		if( len == 0 ) {
			return 0;
		}
		flushWrites();
		if( len >= BLOCK && (position < readStart || position >= readStart + readBuffer.length) ) {
			byte[] data = handle().read(position, len);
			if( data == null || data.length == 0 ) {
				return -1;
			}
			System.arraycopy(data, 0, b, off, data.length);
			return data.length;
		}
		int first = read(position);
		if( first < 0 ) {
			return -1;
		}
		int offset = (int) (position - readStart);
		int n = Math.min(len, readBuffer.length - offset);
		System.arraycopy(readBuffer, offset, b, off, n);
		return n;
	}

	@Override
	public synchronized void write(long position, byte value) throws IOException {
		needWritable();
		if( position < 0 ) {
			throw new IOException("Negative position");
		}
		handle();
		forgetReads();
		if( writeUsed > 0 && (position != writeStart + writeUsed || writeUsed == writeBuffer.length) ) {
			flushWrites();
		}
		if( writeUsed == 0 ) {
			writeStart = position;
		}
		writeBuffer[writeUsed++] = value;
	}

	@Override
	public synchronized void write(long position, byte[] b, int off, int len) throws IOException {
		needWritable();
		java.util.Objects.checkFromIndexSize(off, len, b.length);
		if( position < 0 ) {
			throw new IOException("Negative position");
		}
		flushWrites();
		forgetReads();
		if( len > 0 ) {
			handle().write(position, b, off, len);
			file.clearAttr();
		}
	}

	@Override
	public synchronized long length() throws IOException {
		flushWrites();
		return handle().size();
	}

	@Override
	public synchronized void setLength(long newLength) throws IOException {
		needWritable();
		if( newLength < 0 ) {
			throw new IOException("Negative length");
		}
		flushWrites();
		forgetReads();
		handle().truncate(newLength);
		file.clearAttr();
	}

	@Override
	public synchronized void save() throws IOException {
		flushWrites();
	}

	@Override
	public synchronized void sync() throws IOException {
		flushWrites();
	}

	@Override
	public synchronized void close() throws IOException {
		NfsOpenFile h = handle;
		if( h == null ) {
			return;
		}
		try {
			flushWrites();
		} finally {
			handle = null;
			try {
				h.close();
			} finally {
				((us.bringardner.parley.files.FileSourceFactory) file.getFileSourceFactory()).noteChange();
				file.clearAttr();
			}
		}
	}
}
