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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import us.bringardner.parley.files.test.AbstractNioProviderTests;

/** The shared java.nio.file provider tests against our own NFS server. */
public class NfsNioProviderTests extends AbstractNioProviderTests {

	@BeforeAll
	public static void setUp() throws IOException {
		localTestFileDirPath = TestNfsServer.testFilesDir();
		localCacheDirPath = scratchDir("NfsNioProviderTestsCache");
		remoteTestFileDirPath = "NfsNioProviderTests";
		factory = TestNfsServer.connect();
	}

	/** User 0 may run what any class can run, so the check of one class's bit can't match java.nio's answer. */
	@Override
	@org.junit.jupiter.api.Test
	public void testFileAttributes() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeFalse(TestNfsServer.isRoot(), "the superuser's execute rule");
		super.testFileAttributes();
	}

	@AfterAll
	public static void tearDown() throws IOException {
		if( factory != null ) {
			factory.disConnect();
		}
	}
}
