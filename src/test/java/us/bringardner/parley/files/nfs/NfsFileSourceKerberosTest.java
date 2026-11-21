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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.IRandomAccessStream;
import us.bringardner.parley.files.lock.FileSourceLock;
import us.bringardner.parley.nfs.client.NfsLock;
import us.bringardner.parley.nfs.client.NfsLockDeniedException;
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.NfsServer;

/**
 * The FileSource logging in with Kerberos (krb5, krb5i and krb5p) to a parley-nfs server that only accepts Kerberos,
 * with a Kerby KDC in this JVM: the FileSource, the client and the server together, with no outside machines.
 * (NfsFileSourceLiveTest does the same against a real server.)
 */
public class NfsFileSourceKerberosTest {

	private static final String PREFIX = "test.files.nfs.krb";
	private static final String ALICE = "alice@"+TestKdc.REALM;
	private static final String[] PROPERTIES = {PREFIX+".sec", PREFIX+".krb5.principal", PREFIX+".krb5.keytab"};

	private static NfsServer server;
	private static Path exported;

	@BeforeAll
	public static void startServer() throws Exception {
		TestKdc.start();
		exported = Files.createTempDirectory("parley-files-nfs-krb-export");
		FileSource root = FileSourceFactory.fileProxyFactory.createFileSource(exported.toString());
		System.setProperty(PREFIX+".sec", "krb5,krb5i,krb5p");
		System.setProperty(PREFIX+".krb5.principal", TestKdc.SERVER);
		System.setProperty(PREFIX+".krb5.keytab", TestKdc.KEYTABS.get(TestKdc.SERVER).getAbsolutePath());
		NfsServer s = new NfsServer(PREFIX, 0);
		s.setExport(new Export("test", root, false));
		s.startAndWait(10000);
		server = s;
	}

	@AfterAll
	public static void stopServer() throws Exception {
		try {
			if( server != null ) {
				server.stop();
				server.awaitStopped(10000);
			}
		} finally {
			for (String p : PROPERTIES) {
				System.clearProperty(p);
			}
			if( exported != null ) {
				try (Stream<Path> walk = Files.walk(exported)) {
					walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
				}
			}
		}
	}

	private static NfsFileSourceFactory connect(String auth) throws IOException {
		Properties p = new NfsFileSourceFactory().getConnectProperties();
		p.setProperty(NfsFileSourceFactory.PROP_HOST, "127.0.0.1");
		p.setProperty(NfsFileSourceFactory.PROP_PORT, ""+server.getLocalPort());
		p.setProperty(NfsFileSourceFactory.PROP_AUTH, auth);
		p.setProperty(NfsFileSourceFactory.PROP_SERVICE, TestKdc.SERVER);
		p.setProperty(NfsFileSourceFactory.PROP_PRINCIPAL, ALICE);
		p.setProperty(NfsFileSourceFactory.PROP_KEYTAB, TestKdc.KEYTABS.get(ALICE).getAbsolutePath());
		NfsFileSourceFactory f = new NfsFileSourceFactory();
		f.setConnectionProperties(p);
		assertTrue(f.connect(), "The "+auth+" login did not connect");
		return f;
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

	private static byte[] read(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return in.readAllBytes();
		}
	}

	/** One scenario in a folder of its own, which is removed at the end, with whichever protection the login names. */
	private void scenario(String auth) throws Exception {
		NfsFileSourceFactory factory = connect(auth);
		try {
			FileSource dir = factory.createFileSource("/"+auth+"-"+System.nanoTime());
			assertTrue(dir.mkdirs());
			try {
				byte[] data = new byte[300_000];
				new Random(auth.hashCode()).nextBytes(data);
				FileSource f = dir.getChild("sub/data.bin");
				assertTrue(f.getParentFile().mkdirs());
				assertTrue(f.createNewFile());
				assertFalse(f.createNewFile());
				try (OutputStream out = f.getOutputStream()) {
					out.write(data);
				}
				assertEquals(data.length, f.length());
				assertArrayEquals(data, read(f));

				try (IRandomAccessStream ram = f.getRandomAccessStream("rw")) {
					ram.seek(10);
					ram.write(new byte[] {7, 8, 9});
					ram.seek(11);
					assertEquals(8, ram.read());
				}

				FileSource moved = dir.getChild("moved.bin");
				assertTrue(f.renameTo(moved));
				assertFalse(f.exists());
				assertEquals(data.length, moved.length());
				assertEquals(1, dir.getChild("sub").list().length+dir.list().length-1);

				// a lock is held against another login (another NFS client with its own lock owner)
				NfsFileSourceFactory second = connect(auth);
				try {
					// (the FileSource of the same JVM would refuse an overlap itself; the second client asks the server)
					String path = moved.getAbsolutePath();
					FileSourceLock mine = moved.tryLock(0, 10, false);
					assertNotNull(mine);
					try {
						assertThrows(NfsLockDeniedException.class, () -> second.client().lock(path, 5, 2, true));
					} finally {
						mine.release();
					}
					try (NfsLock now = second.client().lock(path, 5, 2, true)) {
						assertTrue(now.isHeld());
					}
				} finally {
					second.disConnect();
				}
				assertTrue(moved.delete());
			} finally {
				removeAll(dir);
			}
		} finally {
			factory.disConnect();
		}
	}

	@Test
	public void krb5() throws Exception {
		scenario(NfsFileSourceFactory.AUTH_KRB5);
	}

	@Test
	public void krb5i() throws Exception {
		scenario(NfsFileSourceFactory.AUTH_KRB5I);
	}

	@Test
	public void krb5p() throws Exception {
		scenario(NfsFileSourceFactory.AUTH_KRB5P);
	}

	@Test
	public void aServerThatOnlyTakesKerberosRefusesAuthSys() throws Exception {
		Properties p = new NfsFileSourceFactory().getConnectProperties();
		p.setProperty(NfsFileSourceFactory.PROP_HOST, "127.0.0.1");
		p.setProperty(NfsFileSourceFactory.PROP_PORT, ""+server.getLocalPort());
		p.setProperty(NfsFileSourceFactory.PROP_AUTH, NfsFileSourceFactory.AUTH_SYS);
		NfsFileSourceFactory f = new NfsFileSourceFactory();
		try {
			f.setConnectionProperties(p);
			assertThrows(IOException.class, () -> f.connect());
		} finally {
			f.disConnect();
		}
	}

	@Test
	public void aWrongKeytabCannotLogIn() throws Exception {
		Properties p = new NfsFileSourceFactory().getConnectProperties();
		p.setProperty(NfsFileSourceFactory.PROP_HOST, "127.0.0.1");
		p.setProperty(NfsFileSourceFactory.PROP_PORT, ""+server.getLocalPort());
		p.setProperty(NfsFileSourceFactory.PROP_AUTH, NfsFileSourceFactory.AUTH_KRB5);
		p.setProperty(NfsFileSourceFactory.PROP_SERVICE, TestKdc.SERVER);
		p.setProperty(NfsFileSourceFactory.PROP_PRINCIPAL, "nobody@"+TestKdc.REALM);
		p.setProperty(NfsFileSourceFactory.PROP_KEYTAB, TestKdc.KEYTABS.get(ALICE).getAbsolutePath());
		NfsFileSourceFactory f = new NfsFileSourceFactory();
		try {
			f.setConnectionProperties(p);
			assertThrows(IOException.class, () -> f.connect());
		} finally {
			f.disConnect();
		}
	}
}
