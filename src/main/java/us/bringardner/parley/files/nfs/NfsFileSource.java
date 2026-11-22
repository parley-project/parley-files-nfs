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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.nio.ByteBuffer;
import java.nio.file.FileSystemException;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.ArrayList;
import java.util.List;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceCapabilities;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.FileSourceFilter;
import us.bringardner.parley.files.FileSourceGroup;
import us.bringardner.parley.files.FileSourceProgress;
import us.bringardner.parley.files.FileSourceRandomAccessStream;
import us.bringardner.parley.files.FileSourceUser;
import us.bringardner.parley.files.IRandomAccessStream;
import us.bringardner.parley.files.ISeekableInputStream;
import us.bringardner.parley.files.fileproxy.FileProxy;
import us.bringardner.parley.files.lock.FileSourceLock;
import us.bringardner.parley.files.lock.LockBackend;
import us.bringardner.parley.files.lock.LockManager;
import us.bringardner.parley.files.lock.LockScope;
import us.bringardner.parley.nfs.client.NfsClient;
import us.bringardner.parley.nfs.client.NfsException;
import us.bringardner.parley.nfs.client.NfsFileInfo;
import us.bringardner.parley.nfs.client.NfsOpenFile;
import us.bringardner.parley.nfs.client.NfsSetAttributes;
import us.bringardner.parley.nfs.protocol.Nfs4;

/**
 * A file or folder on an NFSv4 server, acting like a java.io.File.
 * <p>
 * What the server says about a file is kept for the factory's attribute cache time, and forgotten
 * whenever this factory changes something (see {@link FileSourceFactory#noteChange()}). Like
 * java.io.File, the questions about a file (exists, length, isDirectory, the times, the permission
 * bits) follow a symbolic link to what it points at; delete, rename and the link questions are about
 * the link itself. Whether this user may read, write or run a file is asked of the server (NFS ACCESS),
 * which knows about groups, ACLs and Kerberos identities.
 * <p>
 * A path with a symbolic link in the middle of it is not followed by the server (NFS4ERR_SYMLINK);
 * {@link #getCanonicalPath()} gives the path with the links resolved.
 *
 * @since 2.0.0
 */
public class NfsFileSource implements FileSource {

	private static final long serialVersionUID = 1L;
	private static final int MAX_LINKS = 40;

	private final NfsFileSourceFactory factory;
	private final String path;
	private final String name;

	// what the server said, and when
	private transient boolean loaded;
	private transient NfsFileInfo own;       // the path itself (a link is a link); null: not there
	private transient NfsFileInfo target;    // what it leads to; null: not there or a link to nothing
	private transient String serverPath;    // the path with the links in the middle of it resolved
	private transient String targetPath;    // where a link leads, even to nothing; the path itself if it isn't one
	private transient long cachedAt;
	private transient long seenChange;

	NfsFileSource(NfsFileSourceFactory factory, String path) {
		this.factory = factory;
		this.path = normalize(path);
		this.name = nameOf(this.path);
	}

	private NfsFileSource(NfsFileSourceFactory factory, String parentPath, NfsFileInfo entry) {
		this.factory = factory;
		this.name = entry.getName();
		this.path = join(parentPath, name);
		if( !entry.isLink() ) {
			// a listing has what a lookup would find
			own = entry;
			target = entry;
			serverPath = path;
			targetPath = path;
			loaded = true;
			cachedAt = System.nanoTime();
			seenChange = factory.getChangeCount();
		}
	}

	// ------------------------------------------------------------------ paths

	static String normalize(String p) {
		if( p == null || p.isEmpty() ) {
			return "/";
		}
		p = p.replace('\\', '/');
		StringBuilder sb = new StringBuilder();
		List<String> parts = new ArrayList<String>();
		for(String s : p.split("/")) {
			if( s.isEmpty() || s.equals(".") ) {
				continue;
			}
			if( s.equals("..") ) {
				if( !parts.isEmpty() ) {
					parts.remove(parts.size()-1);
				}
				continue;
			}
			parts.add(s);
		}
		for(String s : parts) {
			sb.append('/').append(s);
		}
		return sb.length() == 0 ? "/" : sb.toString();
	}

	private static String nameOf(String p) {
		return p.equals("/") ? "/" : p.substring(p.lastIndexOf('/')+1);
	}

	private static String parentOf(String p) {
		if( p.equals("/") ) {
			return null;
		}
		int idx = p.lastIndexOf('/');
		return idx <= 0 ? "/" : p.substring(0, idx);
	}

	private static String join(String dir, String child) {
		return dir.equals("/") ? "/"+child : dir+"/"+child;
	}

	/** Where link text points, from the folder that holds the link. */
	private static String resolve(String linkDir, String text) {
		return text.startsWith("/") ? normalize(text) : normalize(linkDir+"/"+text);
	}

	// ------------------------------------------------------------------ what the server says

	private NfsClient client() throws IOException {
		return factory.client();
	}

	private synchronized void expireIfStale() {
		long ttl = factory.getAttributeCacheTtl();
		if( loaded && ttl >= 0 && (ttl == 0 || System.nanoTime() - cachedAt > ttl * 1_000_000L
				|| seenChange != factory.getChangeCount()) ) {
			loaded = false;
		}
	}

	private static boolean missing(NfsException e) {
		return e.getStatus() == Nfs4.NFS4ERR_NOENT || e.getStatus() == Nfs4.NFS4ERR_NOTDIR;
	}

	private static boolean isLinkInPath(NfsException e) {
		return e.getStatus() == Nfs4.NFS4ERR_SYMLINK;
	}

	private NfsFileInfo statOrNull(String p) throws IOException {
		try {
			return client().info(p);
		} catch (NfsException e) {
			if( missing(e) ) {
				return null;
			}
			throw e;
		}
	}

	/** What the server has at a path, and the path to ask it for (a link in the middle of the path is resolved). */
	private static final class Found {
		final NfsFileInfo info;
		final String path;

		Found(NfsFileInfo info, String path) {
			this.info = info;
			this.path = path;
		}
	}

	private Found stat(String p) throws IOException {
		try {
			return new Found(client().info(p), p);
		} catch (NfsException e) {
			if( missing(e) ) {
				return new Found(null, p);
			}
			if( !isLinkInPath(e) ) {
				throw e;
			}
		}
		// the server doesn't follow a link in the middle of a path: ask for the real folder
		String parent = parentOf(p);
		String real = join(parent == null ? "/" : canonical(parent), nameOf(p));
		return new Found(statOrNull(real), real);
	}

	private synchronized void load() throws IOException {
		expireIfStale();
		if( loaded ) {
			return;
		}
		Found self = stat(path);
		Found t = self;
		int hops = 0;
		while( t.info != null && t.info.isLink() ) {
			if( ++hops > MAX_LINKS ) {
				t = new Found(null, t.path);
				break;
			}
			String text;
			try {
				text = client().readLink(t.path);
			} catch (NfsException e) {
				if( missing(e) ) {
					t = new Found(null, t.path);
					break;
				}
				throw e;
			}
			String dir = parentOf(t.path);
			t = stat(resolve(dir == null ? "/" : dir, text));
		}
		own = self.info;
		serverPath = self.path;
		target = t.info;
		targetPath = t.path;
		loaded = true;
		cachedAt = System.nanoTime();
		seenChange = factory.getChangeCount();
	}

	synchronized void clearAttr() {
		loaded = false;
	}

	private synchronized NfsFileInfo target() throws IOException {
		load();
		return target;
	}

	private synchronized NfsFileInfo self() throws IOException {
		load();
		return own;
	}

	/** The path the server's answers about this file are for: the target of a link. */
	private synchronized String realPath() throws IOException {
		load();
		return targetPath;
	}

	/** The server's change attribute of this folder (or of the file a link points at), see NfsClient.changeId. */
	long changeId() throws IOException {
		return factory.client().changeId(realPath());
	}

	/** The path to ask the server about this name itself: a link stays a link, links in the folders are resolved. */
	synchronized String serverPath() throws IOException {
		load();
		return serverPath;
	}

	private void changed() {
		factory.noteChange();
		clearAttr();
	}

	// ------------------------------------------------------------------ the basics

	@Override
	public int compareTo(Object o) {
		String other = o instanceof FileSource ? ((FileSource) o).getAbsolutePath() : o.toString();
		return path.compareTo(other);
	}

	@Override
	public boolean equals(Object obj) {
		if( obj instanceof NfsFileSource ) {
			NfsFileSource f = (NfsFileSource) obj;
			return f.path.equals(path) && f.factory.isSameFileSystem(factory);
		}
		return false;
	}

	@Override
	public int hashCode() {
		return path.hashCode();
	}

	@Override
	public String toString() {
		return path;
	}

	@Override
	public String getContentType() {
		return FileProxy.getContentType(getName());
	}

	@Override
	public FileSourceFactory getFileSourceFactory() {
		return factory;
	}

	@Override
	public String getAbsolutePath() {
		return path;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getParent() {
		return parentOf(path);
	}

	@Override
	public FileSource getParentFile() throws IOException {
		String p = parentOf(path);
		return p == null ? null : new NfsFileSource(factory, p);
	}

	@Override
	public FileSource getChild(String child) throws IOException {
		return new NfsFileSource(factory, path+"/"+child.replace('\\', '/'));
	}

	@Override
	public String getTitle() throws IOException {
		return factory.getHost()+":"+path;
	}

	@Override
	public boolean isHidden() {
		return name.startsWith(".");
	}

	@Override
	public String getCanonicalPath() throws IOException {
		return canonical(path);
	}

	/** The path with every symbolic link resolved, as far as the path exists (a link to nothing stays as it is). */
	private String canonical(String p) throws IOException {
		java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<String>();
		for(String s : normalizeKeepingDots(p)) {
			todo.addLast(s);
		}
		String cur = "/";
		int hops = 0;
		while( !todo.isEmpty() ) {
			String part = todo.pollFirst();
			if( part.isEmpty() || part.equals(".") ) {
				continue;
			}
			if( part.equals("..") ) {
				String up = parentOf(cur);
				cur = up == null ? "/" : up;
				continue;
			}
			String candidate = join(cur, part);
			NfsFileInfo info = statOrNull(candidate);
			if( info != null && info.isLink() ) {
				if( ++hops > MAX_LINKS ) {
					throw new IOException("Too many symbolic links: "+p);
				}
				String text = client().readLink(candidate);
				String where = resolve(cur, text);
				if( !leadsSomewhere(where) ) {
					cur = candidate;   // leads nowhere: the link is as far as the path goes
					continue;
				}
				String[] parts = text.split("/");
				if( text.startsWith("/") ) {
					cur = "/";
				}
				for(int i = parts.length-1; i >= 0; i--) {
					todo.addFirst(parts[i]);
				}
			} else {
				cur = candidate;
			}
		}
		return cur;
	}

	/** Whether something is at the path (a link in the middle of it means the path is worth following). */
	private boolean leadsSomewhere(String p) throws IOException {
		try {
			client().info(p);
			return true;
		} catch (NfsException e) {
			if( isLinkInPath(e) ) {
				return true;
			}
			if( missing(e) ) {
				return false;
			}
			throw e;
		}
	}

	private static String[] normalizeKeepingDots(String p) {
		return p.split("/");
	}

	@Override
	public String getLinkText() throws IOException {
		NfsFileInfo s = self();
		return s != null && s.isLink() ? client().readLink(serverPath()) : null;
	}

	// ------------------------------------------------------------------ extended attributes (RFC 8276, D101)

	/**
	 * The user attributes of an existing file or folder (not a link) on a server that keeps extended attributes for
	 * it (NFSv4.2); null otherwise.
	 */
	@Override
	public UserDefinedFileAttributeView getUserDefinedFileAttributeView() throws IOException {
		NfsFileInfo s = self();
		if( s == null || s.isLink() ) {
			return null;
		}
		String serverPath = serverPath();
		return client().isXattrSupported(serverPath) ? new XattrView(serverPath) : null;
	}

	/** RFC 8276 through the JDK's view: a missing attribute is a FileSystemException, as for local files */
	private class XattrView implements UserDefinedFileAttributeView {
		private final String serverPath;

		XattrView(String serverPath) {
			this.serverPath = serverPath;
		}

		@Override
		public String name() {
			return "user";
		}

		@Override
		public List<String> list() throws IOException {
			return client().listXattrs(serverPath);
		}

		private byte[] value(String name) throws IOException {
			byte[] v = client().getXattr(serverPath, name);
			if( v == null ) {
				throw new FileSystemException(getAbsolutePath(), null, "No such attribute: "+name);
			}
			return v;
		}

		@Override
		public int size(String name) throws IOException {
			return value(name).length;
		}

		@Override
		public int read(String name, ByteBuffer dst) throws IOException {
			byte[] v = value(name);
			if( dst.remaining() < v.length ) {
				throw new FileSystemException(getAbsolutePath(), null, "The buffer is too small for the "+v.length+" bytes of "+name);
			}
			dst.put(v);
			return v.length;
		}

		@Override
		public int write(String name, ByteBuffer src) throws IOException {
			byte[] v = new byte[src.remaining()];
			src.get(v);
			client().setXattr(serverPath, name, v);
			clearAttr();
			return v.length;
		}

		@Override
		public void delete(String name) throws IOException {
			boolean removed = client().removeXattr(serverPath, name);
			clearAttr();
			if( !removed ) {
				throw new FileSystemException(getAbsolutePath(), null, "No such attribute: "+name);
			}
		}
	}

	@Override
	public FileSource getLinkedTo() throws IOException {
		String text = getLinkText();
		if( text == null ) {
			return null;
		}
		String dir = parentOf(serverPath());
		return new NfsFileSource(factory, resolve(dir == null ? "/" : dir, text));
	}

	@Override
	public boolean exists() throws IOException {
		return target() != null;
	}

	@Override
	public boolean isDirectory() throws IOException {
		NfsFileInfo t = target();
		return t != null && t.isDirectory();
	}

	@Override
	public boolean isFile() throws IOException {
		NfsFileInfo t = target();
		return t != null && t.isFile();
	}

	@Override
	public long length() throws IOException {
		// a link's own length is the length of its text; contentLength() is the file's
		NfsFileInfo t = self();
		return t == null ? 0 : Math.max(0, t.getSize());
	}

	@Override
	public long lastModified() throws IOException {
		NfsFileInfo t = target();
		return t == null ? 0 : Math.max(0, t.getModifyTime());
	}

	@Override
	public long lastAccessTime() throws IOException {
		NfsFileInfo t = target();
		return t == null ? 0 : Math.max(0, t.getAccessTime());
	}

	@Override
	public long creationTime() throws IOException {
		NfsFileInfo t = target();
		return t == null ? 0 : Math.max(0, t.getCreateTime());
	}

	@Override
	public long getCreateDate() throws IOException {
		return creationTime();
	}

	@Override
	public synchronized void refresh() throws IOException {
		clearAttr();
		load();
	}

	@Override
	public void dereferenceChilderen() {
		// nothing to release: listings aren't kept
	}

	// ------------------------------------------------------------------ permissions

	/** A mode bit of the name itself: a symbolic link has bits of its own, apart from the file's. */
	private boolean bit(int mask) throws IOException {
		NfsFileInfo t = self();
		return t != null && t.getMode() >= 0 && (t.getMode() & mask) != 0;
	}

	@Override
	public boolean canOwnerRead() throws IOException {
		return bit(0400);
	}

	@Override
	public boolean canOwnerWrite() throws IOException {
		return bit(0200);
	}

	@Override
	public boolean canOwnerExecute() throws IOException {
		return bit(0100);
	}

	@Override
	public boolean canGroupRead() throws IOException {
		return bit(0040);
	}

	@Override
	public boolean canGroupWrite() throws IOException {
		return bit(0020);
	}

	@Override
	public boolean canGroupExecute() throws IOException {
		return bit(0010);
	}

	@Override
	public boolean canOtherRead() throws IOException {
		return bit(0004);
	}

	@Override
	public boolean canOtherWrite() throws IOException {
		return bit(0002);
	}

	@Override
	public boolean canOtherExecute() throws IOException {
		return bit(0001);
	}

	/**
	 * Whether the user this client logs in as may do something to the file. With AUTH_SYS and numeric owner
	 * and group (what a server without a name mapping sends) the mode bits of the owner, group or others
	 * decide, as they do for java.io.File, and user 0 may read and write anything, and run what any class can.
	 * Otherwise (Kerberos, names the client can't match to a user id) there is only the server's word, ACCESS.
	 * Where both are known, both must allow it: the server may say no for a reason the mode doesn't show
	 * (a read-only export, an access control list).
	 *
	 * @param ownerBit 0400, 0200 or 0100: the owner's bit for what is asked
	 */
	private boolean allowed(int ownerBit, int fileMask, int directoryMask) throws IOException {
		NfsFileInfo t = target();
		if( t == null ) {
			return false;
		}
		Boolean byMode = byMode(t, ownerBit);
		if( byMode != null && factory.getUid() == 0 ) {
			return byMode;   // the superuser: the mode says nothing against it
		}
		if( byMode != null && !byMode ) {
			return false;
		}
		int mask = t.isDirectory() ? directoryMask : fileMask;
		try {
			return (client().access(realPath(), mask) & mask) == mask;
		} catch (NfsException e) {
			if( missing(e) ) {
				return false;
			}
			throw e;
		}
	}

	/** What the mode bits say for this user; null when the user can't be matched to the owner or group. */
	private Boolean byMode(NfsFileInfo t, int ownerBit) {
		if( factory.isKerberos() || t.getMode() < 0 ) {
			return null;
		}
		int mode = t.getMode();
		long uid = factory.getUid();
		int owner = idOf(t.getOwner());
		int group = idOf(t.getGroup());
		if( uid == 0 ) {
			if( ownerBit != 0100 ) {
				return true;
			}
			return t.isDirectory() || (mode & 0111) != 0;
		}
		if( owner < 0 || group < 0 ) {
			return null;
		}
		if( uid == owner ) {
			return (mode & ownerBit) != 0;
		}
		if( factory.getGid() == group ) {
			return (mode & (ownerBit >> 3)) != 0;
		}
		return (mode & (ownerBit >> 6)) != 0;
	}

	@Override
	public boolean canRead() throws IOException {
		return allowed(0400, Nfs4.ACCESS4_READ, Nfs4.ACCESS4_READ);
	}

	@Override
	public boolean canWrite() throws IOException {
		return allowed(0200, Nfs4.ACCESS4_MODIFY, Nfs4.ACCESS4_MODIFY);
	}

	@Override
	public boolean canExecute() throws IOException {
		return allowed(0100, Nfs4.ACCESS4_EXECUTE, Nfs4.ACCESS4_LOOKUP);
	}

	/**
	 * Sets and clears mode bits with one SETATTR, starting from the mode the server has now (never the
	 * cached one, which could undo a change made since). False if the file isn't there or the server refuses.
	 */
	private boolean changeMode(int set, int clear) throws IOException {
		clearAttr();
		NfsFileInfo t = target();
		if( t == null ) {
			return false;
		}
		int perm = t.getMode() & 07777;
		int mode = (perm | set) & ~clear;
		if( mode != perm ) {
			try {
				client().setAttributes(realPath(), new NfsSetAttributes().mode(mode));
			} catch (NfsException e) {
				if( refused(e) || missing(e) ) {
					return false;
				}
				throw e;
			} finally {
				changed();
			}
		}
		return true;
	}

	private boolean changeMode(boolean on, int bits) throws IOException {
		return on ? changeMode(bits, 0) : changeMode(0, bits);
	}

	private static boolean refused(NfsException e) {
		int s = e.getStatus();
		return s == Nfs4.NFS4ERR_ACCESS || s == Nfs4.NFS4ERR_PERM || s == Nfs4.NFS4ERR_ROFS;
	}

	@Override
	public boolean setExecutable(boolean b) throws IOException {
		return changeMode(b, 0100);
	}

	@Override
	public boolean setReadable(boolean b) throws IOException {
		return changeMode(b, 0400);
	}

	@Override
	public boolean setWritable(boolean b) throws IOException {
		return changeMode(b, 0200);
	}

	@Override
	public boolean setExecutable(boolean b, boolean ownerOnly) throws IOException {
		return changeMode(b, ownerOnly ? 0100 : 0111);
	}

	@Override
	public boolean setReadable(boolean b, boolean ownerOnly) throws IOException {
		return changeMode(b, ownerOnly ? 0400 : 0444);
	}

	@Override
	public boolean setWritable(boolean b, boolean ownerOnly) throws IOException {
		return changeMode(b, ownerOnly ? 0200 : 0222);
	}

	@Override
	public boolean setOwnerExecutable(boolean b) throws IOException {
		return changeMode(b, 0100);
	}

	@Override
	public boolean setOwnerReadable(boolean b) throws IOException {
		return changeMode(b, 0400);
	}

	@Override
	public boolean setOwnerWritable(boolean b) throws IOException {
		return changeMode(b, 0200);
	}

	@Override
	public boolean setGroupExecutable(boolean b) throws IOException {
		return changeMode(b, 0010);
	}

	@Override
	public boolean setGroupReadable(boolean b) throws IOException {
		return changeMode(b, 0040);
	}

	@Override
	public boolean setGroupWritable(boolean b) throws IOException {
		return changeMode(b, 0020);
	}

	@Override
	public boolean setOtherExecutable(boolean b) throws IOException {
		return changeMode(b, 0001);
	}

	@Override
	public boolean setOtherReadable(boolean b) throws IOException {
		return changeMode(b, 0004);
	}

	@Override
	public boolean setOtherWritable(boolean b) throws IOException {
		return changeMode(b, 0002);
	}

	@Override
	public boolean setReadOnly() throws IOException {
		return changeMode(0, 0222);
	}

	// ------------------------------------------------------------------ owner, group, times

	private static int idOf(String principal) {
		if( principal == null ) {
			return -1;
		}
		int at = principal.indexOf('@');
		String head = at < 0 ? principal : principal.substring(0, at);
		try {
			return Integer.parseInt(head);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	@Override
	public FileSourceUser getOwner() throws IOException {
		NfsFileInfo t = target();
		if( t == null || t.getOwner() == null ) {
			return new FileSourceUser();
		}
		String group = t.getGroup() == null ? "" : t.getGroup();
		return new FileSourceUser(idOf(t.getOwner()), t.getOwner(), idOf(group), group);
	}

	@Override
	public GroupPrincipal getGroup() throws IOException {
		NfsFileInfo t = target();
		if( t == null || t.getGroup() == null ) {
			return new FileSourceGroup();
		}
		return new FileSourceGroup(idOf(t.getGroup()), t.getGroup());
	}

	/** The form the server wants a principal in: a name with a domain as it is, else the numeric id. */
	private static String principalText(String name, int id) {
		if( name != null && name.indexOf('@') > 0 ) {
			return name;
		}
		if( id >= 0 && (name == null || name.isEmpty() || name.equals(""+id)) ) {
			return ""+id;
		}
		return name != null && !name.isEmpty() ? name : ""+id;
	}

	@Override
	public boolean setOwner(UserPrincipal owner) throws IOException {
		if( !(owner instanceof FileSourceUser) ) {
			return false;
		}
		FileSourceUser u = (FileSourceUser) owner;
		return setAttributes(new NfsSetAttributes().owner(principalText(u.getName(), u.getId())));
	}

	@Override
	public boolean setGroup(GroupPrincipal group) throws IOException {
		if( !(group instanceof FileSourceGroup) ) {
			return false;
		}
		FileSourceGroup g = (FileSourceGroup) group;
		return setAttributes(new NfsSetAttributes().group(principalText(g.getName(), g.getId())));
	}

	private boolean setAttributes(NfsSetAttributes a) throws IOException {
		if( target() == null ) {
			return false;
		}
		try {
			client().setAttributes(realPath(), a);
			return true;
		} catch (NfsException e) {
			if( refused(e) || missing(e) ) {
				return false;
			}
			throw e;
		} finally {
			changed();
		}
	}

	@Override
	public boolean setLastModifiedTime(long time) throws IOException {
		return setAttributes(new NfsSetAttributes().modifyTime(time));
	}

	@Override
	public boolean setLastAccessTime(long time) throws IOException {
		return setAttributes(new NfsSetAttributes().accessTime(time));
	}

	/** NFSv4.0 can't set a creation time. */
	@Override
	public boolean setCreateTime(long time) throws IOException {
		return false;
	}

	// ------------------------------------------------------------------ names: make, remove, move

	@Override
	public boolean createNewFile() throws IOException {
		if( self() != null ) {
			return false;
		}
		try {
			return client().createNew(serverPath());
		} finally {
			changed();
		}
	}

	@Override
	public boolean mkdir() throws IOException {
		if( self() != null ) {
			return false;
		}
		try {
			client().mkdir(serverPath());
			return true;
		} catch (NfsException e) {
			if( missing(e) || e.getStatus() == Nfs4.NFS4ERR_EXIST ) {
				return false;
			}
			throw e;
		} finally {
			changed();
		}
	}

	@Override
	public boolean mkdirs() throws IOException {
		if( self() != null ) {
			return false;
		}
		String parent = parentOf(path);
		if( parent != null ) {
			NfsFileSource p = new NfsFileSource(factory, parent);
			if( !p.exists() && !p.mkdirs() && !p.exists() ) {
				return false;
			}
		}
		return mkdir();
	}

	@Override
	public boolean delete() throws IOException {
		NfsFileInfo s = self();
		if( s == null ) {
			return false;   // like java.io.File: nothing to delete
		}
		try {
			client().remove(serverPath());
			return true;
		} catch (NfsException e) {
			if( missing(e) || e.getStatus() == Nfs4.NFS4ERR_NOTEMPTY ) {
				return false;
			}
			throw e;
		} finally {
			changed();
		}
	}

	@Override
	public boolean renameTo(FileSource dest) throws IOException {
		if( !(dest instanceof NfsFileSource) || !factory.isSameFileSystem(dest.getFileSourceFactory()) ) {
			return false;
		}
		NfsFileSource to = (NfsFileSource) dest;
		if( to.path.equals(path) ) {
			return self() != null;   // onto itself: nothing changes
		}
		if( self() == null || to.self() != null ) {
			return false;   // nothing to move, or renameTo never replaces
		}
		try {
			client().rename(serverPath(), to.serverPath());
			return true;
		} catch (NfsException e) {
			int s = e.getStatus();
			if( missing(e) || s == Nfs4.NFS4ERR_EXIST || s == Nfs4.NFS4ERR_NOTEMPTY || s == Nfs4.NFS4ERR_ISDIR ) {
				return false;
			}
			throw e;
		} finally {
			changed();
			if( dest instanceof NfsFileSource ) {
				((NfsFileSource) dest).clearAttr();
			}
		}
	}

	// ------------------------------------------------------------------ listing

	private List<NfsFileInfo> entries() throws IOException {
		NfsFileInfo t = target();
		if( t == null || !t.isDirectory() ) {
			return null;
		}
		List<NfsFileInfo> ret = new ArrayList<NfsFileInfo>();
		for(NfsFileInfo e : client().list(realPath())) {
			String n = e.getName();
			if( n != null && !n.equals(".") && !n.equals("..") ) {
				ret.add(e);
			}
		}
		return ret;
	}

	@Override
	public String[] list() throws IOException {
		List<NfsFileInfo> all = entries();
		if( all == null ) {
			return null;
		}
		String[] ret = new String[all.size()];
		for(int idx = 0; idx < ret.length; idx++) {
			ret[idx] = all.get(idx).getName();
		}
		return ret;
	}

	@Override
	public String[] list(FileSourceFilter filter) throws IOException {
		FileSource[] files = listFiles(filter);
		if( files == null ) {
			return null;
		}
		String[] ret = new String[files.length];
		for(int idx = 0; idx < ret.length; idx++) {
			ret[idx] = files[idx].getName();
		}
		return ret;
	}

	@Override
	public FileSource[] listFiles() throws IOException {
		return listFiles((FileSourceFilter) null);
	}

	@Override
	public FileSource[] listFiles(FileSourceFilter filter) throws IOException {
		List<NfsFileInfo> all = entries();
		if( all == null ) {
			return null;
		}
		List<FileSource> ret = new ArrayList<FileSource>(all.size());
		for(NfsFileInfo e : all) {
			FileSource f = new NfsFileSource(factory, path, e);
			if( filter == null || filter.accept(f) ) {
				ret.add(f);
			}
		}
		return ret.toArray(new FileSource[ret.size()]);
	}

	@Override
	public FileSource[] listFiles(FileSourceProgress progress) throws IOException {
		FileSource[] ret = listFiles((FileSourceFilter) null);
		if( ret != null && progress != null ) {
			progress.setMaximum(ret.length);
			progress.setProgress(ret.length);
		}
		return ret;
	}

	// ------------------------------------------------------------------ data

	/** What java.io's streams throw for a file that isn't there or can't be opened. */
	static IOException openError(String path, IOException e) {
		if( e instanceof FileNotFoundException || !(e instanceof NfsException) ) {
			return e;
		}
		switch (((NfsException) e).getStatus()) {
		case Nfs4.NFS4ERR_NOENT:
		case Nfs4.NFS4ERR_NOTDIR:
			return (IOException) new FileNotFoundException(path+" (No such file or directory)").initCause(e);
		case Nfs4.NFS4ERR_ACCESS:
		case Nfs4.NFS4ERR_PERM:
		case Nfs4.NFS4ERR_ROFS:
			return (IOException) new FileNotFoundException(path+" (Permission denied)").initCause(e);
		case Nfs4.NFS4ERR_ISDIR:
			return (IOException) new FileNotFoundException(path+" (Is a directory)").initCause(e);
		default:
			return e;
		}
	}

	/** Opens the file the path leads to, for reading or for reading and writing (made when it isn't there). */
	NfsOpenFile open(boolean write, boolean create) throws IOException {
		try {
			NfsFileInfo t = target();
			if( !create && t == null ) {
				throw new FileNotFoundException(path+" (No such file or directory)");
			}
			if( t != null && t.isDirectory() ) {
				throw new FileNotFoundException(path+" (Is a directory)");
			}
			boolean made = create && t == null;
			NfsOpenFile f = client().openFile(realPath(), write, create);
			if( made ) {
				changed();
			}
			return f;
		} catch (IOException e) {
			throw openError(path, e);
		}
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return getInputStream(0);
	}

	@Override
	public InputStream getInputStream(long startingPosition) throws IOException {
		NfsSeekableInputStream in = new NfsSeekableInputStream(this, open(false, false));
		in.seek(startingPosition);
		return in.getInputStream();
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		return getOutputStream(false);
	}

	@Override
	public OutputStream getOutputStream(boolean append) throws IOException {
		return new NfsOutputStream(this, open(true, true), append);
	}

	@Override
	public ISeekableInputStream getSeekableInputStream() throws IOException {
		return new NfsSeekableInputStream(this, open(false, false));
	}

	@Override
	public IRandomAccessStream getRandomAccessStream(String mode) throws IOException {
		NfsRandomAccessIoController io = new NfsRandomAccessIoController(this, mode);
		try {
			return new FileSourceRandomAccessStream(io, mode);
		} catch (IOException | RuntimeException e) {
			try {
				io.close();
			} catch (Exception e2) {
				e.addSuppressed(e2);
			}
			throw e;
		}
	}

	// ------------------------------------------------------------------ locks

	/** Byte-range locks are the server's, so every NFS client of the file sees them. */
	@Override
	public LockScope getLockScope() {
		return LockScope.CLUSTER;
	}

	@Override
	public FileSourceCapabilities getCapabilities() {
		return FileSourceCapabilities.defaults(getLockScope()).withOrderedOperations(true).withAtomicMkdir(true);
	}

	/**
	 * A file's range is locked on the server. A folder can't be (NFS has no lock for one), so a lock on a
	 * folder holds off the callers of this JVM only.
	 */
	@Override
	public FileSourceLock tryLock(long position, long size, boolean shared) throws IOException {
		LockManager.requireExists(this);
		LockBackend backend = isDirectory() ? LockBackend.inProcess() : new NfsLockBackend(factory, realPath());
		return LockManager.get().tryAcquire(this, LockManager.keyOf(this), position, size, shared, backend);
	}

	// ------------------------------------------------------------------ versions and links

	@Override
	public boolean isVersionSupported() throws IOException {
		return false;
	}

	@Override
	public long getVersion() throws IOException {
		return 0;
	}

	@Override
	public long getVersionDate() throws IOException {
		return 0;
	}

	@Override
	public boolean setVersionDate(long version) throws IOException {
		return false;
	}

	@Override
	public boolean setVersion(long version, boolean saveChange) throws IOException {
		return false;
	}

	@Override
	public long getMaxVersion() throws IOException {
		return 0;
	}

	@Override
	public URL toURL() throws MalformedURLException {
		int port = factory.getPort();
		String portText = port == NfsFileSourceFactory.DEFAULT_PORT ? "" : ":"+port;
		return new URL(FileSourceFactory.FILE_SOURCE_PROTOCOL+"://"+factory.getHost()+portText+path
				+"?"+FileSourceFactory.QUERY_STRING_SOURCE_TYPE+"="+NfsFileSourceFactory.FACTORY_ID);
	}
}
