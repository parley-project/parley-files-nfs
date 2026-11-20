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
import java.util.ArrayList;
import java.util.List;

import us.bringardner.parley.files.lock.LockBackend;
import us.bringardner.parley.nfs.client.NfsLock;
import us.bringardner.parley.nfs.client.NfsLockDeniedException;

/**
 * Locks a byte range of a file on the server, so every NFS client of the file is held off, not just
 * this JVM. One backend serves one {@link us.bringardner.parley.files.lock.FileSourceLock} request;
 * LockManager keeps the locks of this JVM apart.
 * <p>
 * A size of 0 or Long.MAX_VALUE, or a range that runs past the largest offset, is a lock to the end of
 * the file, however long it grows.
 *
 * @since 2.0.0
 */
class NfsLockBackend implements LockBackend {

	private final NfsFileSourceFactory factory;
	private final String path;
	private final List<Held> held = new ArrayList<Held>();

	private static final class Held {
		final long position;
		final long size;
		final NfsLock lock;

		Held(long position, long size, NfsLock lock) {
			this.position = position;
			this.size = size;
			this.lock = lock;
		}
	}

	NfsLockBackend(NfsFileSourceFactory factory, String path) {
		this.factory = factory;
		this.path = path;
	}

	static long lengthOf(long position, long size) {
		if( size <= 0 || size == Long.MAX_VALUE || position + size < 0 ) {
			return NfsLock.TO_END;
		}
		return size;
	}

	@Override
	public boolean acquire(long position, long size, boolean shared) throws IOException {
		try {
			NfsLock lock = factory.client().lock(path, position, lengthOf(position, size), !shared);
			synchronized (held) {
				held.add(new Held(position, size, lock));
			}
			return true;
		} catch (NfsLockDeniedException e) {
			return false;
		}
	}

	@Override
	public void release(long position, long size) {
		Held found = null;
		synchronized (held) {
			for(Held h : held) {
				if( h.position == position && h.size == size ) {
					found = h;
					break;
				}
			}
			if( found != null ) {
				held.remove(found);
			}
		}
		if( found != null ) {
			try {
				found.lock.unlock();
			} catch (IOException e) {
				// the server forgets a lock when its lease ends: nothing more to release
			}
		}
	}

	@Override
	public String toString() {
		return "NFS locks on "+path;
	}
}
