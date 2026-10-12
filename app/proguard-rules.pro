# JDBC service entry points; no blanket warning suppression.
-keep class com.microsoft.sqlserver.jdbc.SQLServerDriver { public *; }
-keep class org.mariadb.jdbc.Driver { public *; }
-keep class com.example.datasail.core.StrictTlsPlugin { public *; }
-keepattributes Signature,InnerClasses,EnclosingMethod

-keep class com.example.datasail.core.SqlServerTrustManager { public <init>(java.lang.String); public *; }

-keep class com.example.datasail.core.PostgreSqlTlsFactory { public <init>(java.util.Properties); }
-keep class org.postgresql.Driver { public <init>(); }
