# Android SASL API bridge

Only the unmodified `javax.security.sasl.SaslClient` interface and `SaslException` value/exception type from Apache Harmony are included. The pinned upstream commit, source hashes, license and notice are recorded in `upstream.lock.json`; every compile verifies those bytes.

Android API26/API30 lack these Java SE types. MongoDB Driver 5.6.5's own SCRAM client implements this interface and performs authentication and server-signature verification. This module provides no SASL provider, no authentication implementation, no fallback, and no fake success. Generic `Sasl`, GSSAPI/Kerberos and other mechanisms remain unsupported. The app fixes authentication to SCRAM-SHA-256.

The app consumes this module at runtime only. JVM integration tests continue using the JDK's own API; therefore JVM tests do not validate this bridge. Real MongoDB authentication, wrong-password and cancellation tests on ART are required. This bridge is not a fix for the remaining R8 missing APIs.
