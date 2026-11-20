# parley-files-nfs

> parley-files-nfs is part of **Parley**, a family of Java libraries for implementing internet protocols.

A parley-files `FileSource` for **NFSv4** servers (RFC 7530), built on the client in parley-nfs. Put it on the class
path and `nfs://host[:port]/path` works anywhere a `FileSource` does. Nothing in it is NFS server code: it talks to
any NFSv4.0 server (Linux `nfsd`, a NAS, or a parley-nfs `NfsServer`).

## Getting started

```xml
<dependency>
    <groupId>us.bringardner.parley</groupId>
    <artifactId>parley-files-nfs</artifactId>
    <version>2.0.0</version>
</dependency>
```

```java
FileSourceFactory nfs = FileSourceFactory.getFileSourceFactory("nfs");
Properties p = new Properties();
p.setProperty("host", "files.example.com");
p.setProperty("mount", "/export/data");
nfs.setConnectionProperties(p);
nfs.connect();

FileSource report = nfs.createFileSource("/reports/2026.csv");
try (InputStream in = report.getInputStream()) {
    ...
}
```

Paths are below the **mount**: `/reports/2026.csv` above is `/export/data/reports/2026.csv` on the server. NFSv4 serves
one tree, so the factory has one root (`/`).

## Connection settings

| Property | Default | |
|---|---|---|
| `host` | | the server (required) |
| `port` | 2049 | |
| `mount` | `/` | the folder of the server's export that is this file system's root |
| `auth` | `sys` | `sys` (AUTH_SYS), `krb5`, `krb5i` (adds integrity) or `krb5p` (adds privacy) |
| `uid`, `gid` | 1000 | `sys`: the user and group the server is told. The server believes them |
| `service` | `nfs@host` | Kerberos: the service name, `nfs@host` or `nfs/host@REALM` |
| `principal`, `keytab` | | Kerberos: with a keytab file, who logs in. Empty: the ticket cache (`kinit`) |
| `delegations` | false | let the server give this client read delegations (it then keeps file data while no one else changes it) |
| `timeout` | 30s | how long to wait for the server |
| `attributeCacheTtl` | 2s | how long what the server said about a file is kept; `0` asks every time, `-1` keeps it until `refresh()` |

## What it does

- **Files**: read, write, append, list, rename (never replaces), delete, mkdir/mkdirs, `createNewFile` (the server
  decides when several callers race for a name).
- **Random access** (`getRandomAccessStream("r"|"rw")`) and **seekable input** (`getSeekableInputStream()`) over one open
  file on the server, with buffering.
- **Permissions**: the mode bits (`canOwnerRead`... `setOtherExecutable`). `canRead()`, `canWrite()` and `canExecute()`
  are the server's word (NFS ACCESS) together with the mode bits where the owner and group are numeric ids (a server
  without a name mapping) and the login is `sys`. User 0 may read and write anything, as on a local file system.
- **Owner and group** (`getOwner()`, `getGroup()`, `setOwner`, `setGroup`): as the server sends them, usually the
  numeric ids or `name@domain`.
- **Times**: modified, accessed and (where the server keeps one) created; modified and accessed can be set.
- **Symbolic links**: `getLinkText()`, `getLinkedTo()`, `factory.createSymbolicLink(link, text)`. `length()` of a link is
  its text's; `contentLength()` the file's. A link in the middle of a path is followed. Hard links are refused.
- **Locks** (`tryLock`, `lock`): byte ranges are locked **on the server**, so they hold off every NFS client of the file,
  not just this JVM (`LockScope.CLUSTER`). A folder can't be locked on the server, so a lock on one holds off the
  callers of this JVM only.

## Limits

- NFSv4.0 only (no NFSv4.1/4.2 sessions, no server-side copy).
- A lock is lost if the client can't renew its lease and the server frees it; the lock keeps reporting it is held. A
  held lock is reclaimed after a reconnect only where the server's grace period allows it.
- `createNewFile` is exclusive on the server; `renameTo` checks the new name first, so a name made by someone else
  between the check and the rename is replaced.
- The creation time can't be set (NFSv4.0 has no way to).
- No Kerberos test runs here (it needs a KDC); the parley-nfs client tests cover the login itself.

## Testing

`mvn test` runs the shared parley-files suite (the same one the other providers run: files, random access, NIO
provider, java.io.File behaviour, locks, including a second JVM as another NFS client) against a parley-nfs `NfsServer`
that exports a temporary folder, as the user running the tests. Run it as a user other than root: the superuser's
rules (everything may be read and written) differ from the ones the suite checks for.
