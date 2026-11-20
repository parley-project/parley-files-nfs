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

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.IRandomAccessIoController;
import us.bringardner.parley.files.test.FileSourceRandomAccessIoBufferTests;

/** The shared random access I/O controller tests against our own NFS server. */
public class NfsRandomAccessIoBufferTests extends FileSourceRandomAccessIoBufferTests {

	@BeforeAll
	public static void setUp() throws IOException {
		remoteTestFileDirPath = "NfsRandomAccessIoBufferTests";
		factory = TestNfsServer.connect();
	}

	@AfterAll
	public static void tearDown() throws IOException {
		if( factory != null ) {
			factory.disConnect();
		}
	}

	@Override
	protected IRandomAccessIoController getRandomAccessFileStream(FileSource file) throws IOException {
		return new NfsRandomAccessIoController(file, "rw");
	}
}
