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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.nfs.server.Export;
import us.bringardner.parley.nfs.server.NfsServer;

/**
 * Extended attributes (RFC 8276, D101) through the FileSource methods, against a server of our own that exports the
 * memory provider, so what the build machine's file system keeps doesn't matter.
 */
public class NfsFileSourceXattrTest {

	private MemoryFileSourceFactory mem;
	private NfsServer server;
	private NfsFileSourceFactory factory;

	@BeforeEach
	void start() throws Exception {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		FileSource root = mem.createFileSource("/export");
		assertTrue(root.mkdirs());
		server = new NfsServer("test.files.nfs.xattr", 0);
		server.setExport(new Export("test", root, false));
		server.startAndWait(10000);
		Properties p = new NfsFileSourceFactory().getConnectProperties();
		p.setProperty(NfsFileSourceFactory.PROP_HOST, "127.0.0.1");
		p.setProperty(NfsFileSourceFactory.PROP_PORT, "" + server.getLocalPort());
		p.setProperty(NfsFileSourceFactory.PROP_UID, "1000");
		p.setProperty(NfsFileSourceFactory.PROP_GID, "1000");
		factory = new NfsFileSourceFactory();
		factory.setConnectionProperties(p);
		assertTrue(factory.connect(), "The factory did not connect");
	}

	@AfterEach
	void stop() throws Exception {
		factory.disConnect();
		server.stop();
		assertTrue(server.awaitStopped(10000));
		mem.disConnect();
	}

	private static byte[] b(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void setGetListRemoveThroughTheFileSource() throws IOException {
		FileSource f = factory.createFileSource("/x.txt");
		assertTrue(f.createNewFile());
		assertTrue(f.isExtendedAttributesSupported());
		assertEquals(Collections.emptyList(), f.getExtendedAttributeNames());
		assertNull(f.getExtendedAttribute("origin"));
		assertFalse(f.removeExtendedAttribute("origin"));

		f.setExtendedAttribute("origin", b("https://example.com/x"));
		f.setExtendedAttribute("tag", new byte[] { 0, 1, (byte) 255 });
		List<String> names = f.getExtendedAttributeNames();
		assertEquals(2, names.size());
		assertTrue(names.containsAll(List.of("origin", "tag")));
		assertArrayEquals(b("https://example.com/x"), f.getExtendedAttribute("origin"));
		assertArrayEquals(new byte[] { 0, 1, (byte) 255 }, f.getExtendedAttribute("tag"));

		f.setExtendedAttribute("origin", b("replaced"));
		assertArrayEquals(b("replaced"), f.getExtendedAttribute("origin"));
		assertTrue(f.removeExtendedAttribute("tag"));
		assertFalse(f.removeExtendedAttribute("tag"));
		assertEquals(List.of("origin"), f.getExtendedAttributeNames());

		// what the provider behind the server holds is the same
		assertArrayEquals(b("replaced"), mem.createFileSource("/export/x.txt").getExtendedAttribute("origin"));
	}

	@Test
	void foldersKeepThem() throws IOException {
		FileSource d = factory.createFileSource("/dir");
		assertTrue(d.mkdirs());
		assertTrue(d.isExtendedAttributesSupported());
		d.setExtendedAttribute("k", b("v"));
		assertArrayEquals(b("v"), d.getExtendedAttribute("k"));
	}

	@Test
	void aMissingFileKeepsNone() throws IOException {
		FileSource f = factory.createFileSource("/nope");
		assertFalse(f.isExtendedAttributesSupported());
		org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
				() -> f.getExtendedAttribute("k"));
	}

	@Test
	void aSymbolicLinkKeepsNone() throws IOException {
		FileSource target = factory.createFileSource("/target.txt");
		assertTrue(target.createNewFile());
		FileSource link = factory.createSymbolicLink(factory.createFileSource("/link"), "target.txt");
		assertTrue(link != null && link.exists());
		assertFalse(link.isExtendedAttributesSupported());
	}
}
