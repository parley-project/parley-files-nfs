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
import java.time.Duration;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.watch.PollingWatchService;
import us.bringardner.parley.nfs.client.NfsException;
import us.bringardner.parley.nfs.protocol.Nfs4;

/**
 * The watch service of nfs:// folders: the polling service of parley-files, except that a folder is listed only when
 * the server's change attribute of it has moved (one small GETATTR a look, not a READDIR), and the clock of the
 * server doesn't matter. A folder the server sends no change attribute for is listed every time.
 *
 * <p>The server's change attribute moves when an entry is added, removed or renamed, even by another client; no
 * directory delegation is needed, and there is none a Linux server tells of changes (parley-nfs D93).
 *
 * @since 2.0.0
 */
class NfsWatchService extends PollingWatchService {

	NfsWatchService() {
		super();
	}

	NfsWatchService(Duration interval) {
		super(interval);
	}

	@Override
	protected Object changeStamp(FileSource folder) throws IOException {
		if( !(folder instanceof NfsFileSource) ) {
			return null;
		}
		try {
			return Long.valueOf(((NfsFileSource) folder).changeId());
		} catch (NfsException e) {
			if( e.getStatus() == Nfs4.NFS4ERR_ATTRNOTSUPP ) {
				return null;
			}
			throw e;
		}
	}
}
