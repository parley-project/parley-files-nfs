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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceWatchEvent;
import us.bringardner.parley.files.FileSourceWatchKey;
import us.bringardner.parley.nfs.client.NfsClient;

/** Watching an nfs:// folder: another client's changes are seen, by the server's change attribute (D97). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class NfsWatchTest {

	private NfsFileSourceFactory factory;
	private FileSource dir;
	private NfsClient other;
	private NfsWatchService service;
	private int count;

	@BeforeAll
	void connect() throws Exception {
		factory = TestNfsServer.connect();
		java.util.Properties p = factory.getConnectProperties();
		other = new NfsClient("127.0.0.1", Integer.parseInt(p.getProperty("port")), "/",
				Long.parseLong(p.getProperty("uid")), Long.parseLong(p.getProperty("gid")), 10000);
	}

	@BeforeEach
	void newDir() throws Exception {
		dir = factory.createFileSource("/NfsWatch/t" + (count++));
		assertTrue(dir.mkdirs());
		service = new NfsWatchService(Duration.ofMillis(50));
	}

	@AfterEach
	void closeService() throws IOException {
		service.close();
	}

	@AfterAll
	void disconnect() throws Exception {
		if( other != null ) {
			other.close();
		}
		if( factory != null ) {
			removeAll(factory.createFileSource("/NfsWatch"));
			factory.disConnect();
		}
	}

	private static void removeAll(FileSource f) throws IOException {
		FileSource[] kids = f.isDirectory() ? f.listFiles() : null;
		if( kids != null ) {
			for(FileSource k : kids) {
				removeAll(k);
			}
		}
		f.delete();
	}

	@Test
	public void theFactoryMakesTheNfsService() throws Exception {
		try (var s = factory.newWatchService()) {
			assertInstanceOf(NfsWatchService.class, s);
		}
	}

	@Test
	public void theChangeStampMovesOnlyWhenTheFolderChanges() throws Exception {
		Object first = service.changeStamp(dir);
		assertNotNull(first);
		assertEquals(first, service.changeStamp(dir));
		dir.getChild("a").createNewFile();
		assertNotEquals(first, service.changeStamp(dir));
		assertNull(service.changeStamp(new us.bringardner.parley.files.memory.MemoryFileSourceFactory().createFileSource("/x")));
	}

	@Test
	public void changesOfAnotherClientAreSeen() throws Exception {
		FileSourceWatchKey key = service.register(dir);
		String path = dir.getAbsolutePath();

		assertTrue(other.createNew(path+"/made"));
		assertEquals("ENTRY_CREATE made", next(key));

		other.rename(path+"/made", path+"/renamed");
		String one = next(key);
		String two = next(key);
		assertTrue(("ENTRY_DELETE made".equals(one) && "ENTRY_CREATE renamed".equals(two))
				|| ("ENTRY_CREATE renamed".equals(one) && "ENTRY_DELETE made".equals(two)), one+", "+two);

		other.remove(path+"/renamed");
		assertEquals("ENTRY_DELETE renamed", next(key));
		key.cancel();
	}

	/** the next event of the key, waiting for the service to look: poll for the key, take its events, reset it */
	private String next(FileSourceWatchKey key) throws Exception {
		long end = System.currentTimeMillis()+10000;
		while( pending.isEmpty() && System.currentTimeMillis() < end ) {
			FileSourceWatchKey k = service.poll(100, TimeUnit.MILLISECONDS);
			if( k != null ) {
				assertEquals(key, k);
				pending.addAll(k.pollEvents());
				k.reset();
			}
		}
		FileSourceWatchEvent e = pending.poll();
		return e == null ? "nothing" : e.kind()+" "+e.name();
	}

	private final java.util.ArrayDeque<FileSourceWatchEvent> pending = new java.util.ArrayDeque<FileSourceWatchEvent>();
}
