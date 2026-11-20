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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Properties;
import java.util.stream.Stream;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.test.FileSourceTestSupport;
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.NfsServer;

/**
 * An NFS server of our own, started once per JVM, that exports a folder of this machine's file system
 * (a FileProxy), and a factory connected to it. The folder and the server go when the JVM does.
 */
final class TestNfsServer {

	private static final String[] TEST_FILES = {"AnalyzerDerby.properties", "AnalyzerMySql.properties", "Hotel California.txt"};

	private static NfsServer server;
	private static Path exported;
	private static String testFilesDir;

	private TestNfsServer() {
	}

	static synchronized int port() throws IOException {
		if( server == null ) {
			exported = Files.createTempDirectory("parley-files-nfs-export");
			FileSource root = FileSourceFactory.fileProxyFactory.createFileSource(exported.toString());
			// a short lease: a client that dies (the lock tests kill one) leaves its locks until its lease ends
			System.setProperty("test.files.nfs.leaseTime", "4s");
			NfsServer s = new NfsServer("test.files.nfs", 0);
			s.setExport(new Export("test", root, false));
			s.startAndWait(10000);
			server = s;
			Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(), "TestNfsServer-shutdown"));
		}
		return server.getLocalPort();
	}

	private static void shutdown() {
		try {
			if( server != null ) {
				server.stop();
				server.awaitStopped(10000);
			}
		} catch (Exception e) {
			// the JVM is ending
		}
		if( exported != null ) {
			try (Stream<Path> walk = Files.walk(exported)) {
				walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			} catch (IOException e) {
				// a temporary folder
			}
		}
	}

	/** A local folder with the test files (copied once per run from the class path). */
	static synchronized String testFilesDir() throws IOException {
		if( testFilesDir == null ) {
			Path dir = Paths.get(FileSourceTestSupport.scratchDir("TestFiles"));
			Files.createDirectories(dir);
			for (String name : TEST_FILES) {
				try (java.io.InputStream in = TestNfsServer.class.getResourceAsStream("/TestFiles/"+name)) {
					if( in == null ) {
						throw new IOException("TestFiles/"+name+" isn't on the test class path");
					}
					Files.copy(in, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
				}
			}
			testFilesDir = dir.toString();
		}
		return testFilesDir;
	}

	static Properties properties() throws IOException {
		Properties p = new NfsFileSourceFactory().getConnectProperties();
		p.setProperty(NfsFileSourceFactory.PROP_HOST, "127.0.0.1");
		p.setProperty(NfsFileSourceFactory.PROP_PORT, ""+port());
		// the shared tests compare with what java.io.File says to this process's user, so log in as that user
		p.setProperty(NfsFileSourceFactory.PROP_UID, ""+ownId("unix:uid"));
		p.setProperty(NfsFileSourceFactory.PROP_GID, ""+ownId("unix:gid"));
		return p;
	}

	static boolean isRoot() throws IOException {
		return ownId("unix:uid") == 0;
	}

	private static int ownId(String attribute) throws IOException {
		Path probe = Files.createTempFile("parley-files-nfs-id", ".tmp");
		try {
			return (Integer) Files.getAttribute(probe, attribute);
		} finally {
			Files.deleteIfExists(probe);
		}
	}

	/** A factory connected to the test server. */
	static NfsFileSourceFactory connect() throws IOException {
		NfsFileSourceFactory f = new NfsFileSourceFactory();
		f.setConnectionProperties(properties());
		assertTrue(f.connect(), "The factory did not connect");
		return f;
	}

	/** The folder the server exports, for looking at what the tests did. */
	static Path exported() throws IOException {
		port();
		return exported;
	}
}
