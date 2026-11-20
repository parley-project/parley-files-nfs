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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.lock.LockScope;
import us.bringardner.parley.files.test.AbstractLockTests;

/** The shared FileSource lock tests; the locks are the server's, so their scope is the cluster. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class NfsLockTest extends AbstractLockTests {

	private static final AtomicInteger DIRS = new AtomicInteger();

	private NfsFileSourceFactory factory;
	private String base;

	@BeforeAll
	void connect() throws Exception {
		factory = TestNfsServer.connect();
		base = "/NfsLockTest-" + System.nanoTime();
		assertTrue(factory.createFileSource(base).mkdirs(), "Can't create " + base);
	}

	@AfterAll
	void disconnect() throws Exception {
		if( factory != null ) {
			removeAll(factory.createFileSource(base));
			factory.disConnect();
		}
	}

	private static void removeAll(FileSource f) throws java.io.IOException {
		FileSource[] kids = f.isDirectory() ? f.listFiles() : null;
		if( kids != null ) {
			for (FileSource k : kids) {
				removeAll(k);
			}
		}
		f.delete();
	}

	@Override
	protected FileSource newLockDirectory() throws Exception {
		FileSource d = factory.createFileSource(base + "/locks" + DIRS.incrementAndGet());
		assertTrue(d.mkdirs(), "Can't create " + d);
		return d;
	}

	/** Another JVM reaches the same server as an NFS client of its own, so its locks must conflict with ours. */
	@Override
	protected String workerSpec(FileSource source) throws Exception {
		java.util.Properties p = factory.getConnectProperties();
		return TestNfsServer.port() + "|" + p.getProperty(NfsFileSourceFactory.PROP_UID) + "|"
				+ p.getProperty(NfsFileSourceFactory.PROP_GID) + "|" + source.getAbsolutePath();
	}

	@Override
	protected FileSource fromWorkerSpec(String spec) throws Exception {
		String[] parts = spec.split("[|]", 4);
		java.util.Properties p = new NfsFileSourceFactory().getConnectProperties();
		p.setProperty(NfsFileSourceFactory.PROP_HOST, "127.0.0.1");
		p.setProperty(NfsFileSourceFactory.PROP_PORT, parts[0]);
		p.setProperty(NfsFileSourceFactory.PROP_UID, parts[1]);
		p.setProperty(NfsFileSourceFactory.PROP_GID, parts[2]);
		NfsFileSourceFactory f = new NfsFileSourceFactory();
		f.setConnectionProperties(p);
		f.connect();
		return f.createFileSource(parts[3]);
	}

	@Test
	public void nfsLocksAreTheServers() {
		assertEquals(LockScope.CLUSTER, dir.getLockScope());
	}
}
