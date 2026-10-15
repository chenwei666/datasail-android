# JDBC service entry points; no blanket warning suppression.
-keep class com.microsoft.sqlserver.jdbc.SQLServerDriver { public *; }
-keep class org.mariadb.jdbc.Driver { public *; }
-keep class com.example.datasail.core.StrictTlsPlugin { public *; }
-keepattributes Signature,InnerClasses,EnclosingMethod

-keep class com.example.datasail.core.SqlServerTrustManager { public <init>(java.lang.String); public *; }

-keep class com.example.datasail.core.PostgreSqlTlsFactory { public <init>(java.util.Properties); }
-keep class org.postgresql.Driver { public <init>(); }

# release-test-api.pro is an explicit Gradle input alongside this file, so changes
# to the independently installed test APK's shared API rules invalidate R8 output.

# SQL Server resolves ListResourceBundle implementations by their literal base
# name, outside bytecode call graphs. Keep the bundle identities and constructors.
-keep class com.microsoft.sqlserver.jdbc.SQLServerResource* extends java.util.ListResourceBundle { *; }

# Connector/J binds URL/Properties keys by matching Builder field and method
# names. Configuration fields are also reflected for validation and redaction.
-keepclassmembers class org.mariadb.jdbc.Configuration { <fields>; }
-keep class org.mariadb.jdbc.Configuration$Builder { *; }

# AiServiceUiTest supplies its isolated TLS fixture through this existing internal
# constructor via reflection, which constant-pool call-site analysis cannot see.
-keepclassmembers,allowobfuscation class com.example.datasail.core.AiClient {
    public <init>(com.example.datasail.core.AiService, char[], javax.net.ssl.SSLContext);
}
