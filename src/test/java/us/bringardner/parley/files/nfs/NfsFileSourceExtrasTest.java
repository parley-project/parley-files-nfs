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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceUser;
import us.bringardner.parley.files.IRandomAccessStream;
import us.bringardner.parley.files.ISeekableInputStream;
import us.bringardner.parley.files.lock.FileSourceLock;
import us.bringardner.parley.nfs.client.NfsClient;
import us.bringardner.parley.nfs.client.NfsLock;
import us.bringardner.parley.nfs.client.NfsLockDeniedException;

/** What is particular to NFS: links, owners, times, locks seen by other clients, and the streams. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class NfsFileSourceExtrasTest {

	private NfsFileSourceFactory factory;
	private FileSource dir;
	private int count;

	@BeforeAll
	void connect() throws Exception {
		factory = TestNfsServer.connect();
	}

	@BeforeEach
	void newDir() throws Exception {
		dir = factory.createFileSource("/NfsExtras/t" + (count++));
		assertTrue(dir.mkdirs());
	}

	@AfterAll
	void disconnect() throws Exception {
		if( factory != null ) {
			removeAll(factory.createFileSource("/NfsExtras"));
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

	private static void write(FileSource f, String text) throws IOException {
		try (OutputStream out = f.getOutputStream()) {
			out.write(text.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static String read(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private NfsClient otherClient() throws IOException {
		java.util.Properties p = factory.getConnectProperties();
		return new NfsClient("127.0.0.1", Integer.parseInt(p.getProperty("port")), "/",
				Long.parseLong(p.getProperty("uid")), Long.parseLong(p.getProperty("gid")), 10000);
	}

	@Test
	public void aSymbolicLinkHoldsItsText() throws Exception {
		FileSource target = dir.getChild("target.txt");
		write(target, "through the link");
		FileSource link = dir.getChild("ln");
		assertSame(link, factory.createSymbolicLink(link, "target.txt"));
		assertEquals("target.txt", link.getLinkText());
		assertEquals(target.getAbsolutePath(), link.getLinkedTo().getAbsolutePath());
		assertEquals("through the link", read(link));
		assertEquals("target.txt".length(), link.length(), "a link's own length is its text's");
		assertEquals(target.length(), link.contentLength());

		assertTrue(link.delete());
		assertTrue(target.exists(), "deleting the link leaves what it led to");
		assertNull(target.getLinkText());
	}

	private static void release(FileSourceLock lock) throws IOException {
		assertNotNull(lock, "should be free");
		lock.release();
	}

	private static void assertSame(FileSource expected, FileSource actual) throws IOException {
		assertEquals(expected.getAbsolutePath(), actual.getAbsolutePath());
	}

	@Test
	public void ownerAndGroupAreTheServers() throws Exception {
		FileSource f = dir.getChild("owned.txt");
		write(f, "x");
		FileSourceUser owner = (FileSourceUser) f.getOwner();
		assertEquals(factory.getUid(), owner.getId(), "the file is ours");
		assertNotNull(f.getGroup().getName());
		assertTrue(f.canRead());
		assertTrue(f.canWrite());
	}

	@Test
	public void timesCanBeSet() throws Exception {
		FileSource f = dir.getChild("timed.txt");
		write(f, "x");
		long time = (System.currentTimeMillis() / 1000 - 86400) * 1000;
		assertTrue(f.setLastModifiedTime(time));
		f.refresh();
		assertEquals(time, f.lastModified(), 1000);
		assertTrue(f.setLastAccessTime(time));
		f.refresh();
		assertEquals(time, f.lastAccessTime(), 1000);
		assertFalse(f.setCreateTime(time), "NFSv4.0 has no way to set it");
	}

	@Test
	public void aLockIsHeldAgainstOtherNfsClients() throws Exception {
		FileSource f = dir.getChild("locked.txt");
		write(f, "0123456789");
		try (NfsClient other = otherClient()) {
			try (FileSourceLock mine = f.tryLock(2, 4, false)) {
				assertNotNull(mine);
				assertThrows(NfsLockDeniedException.class, () -> other.lock(f.getAbsolutePath(), 3, 1, true),
						"inside the range we hold");
				try (NfsLock outside = other.lock(f.getAbsolutePath(), 6, 2, true)) {
					assertTrue(outside.isHeld(), "outside it");
				}
			}
			try (NfsLock now = other.lock(f.getAbsolutePath(), 3, 1, true)) {
				assertTrue(now.isHeld(), "released");
			}
			try (NfsLock theirs = other.lock(f.getAbsolutePath(), 0, NfsLock.TO_END, true)) {
				assertNull(f.tryLock(), "held by the other client, to the end of the file");
				assertTrue(theirs.isHeld());
			}
		}
		release(f.tryLock());
	}

	@Test
	public void aFolderLocksInThisJvmOnly() throws Exception {
		FileSourceLock one = dir.tryLock();
		assertNotNull(one);
		assertThrows(java.nio.channels.OverlappingFileLockException.class, () -> dir.tryLock(),
				"NFS can't lock a folder; this JVM still keeps its own callers apart");
		one.release();
		release(dir.tryLock());
	}

	@Test
	public void appendAndOverwrite() throws Exception {
		FileSource f = dir.getChild("text.txt");
		write(f, "abc");
		try (OutputStream out = f.getOutputStream(true)) {
			out.write("def".getBytes(StandardCharsets.UTF_8));
		}
		assertEquals("abcdef", read(f));
		write(f, "x");
		assertEquals("x", read(f), "a new stream starts the file again");
		assertEquals(1, f.length());
	}

	@Test
	public void aBigFileMakesTheTripBothWays() throws Exception {
		byte[] data = new byte[300_001];
		new java.util.Random(7).nextBytes(data);
		FileSource f = dir.getChild("big.bin");
		try (OutputStream out = f.getOutputStream()) {
			out.write(data, 0, 1000);
			out.write(data, 1000, data.length - 1000);
		}
		assertEquals(data.length, f.length());
		try (InputStream in = f.getInputStream()) {
			assertArrayEquals(data, in.readAllBytes());
		}
		try (InputStream in = f.getInputStream(250_000)) {
			assertEquals(data[250_000] & 0xFF, in.read(), "starts where asked");
		}
	}

	@Test
	public void aStreamOfAMissingFileIsNotFound() throws Exception {
		assertThrows(FileNotFoundException.class, () -> dir.getChild("nope").getInputStream());
		assertThrows(FileNotFoundException.class, () -> dir.getInputStream(), "a folder");
		assertThrows(FileNotFoundException.class, () -> dir.getChild("nope").getRandomAccessStream("r"));
		assertThrows(FileNotFoundException.class, () -> dir.getChild("no/such/parent.txt").getOutputStream());
	}

	@Test
	public void randomAccessWritesBeyondTheEndAndReadsBack() throws Exception {
		FileSource f = dir.getChild("ram.bin");
		try (IRandomAccessStream ram = f.getRandomAccessStream("rw")) {
			ram.seek(100_000);
			ram.write(new byte[] {1, 2, 3});
			assertEquals(100_003, ram.length());
			ram.seek(100_001);
			assertEquals(2, ram.read());
			ram.seek(5);
			assertEquals(0, ram.read(), "the gap is zeros");
		}
		assertEquals(100_003, f.length());
	}

	@Test
	public void aSeekableStreamSeeks() throws Exception {
		FileSource f = dir.getChild("seek.txt");
		write(f, "0123456789");
		ISeekableInputStream in = f.getSeekableInputStream();
		try {
			assertEquals(10, in.length());
			in.seek(7);
			assertEquals('7', in.read());
			in.seek(2);
			assertEquals('2', in.read());
			assertEquals(3, in.getFilePointer());
			in.seek(10);
			assertEquals(-1, in.read());
		} finally {
			in.close();
		}
	}

	@Test
	public void namesAreMadeMovedAndRemoved() throws Exception {
		FileSource a = dir.getChild("a/b/c");
		assertTrue(a.mkdirs());
		assertFalse(a.mkdirs(), "it is there");
		assertFalse(dir.getChild("a/b").delete(), "not empty");
		FileSource moved = dir.getChild("moved");
		assertTrue(a.renameTo(moved));
		assertTrue(moved.isDirectory());
		assertFalse(a.exists());
		FileSource other = dir.getChild("other");
		assertTrue(other.mkdirs());
		assertFalse(moved.renameTo(other), "never replaces");
		String[] names = dir.list();
		java.util.Arrays.sort(names);
		assertArrayEquals(new String[] {"a", "moved", "other"}, names);
		assertNull(dir.getChild("a/b/nope").list());
	}

	@Test
	public void relativeNamesAreBelowTheCurrentDirectory() throws Exception {
		assertEquals("/", factory.getCurrentDirectory().getAbsolutePath());
		assertEquals("/x/y", factory.createFileSource("x/y").getAbsolutePath());
		assertEquals("/", factory.listRoots()[0].getAbsolutePath());
	}
}
