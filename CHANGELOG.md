# Changelog

## parley-files-nfs 2.0.0 (unreleased)

### Added

- `NfsFileSourceKerberosTest`: the FileSource logging in with krb5, krb5i and krb5p to a parley-nfs server that takes only Kerberos, with a Kerby KDC in the JVM (no outside machines); also a refused AUTH_SYS login and a wrong keytab. `TestKdc` is a copy of parley-nfs's, which doesn't publish its test classes.
- `NfsFileSourceLiveTest`: the FileSource against a real NFS server (Linux nfsd), skipped unless `nfs.live.host` is set.
- **The module** (T10, D87): a parley-files `FileSource` for NFSv4 servers, `nfs://host[:port]/path`, on the
  parley-nfs client. Login with AUTH_SYS or Kerberos (krb5, krb5i, krb5p); files, random access and seekable
  streams, permissions, owner and group, times, symbolic links, and byte-range locks on the server
  (`LockScope.CLUSTER`). See the README.
- Tests: the shared parley-files suite (files, random access, NIO provider, java.io.File behaviour, locks with a
  second JVM as another NFS client) against a parley-nfs `NfsServer`, and tests of the settings and of what is NFS's
  own.
