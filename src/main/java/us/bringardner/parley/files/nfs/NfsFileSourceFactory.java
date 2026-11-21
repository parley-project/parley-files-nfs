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

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import us.bringardner.parley.core.util.Durations;
import us.bringardner.parley.files.ConnectionSetting;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.FileSourceUser;
import us.bringardner.parley.nfs.client.NfsClient;
import us.bringardner.parley.nfs.client.NfsKerberos;

/**
 * FileSources on an NFSv4 server: <code>nfs://host[:port]/path</code>. The path is below the mount
 * ({@link #PROP_MOUNT}, "/" for the server's whole export).
 * <p>
 * Logs in with AUTH_SYS (a user and group id, which the server believes) or with Kerberos
 * (krb5, krb5i or krb5p). One {@link NfsClient} serves every file of a factory; it makes the
 * connection again by itself when it is lost.
 *
 * @since 2.0.0
 */
public class NfsFileSourceFactory extends FileSourceFactory {

	private static final long serialVersionUID = 1L;

	public static final String FACTORY_ID = "nfs";
	public static final int DEFAULT_PORT = 2049;

	public static final String PROP_HOST = "host";
	public static final String PROP_PORT = "port";
	public static final String PROP_MOUNT = "mount";
	public static final String PROP_AUTH = "auth";
	public static final String PROP_UID = "uid";
	public static final String PROP_GID = "gid";
	public static final String PROP_SERVICE = "service";
	public static final String PROP_PRINCIPAL = "principal";
	public static final String PROP_KEYTAB = "keytab";
	public static final String PROP_DELEGATIONS = "delegations";
	public static final String PROP_TIMEOUT = "timeout";
	public static final String PROP_ATTRIBUTE_CACHE_TTL = "attributeCacheTtl";

	public static final String AUTH_SYS = "sys";
	public static final String AUTH_KRB5 = "krb5";
	public static final String AUTH_KRB5I = "krb5i";
	public static final String AUTH_KRB5P = "krb5p";

	public static final long DEFAULT_ATTRIBUTE_CACHE_TTL = 2000;
	public static final int DEFAULT_TIMEOUT = 30000;

	private String host;
	private int port = DEFAULT_PORT;
	private String mount = "/";
	private String auth = AUTH_SYS;
	private long uid = 1000;
	private long gid = 1000;
	private String service;
	private String principal;
	private String keytab;
	private boolean delegations;
	private int timeout = DEFAULT_TIMEOUT;
	private long attributeCacheTtl = DEFAULT_ATTRIBUTE_CACHE_TTL;

	private transient volatile NfsClient client;
	private transient volatile FileSource currentDir;
	private transient volatile FileSource[] roots;

	public NfsFileSourceFactory() {
	}

	// ------------------------------------------------------------------ settings

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host == null || host.trim().isEmpty() ? null : host.trim();
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port <= 0 ? DEFAULT_PORT : port;
	}

	public String getMount() {
		return mount;
	}

	public void setMount(String mount) {
		this.mount = mount == null || mount.trim().isEmpty() ? "/" : mount.trim();
	}

	public String getAuth() {
		return auth;
	}

	public void setAuth(String auth) {
		String a = auth == null ? AUTH_SYS : auth.trim().toLowerCase();
		if( a.isEmpty() ) {
			a = AUTH_SYS;
		}
		if( !a.equals(AUTH_SYS) && !a.equals(AUTH_KRB5) && !a.equals(AUTH_KRB5I) && !a.equals(AUTH_KRB5P) ) {
			throw new IllegalArgumentException("Log in with sys, krb5, krb5i or krb5p, not '"+auth+"'");
		}
		this.auth = a;
	}

	public long getUid() {
		return uid;
	}

	public void setUid(long uid) {
		this.uid = uid;
	}

	public long getGid() {
		return gid;
	}

	public void setGid(long gid) {
		this.gid = gid;
	}

	/** The Kerberos service name: nfs@host (host based) or nfs/host@REALM; empty is nfs@ the host. */
	public String getService() {
		return service;
	}

	public void setService(String service) {
		this.service = service == null || service.trim().isEmpty() ? null : service.trim();
	}

	public String getPrincipal() {
		return principal;
	}

	public void setPrincipal(String principal) {
		this.principal = principal == null || principal.trim().isEmpty() ? null : principal.trim();
	}

	public String getKeytab() {
		return keytab;
	}

	public void setKeytab(String keytab) {
		this.keytab = keytab == null || keytab.trim().isEmpty() ? null : keytab.trim();
	}

	public boolean isDelegations() {
		return delegations;
	}

	public void setDelegations(boolean delegations) {
		this.delegations = delegations;
	}

	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int millis) {
		this.timeout = millis <= 0 ? DEFAULT_TIMEOUT : millis;
	}

	/** Milliseconds a file's details are kept: 0 asks the server every time, -1 keeps them until refresh(). */
	public long getAttributeCacheTtl() {
		return attributeCacheTtl;
	}

	public void setAttributeCacheTtl(long millis) {
		this.attributeCacheTtl = millis;
	}

	boolean isKerberos() {
		return !AUTH_SYS.equals(auth);
	}

	@Override
	public List<ConnectionSetting> getConnectionSettings() {
		return List.of(
				ConnectionSetting.text(PROP_HOST, "Host").asRequired(),
				ConnectionSetting.integer(PROP_PORT, "Port", 1L, 65535L).withDefault(""+DEFAULT_PORT).asRequired(),
				ConnectionSetting.text(PROP_MOUNT, "Mount").withDefault("/")
					.withDescription("The folder of the server's export that is this file system's root"),
				ConnectionSetting.choice(PROP_AUTH, "Log in with", AUTH_SYS, AUTH_KRB5, AUTH_KRB5I, AUTH_KRB5P)
					.withDefault(AUTH_SYS)
					.withDescription("sys: the user and group id below; krb5: Kerberos; krb5i adds integrity and krb5p privacy"),
				ConnectionSetting.integer(PROP_UID, "User id", 0L, 4294967295L).withDefault("1000").visibleWhen(PROP_AUTH, AUTH_SYS),
				ConnectionSetting.integer(PROP_GID, "Group id", 0L, 4294967295L).withDefault("1000").visibleWhen(PROP_AUTH, AUTH_SYS),
				ConnectionSetting.text(PROP_SERVICE, "Kerberos service name")
					.withDescription("nfs@host, or nfs/host@REALM; empty is nfs@ and the host"),
				ConnectionSetting.text(PROP_PRINCIPAL, "Kerberos principal")
					.withDescription("With a keytab: who logs in. Empty without one: the ticket cache"),
				ConnectionSetting.localFile(PROP_KEYTAB, "Kerberos keytab file")
					.withDescription("Empty: use the ticket cache (kinit)"),
				ConnectionSetting.durationOrMinusOne(PROP_ATTRIBUTE_CACHE_TTL, "Cache file details for")
					.withDefault(formatTtl(DEFAULT_ATTRIBUTE_CACHE_TTL))
					.withDescription("A duration (2s); 0 = always ask the server, -1 = until refresh"),
				ConnectionSetting.duration(PROP_TIMEOUT, "Wait for the server").asAdvanced()
					.withDefault(Durations.format(DEFAULT_TIMEOUT)),
				ConnectionSetting.bool(PROP_DELEGATIONS, "Keep reads in memory (delegations)").asAdvanced()
					.withDescription("The server lets this client keep a file's data while nobody else changes it"));
	}

	static long parseTtl(String v) {
		String t = v.trim();
		return t.startsWith("-") ? -1 : Durations.parse(t);
	}

	static String formatTtl(long ttl) {
		return ttl < 0 ? "-1" : Durations.format(ttl);
	}

	// ------------------------------------------------------------------ connection

	@Override
	protected synchronized boolean connectImpl() throws IOException {
		if( client != null ) {
			return true;
		}
		if( host == null ) {
			throw new IOException("An NFS server needs a host");
		}
		NfsClient c;
		if( isKerberos() ) {
			NfsKerberos.Protection level = AUTH_KRB5P.equals(auth) ? NfsKerberos.Protection.PRIVACY
					: AUTH_KRB5I.equals(auth) ? NfsKerberos.Protection.INTEGRITY : NfsKerberos.Protection.AUTHENTICATION;
			String name = service == null ? "nfs@"+host : service;
			NfsKerberos k = keytab != null && principal != null
					? NfsKerberos.withKeytab(name, principal, new File(keytab), level)
					: new NfsKerberos(name, null, level);
			c = new NfsClient(host, port, mount, timeout, k, delegations);
		} else {
			c = new NfsClient(host, port, mount, uid, gid, timeout, delegations);
		}
		client = c;
		roots = null;
		return true;
	}

	@Override
	protected synchronized void disConnectImpl() throws IOException {
		NfsClient c = client;
		client = null;
		roots = null;
		if( c != null ) {
			c.close();
		}
	}

	@Override
	public boolean isConnected() {
		return client != null;
	}

	/** The connection, made when there isn't one. */
	NfsClient client() throws IOException {
		NfsClient c = client;
		if( c == null ) {
			connect();
			c = client;
			if( c == null ) {
				throw new IOException("Not connected to "+getURL());
			}
		}
		return c;
	}

	// ------------------------------------------------------------------ files

	@Override
	public FileSource createFileSource(String path) throws IOException {
		connect();
		if( path == null || path.isEmpty() ) {
			path = "/";
		}
		path = path.replace('\\', '/');
		if( !path.startsWith("/") ) {
			return getCurrentDirectory().getChild(path);
		}
		return new NfsFileSource(this, path);
	}

	@Override
	public FileSource[] listRoots() throws IOException {
		FileSource[] r = roots;
		if( r == null ) {
			r = new FileSource[] { createFileSource("/") };
			roots = r;
		}
		return r;
	}

	@Override
	public FileSource getCurrentDirectory() throws IOException {
		FileSource d = currentDir;
		if( d == null ) {
			d = createFileSource("/");
			currentDir = d;
		}
		return d;
	}

	@Override
	public void setCurrentDirectory(FileSource dir) throws IOException {
		currentDir = dir;
	}

	@Override
	public boolean isVersionSupported() {
		return false;
	}

	@Override
	public String getTypeId() {
		return FACTORY_ID;
	}

	@Override
	public char getPathSeparatorChar() {
		return ':';
	}

	@Override
	public char getSeparatorChar() {
		return '/';
	}

	/** The user and group this client acts as: what it sends with AUTH_SYS; unknown (0) with Kerberos. */
	@Override
	public FileSourceUser whoAmI() {
		if( isKerberos() ) {
			return super.whoAmI();
		}
		return new FileSourceUser((int) uid, ""+uid, (int) gid, ""+gid);
	}

	@Override
	public FileSource createSymbolicLink(FileSource newFileLink, FileSource existingFile) throws IOException {
		return createSymbolicLink(newFileLink, existingFile.getAbsolutePath());
	}

	@Override
	public FileSource createSymbolicLink(FileSource newFileLink, String linkText) throws IOException {
		if( linkText == null || linkText.isEmpty() ) {
			throw new IOException("A symbolic link needs text");
		}
		String where = newFileLink instanceof NfsFileSource ? ((NfsFileSource) newFileLink).serverPath() : newFileLink.getAbsolutePath();
		client().symlink(where, linkText);
		noteChange();
		newFileLink.refresh();
		return newFileLink;
	}

	/** NFSv4 has a LINK operation, but this client doesn't offer hard links. */
	@Override
	public FileSource createLink(FileSource newFileLink, FileSource existingFile) throws IOException {
		throw new IOException("Hard links are not supported by the NFS file source");
	}

	// ------------------------------------------------------------------ properties

	/** A watch service that asks the server's change attribute whether a folder changed, and lists it only then. */
	@Override
	public us.bringardner.parley.files.FileSourceWatchService newWatchService() throws IOException {
		return new NfsWatchService();
	}

	@Override
	public FileSourceFactory createThreadSafeCopy() {
		NfsFileSourceFactory ret = new NfsFileSourceFactory();
		ret.setConnectionProperties(getConnectProperties());
		return ret;
	}

	@Override
	public boolean isSameFileSystem(FileSourceFactory other) {
		if( other == this ) {
			return true;
		}
		if( !(other instanceof NfsFileSourceFactory) ) {
			return false;
		}
		NfsFileSourceFactory o = (NfsFileSourceFactory) other;
		return port == o.port && host != null && host.equalsIgnoreCase(o.host) && mount.equals(o.mount);
	}

	@Override
	public Properties getConnectProperties() {
		Properties p = new Properties();
		p.setProperty(PROP_HOST, host == null ? "" : host);
		p.setProperty(PROP_PORT, ""+port);
		p.setProperty(PROP_MOUNT, mount);
		p.setProperty(PROP_AUTH, auth);
		p.setProperty(PROP_UID, ""+uid);
		p.setProperty(PROP_GID, ""+gid);
		p.setProperty(PROP_SERVICE, service == null ? "" : service);
		p.setProperty(PROP_PRINCIPAL, principal == null ? "" : principal);
		p.setProperty(PROP_KEYTAB, keytab == null ? "" : keytab);
		p.setProperty(PROP_DELEGATIONS, ""+delegations);
		p.setProperty(PROP_TIMEOUT, Durations.format(timeout));
		p.setProperty(PROP_ATTRIBUTE_CACHE_TTL, formatTtl(attributeCacheTtl));
		return p;
	}

	@Override
	public void setConnectionProperties(Properties p) {
		if( p.containsKey(PROP_HOST) ) {
			setHost(p.getProperty(PROP_HOST));
		}
		if( has(p, PROP_PORT) ) {
			setPort(Integer.parseInt(p.getProperty(PROP_PORT).trim()));
		}
		if( p.containsKey(PROP_MOUNT) ) {
			setMount(p.getProperty(PROP_MOUNT));
		}
		if( has(p, PROP_AUTH) ) {
			setAuth(p.getProperty(PROP_AUTH));
		}
		if( has(p, PROP_UID) ) {
			setUid(Long.parseLong(p.getProperty(PROP_UID).trim()));
		}
		if( has(p, PROP_GID) ) {
			setGid(Long.parseLong(p.getProperty(PROP_GID).trim()));
		}
		if( p.containsKey(PROP_SERVICE) ) {
			setService(p.getProperty(PROP_SERVICE));
		}
		if( p.containsKey(PROP_PRINCIPAL) ) {
			setPrincipal(p.getProperty(PROP_PRINCIPAL));
		}
		if( p.containsKey(PROP_KEYTAB) ) {
			setKeytab(p.getProperty(PROP_KEYTAB));
		}
		if( has(p, PROP_DELEGATIONS) ) {
			setDelegations(Boolean.parseBoolean(p.getProperty(PROP_DELEGATIONS).trim()));
		}
		if( has(p, PROP_TIMEOUT) ) {
			setTimeout(Durations.toIntMillis(Durations.parse(p.getProperty(PROP_TIMEOUT))));
		}
		if( has(p, PROP_ATTRIBUTE_CACHE_TTL) ) {
			setAttributeCacheTtl(parseTtl(p.getProperty(PROP_ATTRIBUTE_CACHE_TTL)));
		}
	}

	private static boolean has(Properties p, String key) {
		String v = p.getProperty(key);
		return v != null && !v.trim().isEmpty();
	}

	/** nfs://host[:port]/path : the host and port; the path is the caller's. */
	@Override
	public void setConnectionProperties(URL url) {
		String authority = url.getAuthority();
		if( authority == null || authority.isEmpty() ) {
			return;
		}
		int at = authority.lastIndexOf('@');
		if( at >= 0 ) {
			authority = authority.substring(at+1);
		}
		int colon = authority.lastIndexOf(':');
		if( colon > 0 && authority.indexOf(']') < colon ) {
			setPort(Integer.parseInt(authority.substring(colon+1)));
			authority = authority.substring(0, colon);
		}
		setHost(decode(authority));
	}

	private static String decode(String s) {
		try {
			return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8.name());
		} catch (java.io.UnsupportedEncodingException | IllegalArgumentException e) {
			return s;
		}
	}

	@Override
	public String getTitle() {
		return getURL();
	}

	@Override
	public String getURL() {
		return FACTORY_ID+"://"+host+":"+port;
	}
}
