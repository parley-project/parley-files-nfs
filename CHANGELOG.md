# Changelog

## parley-files-nfs 2.0.0 (unreleased)

### Added

- `NfsFileSourceLiveTest`: the FileSource against a real NFS server (Linux nfsd), skipped unless `nfs.live.host` is set.
- **The module** (T10, D87): a parley-files `FileSource` for NFSv4 servers, `nfs://host[:port]/path`, on the
  parley-nfs client. Login with AUTH_SYS or Kerberos (krb5, krb5i, krb5p); files, random access and seekable
  streams, permissions, owner and group, times, symbolic links, and byte-range locks on the server
  (`LockScope.CLUSTER`). See the README.
- Tests: the shared parley-files suite (files, random access, NIO provider, java.io.File behaviour, locks with a
  second JVM as another NFS client) against a parley-nfs `NfsServer`, and tests of the settings and of what is NFS's
  own.
