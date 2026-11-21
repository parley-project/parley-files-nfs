# Changelog

## parley-files-nfs 2.0.0 (unreleased)

### Added (2026-11-21: extended attributes, D101)

- **Extended attributes** (`FileSource.isExtendedAttributesSupported`, `getExtendedAttributeNames`,
  `getExtendedAttribute`, `setExtendedAttribute`, `removeExtendedAttribute`) through NFSv4.2 (RFC 8276): supported for
  an existing file or folder (not a symbolic link) when the server says `xattr_support`. Needs parley-nfs with
  `NfsClient.getXattr` and the rest, and parley-files with the methods.

### Added (2026-11-21: watching a folder, D97)

- **`NfsFileSourceFactory.newWatchService()`**: the watch service of parley-files, except that it asks the server's
  change attribute (one small GETATTR) and lists a folder only when that has moved, so it sees the changes of other
  clients cheaply and doesn't depend on a directory delegation (a Linux server tells of no changes). Needs
  parley-nfs `NfsClient.changeId`.

### Added

- `NfsFileSourceKerberosTest`: the FileSource logging in with krb5, krb5i and krb5p to a parley-nfs server that takes only Kerberos, with a Kerby KDC in the JVM (no outside machines); also a refused AUTH_SYS login and a wrong keytab. `TestKdc` comes from parley-nfs's test jar.
- `NfsFileSourceLiveTest`: the FileSource against a real NFS server (Linux nfsd), skipped unless `nfs.live.host` is set.
- **The module** (T10, D87): a parley-files `FileSource` for NFSv4 servers, `nfs://host[:port]/path`, on the
  parley-nfs client. Login with AUTH_SYS or Kerberos (krb5, krb5i, krb5p); files, random access and seekable
  streams, permissions, owner and group, times, symbolic links, and byte-range locks on the server
  (`LockScope.CLUSTER`). See the README.
- Tests: the shared parley-files suite (files, random access, NIO provider, java.io.File behaviour, locks with a
  second JVM as another NFS client) against a parley-nfs `NfsServer`, and tests of the settings and of what is NFS's
  own.
