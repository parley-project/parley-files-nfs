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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.test.FileLikeBehaviorTests;

/** An NFS server, reached through NfsFileSource, acts like a java.io.File. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class NfsFileLikeTest extends FileLikeBehaviorTests {

	private static final AtomicInteger TREES = new AtomicInteger();

	private NfsFileSourceFactory factory;
	private String base;
	private String tree;

	@BeforeAll
	void connect() throws Exception {
		factory = TestNfsServer.connect();
		base = "/NfsFileLikeTest";
		FileSource dir = factory.createFileSource(base);
		if( dir.exists() ) {
			removeAll(dir);
		}
		assertTrue(dir.mkdirs(), "Can't create " + dir);
	}

	@AfterAll
	void disconnect() throws Exception {
		if( factory != null ) {
			removeAll(factory.createFileSource(base));
			factory.disConnect();
		}
	}

	/** Makes directories writable first: a permission case may have left one read-only. */
	private static void removeAll(FileSource f) throws IOException {
		if( f.isDirectory() ) {
			f.setExecutable(true);
			f.setWritable(true);
			FileSource[] kids = f.listFiles();
			if( kids != null ) {
				for(FileSource k : kids) {
					removeAll(k);
				}
			}
		}
		f.delete();
	}

	@Override
	protected void newTree() throws Exception {
		tree = base + "/tree" + TREES.incrementAndGet();
		assertTrue(factory.createFileSource(tree).mkdirs(), "Can't create " + tree);
	}

	@Override
	protected FileSource sourceFor(String relative) throws Exception {
		return factory.createFileSource(tree + "/" + relative);
	}

	/** Hard links aren't offered by this file source. */
	@Override
	protected boolean supportsHardLinks() {
		return false;
	}

	/** A link's own bits are the server's. */
	@Override
	protected Set<PosixFilePermission> ownPermissionsOfALink(FileSource link) throws Exception {
		int mode = factory.client().info(link.getAbsolutePath()).getMode();
		Set<PosixFilePermission> bits = new HashSet<>();
		PosixFilePermission[] all = PosixFilePermission.values();   // OWNER_READ ... OTHERS_EXECUTE
		for (int i = 0; i < all.length; i++) {
			if( (mode & (0400 >> i)) != 0 ) {
				bits.add(all[i]);
			}
		}
		return bits;
	}
}
