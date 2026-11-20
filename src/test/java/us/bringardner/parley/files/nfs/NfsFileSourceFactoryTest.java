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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.ConnectionSetting;
import us.bringardner.parley.files.FileSourceFactory;

/** The settings of a factory: no server needed. */
public class NfsFileSourceFactoryTest {

	@Test
	public void itIsFoundByItsTypeId() {
		FileSourceFactory f = FileSourceFactory.getFileSourceFactory("nfs");
		assertNotNull(f, "ServiceLoader finds the factory");
		assertTrue(f instanceof NfsFileSourceFactory);
		assertEquals("nfs", f.getTypeId());
	}

	@Test
	public void propertiesRoundTrip() {
		NfsFileSourceFactory f = new NfsFileSourceFactory();
		Properties p = new Properties();
		p.setProperty("host", "files.example.com");
		p.setProperty("port", "2999");
		p.setProperty("mount", "/export/data");
		p.setProperty("auth", "krb5p");
		p.setProperty("service", "nfs/files.example.com@EXAMPLE.COM");
		p.setProperty("principal", "tony@EXAMPLE.COM");
		p.setProperty("keytab", "/etc/tony.keytab");
		p.setProperty("delegations", "true");
		p.setProperty("timeout", "12s");
		p.setProperty("attributeCacheTtl", "-1");
		f.setConnectionProperties(p);

		assertEquals("files.example.com", f.getHost());
		assertEquals(2999, f.getPort());
		assertEquals("/export/data", f.getMount());
		assertEquals(NfsFileSourceFactory.AUTH_KRB5P, f.getAuth());
		assertTrue(f.isKerberos());
		assertEquals("nfs/files.example.com@EXAMPLE.COM", f.getService());
		assertEquals("tony@EXAMPLE.COM", f.getPrincipal());
		assertEquals("/etc/tony.keytab", f.getKeytab());
		assertTrue(f.isDelegations());
		assertEquals(12000, f.getTimeout());
		assertEquals(-1, f.getAttributeCacheTtl());

		NfsFileSourceFactory copy = (NfsFileSourceFactory) f.createThreadSafeCopy();
		assertEquals(f.getConnectProperties(), copy.getConnectProperties());
		assertTrue(f.isSameFileSystem(copy));
		assertEquals("nfs://files.example.com:2999", f.getURL());
	}

	@Test
	public void aSettingNotGivenStaysAsItWas() {
		NfsFileSourceFactory f = new NfsFileSourceFactory();
		f.setHost("a");
		f.setUid(501);
		Properties p = new Properties();
		p.setProperty("host", "b");
		f.setConnectionProperties(p);
		assertEquals("b", f.getHost());
		assertEquals(501, f.getUid());
		assertEquals(NfsFileSourceFactory.DEFAULT_PORT, f.getPort());
		assertEquals(NfsFileSourceFactory.AUTH_SYS, f.getAuth());
		assertFalse(f.isKerberos());
	}

	@Test
	public void aUrlGivesHostAndPort() throws Exception {
		NfsFileSourceFactory f = new NfsFileSourceFactory();
		f.setConnectionProperties(new URL("filesource://files.example.com:2999/some/path?sourceType=nfs"));
		assertEquals("files.example.com", f.getHost());
		assertEquals(2999, f.getPort());

		NfsFileSourceFactory g = new NfsFileSourceFactory();
		g.setConnectionProperties(new URL("filesource://files.example.com/x"));
		assertEquals(NfsFileSourceFactory.DEFAULT_PORT, g.getPort());
	}

	@Test
	public void anUnknownLoginIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> new NfsFileSourceFactory().setAuth("ntlm"));
	}

	@Test
	public void theSettingsAreDescribed() {
		boolean host = false, auth = false, keytab = false;
		for(ConnectionSetting s : new NfsFileSourceFactory().getConnectionSettings()) {
			host |= s.key().equals("host");
			auth |= s.key().equals("auth");
			keytab |= s.key().equals("keytab");
		}
		assertTrue(host && auth && keytab);
	}

	@Test
	public void nothingIsKeptSecretBecauseNothingIsASecret() {
		// a keytab is a file name; the key is in the file
		NfsFileSourceFactory f = new NfsFileSourceFactory();
		for(String name : f.getConnectProperties().stringPropertyNames()) {
			assertFalse(f.isSecretProperty(name), name);
		}
		assertNull(f.getService());
	}
}
