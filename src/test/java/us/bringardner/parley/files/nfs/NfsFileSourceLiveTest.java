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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceWatchEvent;
import us.bringardner.parley.files.FileSourceWatchKey;
import us.bringardner.parley.files.IRandomAccessStream;
import us.bringardner.parley.files.java.file.FileSourcePath;
import us.bringardner.parley.files.lock.FileSourceLock;
import us.bringardner.parley.nfs.client.NfsClient;
import us.bringardner.parley.nfs.client.NfsLock;
import us.bringardner.parley.nfs.client.NfsLockDeniedException;

/**
 * The FileSource against a real NFS server, such as Linux's nfsd. Skipped unless nfs.live.host is set.
 * <p>
 * System properties: nfs.live.host (required), nfs.live.port (2049), nfs.live.mount (/), and for AUTH_SYS nfs.live.uid
 * and nfs.live.gid (1000), a user who may write the export. With nfs.live.auth=krb5, krb5i or krb5p the login is
 * Kerberos: nfs.live.krb5.service (nfs@host), and nfs.live.krb5.principal with nfs.live.krb5.keytab, or neither to use the
 * ticket cache (kinit). Everything happens in a folder of its own, which is removed at the end.
 */
public class NfsFileSourceLiveTest {

	private NfsFileSourceFactory factory;
	private Properties settings;
	private FileSource dir;

	@BeforeEach
	public void connect() throws Exception {
		String host = System.getProperty("nfs.live.host");
		Assumptions.assumeTrue(host != null && !host.isEmpty(), "no nfs.live.host: not testing against a real server");
		settings = new NfsFileSourceFactory().getConnectProperties();
		settings.setProperty("host", host);
		settings.setProperty("port", System.getProperty("nfs.live.port", "2049"));
		settings.setProperty("mount", System.getProperty("nfs.live.mount", "/"));
		settings.setProperty("uid", System.getProperty("nfs.live.uid", "1000"));
		settings.setProperty("gid", System.getProperty("nfs.live.gid", "1000"));
		settings.setProperty("auth", System.getProperty("nfs.live.auth", "sys"));
		settings.setProperty("service", System.getProperty("nfs.live.krb5.service", ""));
		settings.setProperty("principal", System.getProperty("nfs.live.krb5.principal", ""));
		settings.setProperty("keytab", System.getProperty("nfs.live.krb5.keytab", ""));
		factory = new NfsFileSourceFactory();
		factory.setConnectionProperties(settings);
		assertTrue(factory.connect());
		dir = factory.createFileSource("/file-source-live-" + System.currentTimeMillis());
		assertTrue(dir.mkdirs(), "Can't make " + dir + ": is the export writable by this user?");
	}

	@AfterEach
	public void disconnect() throws Exception {
		try {
			if( dir != null ) {
				removeAll(dir);
			}
		} finally {
			if( factory != null ) {
				factory.disConnect();
			}
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

	private static void write(FileSource f, byte[] data) throws IOException {
		try (OutputStream out = f.getOutputStream()) {
			out.write(data);
		}
	}

	private static byte[] read(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return in.readAllBytes();
		}
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	public void filesAreMadeReadListedMovedAndRemoved() throws Exception {
		FileSource a = dir.getChild("a.txt");
		assertTrue(a.createNewFile());
		assertFalse(a.createNewFile(), "it is there");
		write(a, bytes("hello nfs"));
		assertEquals(9, a.length());
		assertArrayEquals(bytes("hello nfs"), read(a));

		try (OutputStream out = a.getOutputStream(true)) {
			out.write(bytes("!"));
		}
		assertArrayEquals(bytes("hello nfs!"), read(a));

		FileSource sub = dir.getChild("sub/deeper");
		assertTrue(sub.mkdirs());
		FileSource moved = sub.getChild("b.txt");
		assertTrue(a.renameTo(moved));
		assertFalse(a.exists());
		assertArrayEquals(bytes("hello nfs!"), read(moved));

		String[] names = dir.list();
		assertArrayEquals(new String[] {"sub"}, names);
		assertFalse(dir.getChild("sub").delete(), "not empty");
		assertTrue(moved.delete());
		assertFalse(moved.exists());
	}

	@Test
	public void aLongFileMakesTheTripBothWays() throws Exception {
		byte[] data = new byte[1_500_000];
		new java.util.Random(11).nextBytes(data);
		FileSource f = dir.getChild("big.bin");
		write(f, data);
		assertEquals(data.length, f.length());
		assertArrayEquals(data, read(f));
	}

	@Test
	public void randomAccessAndSeeking() throws Exception {
		FileSource f = dir.getChild("ram.bin");
		try (IRandomAccessStream ram = f.getRandomAccessStream("rw")) {
			ram.seek(70_000);
			ram.write(new byte[] {1, 2, 3});
			assertEquals(70_003, ram.length());
			ram.seek(70_002);
			assertEquals(3, ram.read());
			ram.setLength(10);
			assertEquals(10, ram.length());
		}
		assertEquals(10, f.length());
	}

	@Test
	public void permissionsOwnerAndTimes() throws Exception {
		FileSource f = dir.getChild("p.txt");
		write(f, bytes("x"));
		assertTrue(f.canRead());
		assertTrue(f.canWrite());
		assertTrue(f.setReadOnly());
		f.refresh();
		assertFalse(f.canOwnerWrite());
		assertTrue(f.setOwnerWritable(true));
		assertNotNull(f.getOwner().getName());
		assertNotNull(f.getGroup().getName());

		long time = (System.currentTimeMillis() / 1000 - 86400) * 1000;
		assertTrue(f.setLastModifiedTime(time));
		f.refresh();
		assertEquals(time, f.lastModified(), 2000);
	}

	@Test
	public void symbolicLinks() throws Exception {
		FileSource target = dir.getChild("t.txt");
		write(target, bytes("through the link"));
		FileSource link = dir.getChild("ln");
		factory.createSymbolicLink(link, "t.txt");
		assertEquals("t.txt", link.getLinkText());
		assertArrayEquals(bytes("through the link"), read(link));
		assertTrue(link.delete());
		assertTrue(target.exists());
	}

	@Test
	public void locksAreHeldAgainstOtherClients() throws Exception {
		FileSource f = dir.getChild("lock.txt");
		write(f, bytes("0123456789"));
		// a second login with the same settings (AUTH_SYS or Kerberos) is another NFS client with its own lock owner
		NfsFileSourceFactory second = new NfsFileSourceFactory();
		second.setConnectionProperties(settings);
		assertTrue(second.connect());
		NfsClient other = second.client();
		try {
			String path = f.getAbsolutePath();
			FileSourceLock mine = f.tryLock(2, 4, false);
			assertNotNull(mine);
			try {
				assertThrows(NfsLockDeniedException.class, () -> other.lock(path, 3, 1, true));
			} finally {
				mine.release();
			}
			try (NfsLock now = other.lock(path, 3, 1, true)) {
				assertTrue(now.isHeld());
			}
		} finally {
			second.disConnect();
		}
		FileSourceLock again = f.tryLock();
		assertNotNull(again);
		again.release();
		assertTrue(Arrays.asList(dir.list()).contains("lock.txt"));
	}

	/** the events of a key, waiting up to a few polling intervals for at least one */
	private static List<String> events(NfsWatchService service, FileSourceWatchKey key) throws Exception {
		List<String> ret = new ArrayList<String>();
		long end = System.currentTimeMillis()+10000;
		while( ret.isEmpty() && System.currentTimeMillis() < end ) {
			FileSourceWatchKey k = service.poll(100, TimeUnit.MILLISECONDS);
			if( k != null ) {
				for (FileSourceWatchEvent e : k.pollEvents()) {
					ret.add(e.kind()+" "+e.name());
				}
				k.reset();
			}
		}
		return ret;
	}

	@Test
	public void aFolderIsWatchedByTheServersChangeAttribute() throws Exception {
		try (NfsWatchService service = new NfsWatchService(Duration.ofMillis(200))) {
			// the server sends a change attribute that moves with the folder, or the watcher lists every time
			Object before = service.changeStamp(dir);
			assertNotNull(before, "the server sends no change attribute for a folder");
			assertEquals(before, service.changeStamp(dir));

			FileSourceWatchKey key = service.register(dir);
			// another client makes the changes: this one has no hand in them
			NfsFileSourceFactory second = new NfsFileSourceFactory();
			second.setConnectionProperties(settings);
			assertTrue(second.connect());
			try {
				FileSource theirs = second.createFileSource(dir.getAbsolutePath()).getChild("seen.txt");
				assertTrue(theirs.createNewFile());
				assertEquals(Arrays.asList("ENTRY_CREATE seen.txt"), events(service, key));
				assertFalse(before.equals(service.changeStamp(dir)), "the change attribute did not move");

				assertTrue(theirs.renameTo(second.createFileSource(dir.getAbsolutePath()).getChild("moved.txt")));
				List<String> renamed = new ArrayList<String>();
				while( renamed.size() < 2 ) {
					List<String> more = events(service, key);
					assertFalse(more.isEmpty(), "the rename was not seen: "+renamed);
					renamed.addAll(more);
				}
				assertTrue(renamed.contains("ENTRY_DELETE seen.txt") && renamed.contains("ENTRY_CREATE moved.txt"), renamed.toString());

				assertTrue(second.createFileSource(dir.getAbsolutePath()).getChild("moved.txt").delete());
				assertEquals(Arrays.asList("ENTRY_DELETE moved.txt"), events(service, key));
			} finally {
				key.cancel();
				second.disConnect();
			}
		}
	}

	/** the names in the events of the next key (java.nio.file), waiting up to ten seconds */
	private static List<String> nioEvents(WatchService service) throws Exception {
		List<String> ret = new ArrayList<String>();
		long end = System.currentTimeMillis()+10000;
		while( ret.isEmpty() && System.currentTimeMillis() < end ) {
			WatchKey k = service.poll(100, TimeUnit.MILLISECONDS);
			if( k != null ) {
				for (WatchEvent<?> e : k.pollEvents()) {
					ret.add(e.kind().name()+" "+e.context());
				}
				k.reset();
			}
		}
		return ret;
	}

	@Test
	public void aFolderIsWatchedThroughJavaNio() throws Exception {
		String saved = System.getProperty("parley.files.watch.pollInterval");
		System.setProperty("parley.files.watch.pollInterval", "200");
		NfsFileSourceFactory second = new NfsFileSourceFactory();
		try {
			Path path = new FileSourcePath(dir);
			try (WatchService service = path.getFileSystem().newWatchService()) {
				path.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);
				// another client makes the changes
				second.setConnectionProperties(settings);
				assertTrue(second.connect());
				FileSource theirs = second.createFileSource(dir.getAbsolutePath()).getChild("nio.txt");
				assertTrue(theirs.createNewFile());
				assertEquals(Arrays.asList("ENTRY_CREATE nio.txt"), nioEvents(service));
				assertTrue(theirs.delete());
				assertEquals(Arrays.asList("ENTRY_DELETE nio.txt"), nioEvents(service));
			}
		} finally {
			second.disConnect();
			if( saved == null ) {
				System.clearProperty("parley.files.watch.pollInterval");
			} else {
				System.setProperty("parley.files.watch.pollInterval", saved);
			}
		}
	}
}
